# backend
백엔드 API 서버

MVP 설계 리뷰와 구현 순서는 [docs/mvp/README.md](docs/mvp/README.md)에서 시작합니다. 현재는 구현 전 제안이며 팀 승인 뒤 기능별 계약으로 확정합니다.

개발 PostgreSQL 생성과 데모 H2 데이터 이관은 [RDS 생성·이관 런북](docs/runbooks/rds-postgresql-bootstrap-and-migration.md)을 따릅니다.

## 로컬 실행

Java 21과 Docker Desktop이 필요합니다.

```powershell
docker compose up -d postgres
.\gradlew.bat bootRun --args='--spring.profiles.active=local'
```

검증은 다음 한 명령으로 실행합니다. Docker가 실행 중이면 PostgreSQL 17 통합 테스트도 함께 실행됩니다.

```powershell
.\gradlew.bat compileJava test --no-daemon
```

다들 안녕 - 장찬욱
