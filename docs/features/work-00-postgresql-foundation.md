# WORK-00 PostgreSQL 기반

- 상태: implemented
- 담당 범위: PostgreSQL, Flyway V1, Testcontainers, CI, 공통 오류, 인증 사용자 주입
- 마지막 갱신일: 2026-09-20
- 관련: [MVP 구현 작업서](../mvp/implementation-workpack.md), [ADR-0002](../adr/0002-postgresql-flyway-schema-source.md), [RDS 런북](../runbooks/rds-postgresql-bootstrap-and-migration.md)

## 목표

SQLite와 Hibernate 자동 DDL을 제거하고 모든 개발·테스트·배포 환경이 같은 PostgreSQL/Flyway 스키마를 사용한다. 후속 기능은 V1을 수정하지 않고 새 version migration으로 확장한다.

## 구현 범위

- PostgreSQL 17 JDBC와 Flyway PostgreSQL 모듈
- `compose.yaml`의 localhost 전용 PostgreSQL 17
- `application-local.yml`, `application-dev-rds.yml` 환경 분리
- `ddl-auto=validate`, Flyway `app` schema, clean 비활성화
- MVP 핵심 전체 테이블을 만드는 `V1__baseline.sql`
- PostgreSQL 17 Testcontainers migration/JPA/제약 통합 테스트
- Java 21 GitHub Actions CI
- 공통 JSON 오류 응답과 예상하지 못한 내부 메시지 비노출
- `@CurrentUserId Long userId` argument resolver 계약

## 제외 범위

- 데모 H2 데이터 이관 실행기와 RDS 적재: WORK-09
- JWT 발급·검증 필터와 경로 보호: WORK-01
- pgvector extension/index: 후보 규모 측정 후 별도 migration
- 개발/운영 RDS 직접 변경

## 로컬 실행

Docker Desktop을 먼저 실행한다.

```powershell
docker compose up -d postgres
.\gradlew.bat bootRun --args='--spring.profiles.active=local'
```

종료할 때 컨테이너만 정지하면 데이터 volume은 유지된다.

```powershell
docker compose stop postgres
```

데이터를 제거하는 `docker compose down -v`는 파괴적이므로 로컬 DB 초기화 의도가 명확할 때만 사용한다.

## 검증

```powershell
.\gradlew.bat compileJava test --no-daemon
docker compose config
```

`test`는 Docker가 있으면 PostgreSQL 17 컨테이너에서 다음을 검증한다.

- 빈 DB에 V1 적용
- Spring context와 Hibernate schema validation
- 두 번째 Flyway migrate가 no-op
- `(source_system, source_content_id)` 중복 제약

Docker를 사용할 수 없는 로컬 PC에서는 통합 테스트만 skip되고 단위 테스트는 실행된다. GitHub Actions runner에서는 Docker 기반 통합 테스트가 필수로 실행된다.

## 완료 조건

- [x] SQLite runtime과 dialect 제거
- [x] Flyway가 schema의 단일 출처
- [x] JPA 자동 DDL 금지
- [x] 로컬 PostgreSQL 재현 구성
- [x] PostgreSQL/Testcontainers 검증 코드
- [x] CI 구성
- [x] 공통 오류와 사용자 ID 주입 계약
- [ ] GitHub Actions의 실제 Docker 통합 테스트 성공 확인

마지막 항목은 브랜치를 push한 후 CI 결과로 닫는다.
