# 로컬 개발 스택 (backend + AI + 테스트 프론트)

Docker Desktop만 있으면 Spring backend, `ai` 임베딩 서버, `test_frontend`를 한 번에 띄운다. JDK·Node·Python은 이미지 안에 있어 따로 설치하지 않는다. 기본은 **개발 RDS**에 연결한다.

## 1. 처음 한 번 준비

1. **Docker Desktop** 설치 후 실행.
2. **저장소 배치**: `backend`와 `ai`를 같은 상위 폴더에 clone한다.

   ```text
   <아무 폴더>/
   ├── backend/
   └── ai/
   ```

   다른 곳에 두었으면 실행 전에 `TRIPIN_AI_DIR` 환경변수로 ai 경로를 지정한다.
3. **파인튜닝 임베딩 모델**: 추천 모델(`e5-base-rating-2022-2023-t4-all-v2`, 가중치 약 1.1GB)은 git에 없다. 비공개 S3에 있고, 팀에서 받은 기간 한정 링크(7일)로 받는다. 링크는 팀 밖으로 전달하지 않는다.
   - Python이 있으면 `backend` 폴더에서 `python scripts/fetch_e5_model.py --url "<링크>"` — SHA256 확인 후 아래 폴더에 푼다.
   - Python이 없으면 링크를 브라우저로 열어 zip(약 800MB)을 받고, `Get-FileHash <zip> -Algorithm SHA256` 결과가 `0bff9eff843ae88ffeecfb2274ba15aaa5acf63c9bb8c0cf7b2da8c1c78b9a39`인지 확인한 뒤 `ai/data/interim/models/e5-base-rating-2022-2023-t4-all-v2/`에 압축을 푼다(zip 안에 `final/` 폴더가 있다).
   - 링크가 만료됐으면 버킷 권한이 있는 팀원에게 새 링크를 요청한다([E5 전환 런북](e5-model-switch.md#체크포인트-준비)).


   ```text
   ai/data/interim/models/e5-base-rating-2022-2023-t4-all-v2/final/   ← model.safetensors, config.json, tokenizer.json …
   ```

   다른 곳에 두었으면 `TRIPIN_E5_MODEL_DIR`로 그 `final` 폴더를 지정한다. 모델이 없거나 구형 모델을 연결하면 AI healthcheck가 실패하여 새 backend 시작을 차단한다. backend의 `embedding.model-version`과 AI의 `MODEL_VERSION`이 같아야 하며, 다르면 임베딩 요청이 409다. E5 입력 계약과 체크포인트 확인은 [E5 전환 런북](e5-model-switch.md)을 따른다. `final` 전체 폴더(토크나이저·설정·`training_info.json` 포함)를 받아야 하며, MiniLM 폴더를 재사용하지 않는다.
4. **`backend/.env`**: 팀에서 안전한 경로로 받는다(git에 없다). 필요한 키는 `DB_URL`, `DB_PASSWORD`, `FLYWAY_PASSWORD`, `JWT_SECRET`(32바이트 이상)이다. 선택 키는 `KAKAO_REST_API_KEY`, `COURSE_RESTAURANT_SELECTION_SECRET`, `GEMINI_API_KEY`다.
   - `DB_URL`의 `sslrootcert` 경로는 각자 PC 기준이어도 된다. RDS CA 인증서는 이미지에 들어 있고 컨테이너가 경로를 바꾼다.
   - 값에 `$`가 있으면 `$$`로 적는다(Compose가 변수로 해석한다).
5. **RDS 보안 그룹에 내 공인 IP 등록**: AWS 콘솔 권한이 있는 팀원에게 `내 IP/32` 추가를 요청한다. 빠지면 backend가 DB 연결 시간 초과로 뜨지 않는다. `0.0.0.0/0`으로 열지 않는다. 집·학교처럼 네트워크가 바뀌면 IP도 바뀌니 다시 등록한다.

6. **(선택) 여행기 사진**: 사진 업로드까지 쓰려면 내 AWS 프로필에 사진 버킷 권한을 받고 `compose.diary-photos.yaml`을 함께 쓴다([여행기 사진 런북](diary-photo-storage.md#로컬-개발-2026-10-18)). 없으면 사진 API만 503이고 나머지는 정상이다.

## 2. 실행

`backend/` 폴더에서 실행한다(`Tripin/` 같은 상위 폴더에서 실행하면 `compose.dev.yaml`을 찾지 못한다).

```powershell
cd backend
docker compose -f compose.dev.yaml up -d --build
```

첫 빌드는 AI 모델·torch와 Gradle 의존성을 받느라 수 분 걸리고 디스크를 6~8GB 쓴다. 이후에는 캐시를 쓴다. 코드를 바꿨으면 `--build`를 붙여 다시 띄운다(테스트 프론트도 이미지에 복사되므로 자동 반영되지 않는다).

브라우저로 **http://localhost:5173** 을 연다. 프론트가 `/api`를 backend(8080), `/ai-api`를 AI(8000)로 넘긴다.

## 3. 상태 확인

```powershell
docker compose -f compose.dev.yaml ps
docker compose -f compose.dev.yaml logs backend | Select-String "Flyway|Schema|Started BackendApplication|ERROR"
Invoke-RestMethod http://127.0.0.1:8000/health          # status UP, dimension 768, templateVersions [3]
```

backend 로그에 `Schema "app" is up to date`와 `Started BackendApplication`이 보이면 정상이다.

**주의**: backend가 뜰 때 Flyway가 공용 개발 RDS에 아직 적용되지 않은 migration을 자동 적용한다. 새 migration이 든 브랜치를 띄우기 전에는 팀에 먼저 알린다.

## 4. 종료

```powershell
docker compose -f compose.dev.yaml down
```

## 5. RDS 없이 로컬 DB로 (선택)

공용 RDS를 건드리지 않고 Docker PostgreSQL을 쓴다. 빈 DB라 지역·관광지 데이터가 없어 회원가입·온보딩 같은 흐름만 시험할 수 있고, 지역 추첨·코스는 동작하지 않는다.

```powershell
docker compose -f compose.dev.yaml -f compose.dev.local-db.yaml up -d --build
docker compose -f compose.dev.yaml -f compose.dev.local-db.yaml down     # 데이터는 볼륨에 남는다
```

## 자주 겪는 문제

| 증상 | 원인·조치 |
|---|---|
| `open ...\compose.dev.yaml: The system cannot find the file` | `backend/` 폴더에서 실행하지 않았다 |
| backend가 DB 연결 timeout으로 종료 | RDS 보안 그룹에 내 IP가 없다 |
| Flyway validate 실패(RDS가 더 최신) | `git pull`로 backend 최신 main을 받는다. `repair`·`clean`으로 우회하지 않는다 |
| 빌드 중 `read-only file system`·I/O 오류 | C: 드라이브 공간 부족. 공간을 비우고 Docker Desktop을 재시작한다 |
| 지역 추첨이 `DRAW_NO_ELIGIBLE_REGION`(422) | 아래 "개발 RDS 데이터 상태" 참고. 환경 문제가 아니다 |

## E5 전환 (2026-10-17)

추천 가능한 관광지 8,287곳은 E5 768차원/템플릿3으로 변환됐다. 팀원은 backend와 ai의 E5 PR을 모두 반영하고 체크포인트를 준비한 뒤 AI와 backend를 함께 재시작한다. 이전 MiniLM 서버를 이 개발 DB에 계속 연결하면 취향 추천에 사용할 벡터가 없다. AI Hub 응답이 있는 기존 사용자 2명은 변환됐고 구형 MBTI 설문 사용자 3명은 취향 재검사가 필요하다. PR 병합 자체가 실행 중인 서버나 모델 파일을 갱신하지 않는다.

## 개발 RDS 데이터 상태 (2026-10-01, 과거 기록)

지역 250, 관광지 12,164, 이미지 10,933건. 2026-10-01에 이미지 URL을 모두 내려받아 검증했다(HTTP 200, `image/*`, Pillow로 형식·크기 판독, 가로·세로 100px 이상). 10,933건 전부 `VALID`로 바꿨다. 추천 가능 관광지는 3,982곳이다. RELAXED 기준 추첨 가능 지역 수는 1일 242, 2일 204, 3일 77, 4일 47, 5일 41, 6일 29, 7일 23곳이다.

긴 여행(4일 이상)에서 `DRAW_NO_ELIGIBLE_REGION`이 나오면, 설명이 있는 관광지가 모자란 것이다(설명 있음 4,111/12,164). `$tourapi-attraction-backfill`로 설명을 채우면 늘어난다. 새로 들어온 이미지는 `PENDING`으로 시작하므로 [관광지 이미지 검증](attraction-image-validation.md)으로 다시 검증한다.
