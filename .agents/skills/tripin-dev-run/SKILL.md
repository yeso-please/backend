---
name: tripin-dev-run
description: TriPin 개발 스택(Spring backend, Python AI 임베딩 서버, backend/test_frontend 테스트 프론트)을 Docker Compose로 로컬 컴퓨터에서 함께 실행하고 상태를 확인한다. "백엔드·AI·테스트 프론트 띄워줘", "로컬 개발 서버 실행", "dev-rds로 backend 실행" 같은 요청에 사용한다. 스키마 변경·migration 작성·RDS 데이터 적재에는 사용하지 않는다($db-man, $flyway-rds-sync, $tourapi-attraction-backfill 사용).
---

# tripin-dev-run — backend + AI + 테스트 프론트 실행

목표는 **Docker Desktop만 있으면** 어느 팀원 컴퓨터에서든 세 서버를 띄우는 것이다. JDK·Node·Python은 이미지 안에 있으므로 따로 설치하지 않는다. 브라우저는 프론트(5173)만 보고, Vite proxy가 `/api` → backend, `/ai-api` → AI로 넘긴다. DB에는 backend만 연결한다.

| 서비스 | 이미지 | 포트(127.0.0.1만) | 준비 완료 기준 |
|---|---|---|---|
| `ai` | `../ai/Dockerfile` (CPU torch, 모델을 빌드 때 받음) | 8000 | `GET /health` → `UP` (모델 로딩 동안 503) |
| `backend` | `backend/Dockerfile` (JDK 21로 bootJar 빌드 → JRE 21 실행, RDS CA 포함) | 8080 | 로그 `Started BackendApplication`, `GET /v3/api-docs` 200 |
| `frontend` | `backend/test_frontend/Dockerfile` (Node 22, Vite dev server) | 5173 | `GET /` 200, `GET /api/onboarding/questions` 200 |

파일: `backend/compose.dev.yaml`(기본, 개발 RDS), `backend/compose.dev.local-db.yaml`(Docker PostgreSQL로 바꾸는 override), `backend/docker/entrypoint.sh`.

## 필요한 것

1. **Docker Desktop**이 켜져 있어야 한다(`docker info`). 꺼져 있으면 사용자에게 켜 달라고 한다.
2. **저장소 배치**: `backend`와 `ai`를 같은 상위 폴더에 clone한다(`<root>/backend`, `<root>/ai`). 다르면 `TRIPIN_AI_DIR` 환경변수로 ai 경로를 지정한다.
3. **`backend/.env`** (dev-rds일 때): `DB_URL`, `DB_PASSWORD`, `FLYWAY_PASSWORD`, `JWT_SECRET`(32바이트 이상). `DB_USERNAME`/`FLYWAY_USER`는 없으면 `tripin_app`/`tripin_migrator`. 선택: `KAKAO_REST_API_KEY`, `COURSE_RESTAURANT_SELECTION_SECRET`, `GEMINI_API_KEY`. Compose가 이 파일을 자동으로 읽어 필요한 키만 컨테이너 환경변수로 넘기며 이미지에는 넣지 않는다(`.dockerignore`). 값에 `$`가 있으면 Compose가 변수로 해석하므로 `$$`로 적는다.
4. **RDS 보안 그룹**이 이 컴퓨터의 공인 IP `/32`를 허용해야 한다. 연결 시간 초과는 대부분 이것이다. `0.0.0.0/0`으로 열지 않는다.

RDS CA 인증서는 이미지에 들어 있고, `entrypoint.sh`가 `DB_URL`의 `sslrootcert`를 컨테이너 안 경로로 바꾼다. 그래서 `.env`의 CA 경로가 다른 팀원 컴퓨터 기준이어도 된다.

## 1. 대상 점검 (dev-rds)

`.env`의 값은 출력하지 말고 키 존재와 형식만 본다. `DB_URL`이 `*.rds.amazonaws.com`, database `tripin_dev`, `currentSchema=app`, `sslmode=verify-full`인지 확인한다. 운영처럼 보이거나 불명확하면 중단한다.

## 2. Flyway 게이트 (dev-rds)

backend가 시작되면 Spring Flyway가 공용 개발 RDS에 pending migration을 **자동 적용**한다. 이는 공유 스키마 쓰기다.

1. 로컬 최신 버전: `src/main/resources/db/migration`의 가장 큰 `V{N}`.
2. RDS 버전을 Docker의 psql로 **읽기 전용** 확인한다. 비밀번호는 `PGPASSWORD`로만 넘기고 출력하지 않는다. CA는 `https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem`을 받아 마운트한다.

   ```powershell
   $env:PGPASSWORD = '<.env의 DB_PASSWORD>'; $env:PGOPTIONS = '-c default_transaction_read_only=on'
   docker run --rm -e PGPASSWORD -e PGOPTIONS -v "<CA폴더>:/ca:ro" postgres:17-alpine psql "host=<RDS host> port=5432 dbname=tripin_dev user=tripin_app sslmode=verify-full sslrootcert=/ca/global-bundle.pem connect_timeout=10" -c "select max(version::int) from app.flyway_schema_history where success"
   $env:PGPASSWORD = $null; $env:PGOPTIONS = $null
   ```

3. 같으면 진행한다. **RDS가 뒤처져 있으면** 적용될 migration 목록을 보여 주고, 사용자가 이번 대화에서 명시적으로 승인한 경우에만 띄운다. 이때 `$db-man`의 개발 RDS 게이트(스냅샷 확인 등)를 따른다. 확인할 수 없으면 그 사실을 알리고 승인을 받는다.
4. RDS가 로컬보다 **앞서 있으면** Flyway validate가 실패한다. `git pull`로 backend 최신 main을 받으라고 안내한다. `repair`, `clean`, `baselineOnMigrate`, `ddl-auto=update`, history 수정으로 우회하지 않는다.

local-db 모드는 각자의 Docker DB라 이 게이트가 필요 없다.

## 3. 실행

`backend/`에서 실행한다. 포트 8000/8080/5173을 이미 쓰는 프로세스가 있으면 먼저 무엇인지 확인하고, 남의 프로세스를 임의로 종료하지 않는다.

```powershell
docker compose -f compose.dev.yaml up -d --build                                # 개발 RDS
docker compose -f compose.dev.yaml -f compose.dev.local-db.yaml up -d --build   # Docker PostgreSQL (빈 DB, 지역·관광지 데이터 없음)
```

첫 빌드는 AI 이미지(CPU torch와 모델 다운로드)와 Gradle 의존성 때문에 수 분 이상 걸린다. 이후에는 캐시를 쓴다. 코드를 바꿨으면 `--build`로 다시 띄운다. 테스트 프론트는 이미지에 소스를 복사하므로 실시간 반영(HMR)이 되지 않는다.

## 4. 확인

```powershell
docker compose -f compose.dev.yaml ps
docker compose -f compose.dev.yaml logs backend | Select-String "Flyway|Schema|migration|Started BackendApplication|ERROR"
Invoke-RestMethod http://127.0.0.1:8000/health                                    # status UP, modelVersion, templateVersions, dimension 384
(Invoke-WebRequest http://127.0.0.1:8080/v3/api-docs).StatusCode                  # 200
(Invoke-WebRequest http://127.0.0.1:5173/api/onboarding/questions).StatusCode     # 200 (프론트 → backend proxy)
```

dev-rds면 backend 로그에서 “Schema "app" is up to date” 또는 적용된 migration 수와 `HikariPool ... Start completed`를 확인한다. 사용자에게 `http://127.0.0.1:5173` 주소, 서비스별 상태, 확인하지 못한 항목을 보고한다.

## 종료

```powershell
docker compose -f compose.dev.yaml down                                           # 컨테이너만 제거
docker compose -f compose.dev.yaml -f compose.dev.local-db.yaml down              # local-db 모드
```

local DB 데이터를 지우는 `down -v`는 사용자가 요청할 때만 한다.

## 알려진 연동 차이 — 실행 전에 현재 코드로 다시 확인

backend는 ai 계약대로 `HttpEmbeddingClient`가 `{requestId, modelVersion, templateVersion, profile}`을 보내고 `embedding.model-version=mminilm-l12-ft-b64-v2`(파인튜닝 모델, 파일은 docs/runbooks/local-dev-stack.md 3단계)이다. 템플릿 버전은 제출의 설문 형식으로 정한다(AI Hub `aihub-traveler-v1` → 2, 구형 `demo-mbti-v1` → 1). 관광지 벡터는 `embedding.attraction-batch.enabled=true`로 한 번 실행하는 배치가 채운다(`docs/design/recommendation.md` 5절). 이 변경이 들어가지 않은 backend 브랜치를 띄우면 온보딩 취향 임베딩이 409(버전) 또는 400(`profile` 없음)으로 실패한다. 서버 기동과 다른 API에는 영향이 없다. 그 경우 사용자에게 알리고, 요청 없이 코드를 고치지 않는다.

빠른 확인: `docker compose -f compose.dev.yaml logs backend | Select-String "Embedding service returned"`에 409/400이 보이면 위 차이다.

## Docker 없이 실행 (대안)

Docker를 쓸 수 없을 때만 쓴다. JDK 21, Node 22.12+, Python 3.11+이 필요하다.

- backend: `scripts/start-backend.ps1 -SpringProfile dev-rds|local [-RdsCaPath <CA 파일>] [-CheckOnly]`. Spring은 `.env`를 읽지 않으므로 스크립트가 허용된 키만 읽어 넘기고, RDS 대상·CA 파일·JDK 21·포트를 점검한 뒤 `gradlew bootRun`을 실행한다. local 모드는 RDS 값이 섞이지 않게 DB 변수를 비운다.
- AI: ai 저장소의 `pip install -e ".[serve,dev]"`는 현재 setuptools 패키지 자동 탐색 오류(`Multiple top-level packages discovered`)로 실패한다. 대신 `pip install numpy pandas "sentence-transformers>=3" torch fastapi "uvicorn[standard]"` 후 ai 폴더에서 `python -m uvicorn app.main:app --host 127.0.0.1 --port 8000`.
- 프론트: `test_frontend`에서 `npm ci`, `.env.example`을 `.env.local`로 복사, `npm run dev`.

## 금지

- `.env`, `config/application-secret.yaml`, `test_frontend/.env.local`의 비밀값을 출력·커밋·이미지에 넣지 않는다. RDS endpoint·계정 ID·보안 그룹 ID를 이 스킬에 적지 않는다.
- 운영 RDS 연결, 승인 없는 공용 RDS migration 적용, 보안 그룹 변경, RDS 인스턴스 설정 변경은 이 스킬로 허가되지 않는다.
- 테스트 프론트를 운영 서비스로 배포하지 않는다. 포트를 `0.0.0.0`으로 공개하는 것은 사용자가 요청할 때만 한다.
