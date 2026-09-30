# TriPin 테스트용 프론트엔드

이 디렉터리는 현재 Spring backend와 AI API를 실제로 호출해 기능을 점검하는 **테스트 전용 프론트엔드**입니다. 데모의 전국 지도와 여행 흐름을 참고하지만 API 동작은 `../docs/api/`와 backend 응답에 맞춥니다. 제품용 프론트엔드가 아니며 운영 서비스 배포에 사용하지 마세요.

## 로컬 실행

Node.js 22.12 이상을 사용합니다.

```powershell
cd backend/test_frontend
npm ci
Copy-Item .env.example .env.local
npm run dev
```

브라우저에서 `http://127.0.0.1:5173`을 엽니다. 기본 설정은 같은 컴퓨터의 backend `http://127.0.0.1:8080`과 AI `http://127.0.0.1:8000`으로 프록시합니다. 백엔드 서버가 없거나 로그인이 필요한 기능은 실제 오류를 표시하며 mock 성공 응답을 만들지 않습니다.

백엔드 프로세스의 데이터베이스를 개발 RDS로 지정하려면 로컬 비밀 환경에서 `SPRING_PROFILES_ACTIVE=dev-rds`, `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `FLYWAY_USER`, `FLYWAY_PASSWORD`, `JWT_SECRET`을 설정한 뒤 backend를 실행합니다. `DB_URL`은 RDS PostgreSQL endpoint, `tripin_dev`, `currentSchema=app`, TLS `verify-full`과 로컬 RDS CA bundle 경로를 가리켜야 합니다. 앱/마이그레이션 계정 비밀번호는 이 프론트 설정이나 Git에 기록하지 마세요. 개발 RDS의 네트워크 보안 그룹이 backend 실행 컴퓨터의 IP에서 연결을 허용해야 합니다.

다른 컴퓨터의 브라우저에서 같은 LAN의 개발 프론트를 열려면 `.env.local`의 `VITE_HOST`를 `0.0.0.0`으로 바꾸고 프론트와 backend를 실행하는 컴퓨터의 5173/8080 방화벽 및 네트워크 접근을 허용합니다. 이때도 DB 연결은 프론트가 아닌 backend가 담당하며, backend의 `DB_URL`이 RDS를 가리켜야 합니다.

## 팀원별 backend와 RDS 실행

각 팀원이 자신의 컴퓨터에서 프론트와 Spring backend를 실행합니다. 프론트는 같은 컴퓨터의 `127.0.0.1:8080` backend로 요청하고, backend만 개발 RDS에 연결합니다. 다른 컴퓨터의 브라우저에서 한 사람의 localhost backend에 접근하지 않습니다. 팀원마다 둘 다 실행하면 모두 같은 개발 RDS의 계정·여행 데이터를 사용합니다.

1. 팀 저장소의 RDS 접속 안내를 따라 로컬 `config/application-secret.yaml` 또는 현재 PowerShell 세션의 환경변수에 DB 주소·계정·비밀값과 `JWT_SECRET`을 설정합니다. 비밀값은 Git에 커밋하지 않습니다.
2. `backend/docs/runbooks/rds-postgresql-bootstrap-and-migration.md`의 `dev-rds` 명령으로 Spring backend를 실행합니다. 현재 읽기 전용 확인에서 RDS Flyway 이력은 V9였고 backend에는 V10–V17이 있습니다. 첫 backend 실행 때 Flyway가 pending migration을 적용하므로 공용 개발 스키마가 업그레이드됩니다. 이 migration은 기존 지역·관광지 행을 삭제하지 않으며 새 지역 카드 필드, 사용자 설정, 식당·관광지 상세, 설문 필드, 여행기 테이블을 추가합니다.
3. 이 폴더의 `.env.local`에서 `BACKEND_URL=http://127.0.0.1:8080`을 확인하고 `npm run dev`를 실행합니다.
4. `http://127.0.0.1:5173`에서 테스트합니다. 같은 RDS를 공유하므로 계정 이름과 테스트 여행 데이터는 팀 공용 데이터로 취급합니다.

RDS endpoint는 저장소에 기본값으로 넣지 않았습니다. `DB_URL`은 PostgreSQL endpoint를 가리키고, `BACKEND_URL`은 backend HTTP origin을 가리킵니다. RDS TLS 인증서, 네트워크 허용, 비밀 설정은 위 runbook을 따릅니다.

## 확인

```powershell
npm test
npm run build
```

브라우저 계약 테스트(`npm run test:browser`)는 fixture 응답을 사용하고 실제 RDS 연결을 검증하지 않습니다. 실제 연동은 RDS profile로 실행한 backend를 통해 API 기록 보기에서 요청·응답을 확인하세요.

API 계약 스냅샷을 backend 명세와 다시 맞추려면 저장소 루트에서 다음 명령을 실행합니다.

```powershell
cd backend/test_frontend
node scripts/sync-contract.mjs
```
