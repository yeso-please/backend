---
name: db-man
description: TriPin 백엔드의 JPA 엔티티, append-only Flyway SQL, 로컬 PostgreSQL과 명시된 개발 RDS의 스키마 변경을 조율한다. 엔티티·컬럼·제약·인덱스·테이블 변경 또는 개발 RDS 반영 요청에 사용하며, 스키마를 바꾸지 않는 조회 쿼리·서비스 로직 작업에는 사용하지 않는다.
---

# db-man — TriPin 스키마 변경 조율

이 저장소의 스키마 원본은 `src/main/resources/db/migration/V*__*.sql`이다. 엔티티는 이에 맞춰야 하고, 개발 RDS는 검증된 migration을 적용한 결과여야 한다. RDS에서 임의로 DDL을 먼저 실행하거나, RDS 구조를 엔티티/SQL의 원본으로 삼지 않는다.

## 이 프로젝트의 실제 경계

- Java 21, Spring Boot 4.1, PostgreSQL 17, Flyway, `spring.jpa.hibernate.ddl-auto=validate`를 사용한다. 로컬 DB는 `compose.yaml`의 PostgreSQL, 테스트/CI는 Testcontainers PostgreSQL이다. 테스트를 공용 RDS에 붙이지 않는다.
- 엔티티는 `com.yeso.backend.{auth,trip,attraction,profile}.domain` 및 필요한 `shared`에 도메인 소유권대로 둔다. 다른 프로젝트의 `common/entity`, `profile/db/*-ddl.sql`, `erd-cloud.sql` 규칙을 가져오지 않는다.
- 현재 개발 RDS의 기대 식별자는 `tripin-dev-postgres`, 리전은 `ap-northeast-2`, 데이터베이스는 `tripin_dev`, 엔진은 PostgreSQL 17이다. 이 값은 **대상 검증 기준**이지 접속 권한이나 자동 적용 승인이 아니다. 실제 endpoint·사용자·TLS·Flyway 이력은 매번 읽기 전용으로 확인한다. endpoint, 계정 ID, 보안 그룹 ID, 비밀번호를 스킬에 고정하지 않는다.
- 개발 RDS는 현재 프리 티어 `db.t4g.micro`/Single-AZ 환경이다. 인스턴스 크기·백업 보존·스토리지·네트워크를 임의 변경하지 않는다. 개발 RDS와 운영 RDS를 혼동하지 않는다.

## 시작할 때

1. 요청이 **설계/조사**, **코드·migration 작성**, **로컬 검증**, **개발 RDS 적용** 중 어디까지인지 구분한다. 코드 변경 요청만으로 RDS 적용이나 PR 생성을 추정하지 않는다.
2. `AGENTS.md`, `docs/mvp/README.md`, `docs/mvp/decisions.md`, 관련 `docs/conventions/`, `docs/features/`, `docs/api/`, `docs/adr/0002-postgresql-flyway-schema-source.md`, `docs/runbooks/rds-postgresql-bootstrap-and-migration.md`에서 작업과 관련된 부분을 확인한다. git 상태와 변경 대상의 현재 엔티티·repository·Flyway SQL도 본다. 사용자 변경은 보존한다.
3. 실제 변경을 수행할 때는 `$flyway-rds-sync`의 append-only migration·검증 규칙을 함께 따른다. 엔티티/스키마 차이는 table/column/type/null/default/FK/unique/index와 데이터 backfill 필요 여부로 정리한다. 영향받는 도메인과 API·배치·테스트를 찾는다.
4. 접속값이 없거나 `psql`이 설치되지 않았다는 이유만으로 **로컬 코드·migration·Testcontainers 작업 전체를 중단하지 않는다**. `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `FLYWAY_USER`, `FLYWAY_PASSWORD`는 개발 RDS 단계에서만 필요하다. 읽기 전용 스키마 점검은 사용 가능한 JDBC, `psql`, Docker의 `psql` 중 적절한 수단을 선택한다. 비밀값은 출력·커밋하지 않는다.

## 구현과 검증

- 기적용 `V1` 또는 다른 공유 migration은 수정·재번호화하지 않는다. 다음 번호의 `V{N}__*.sql`을 추가한다. 새 필수 컬럼, 데이터 backfill, 제약 강화가 필요하면 expand → backfill → contract로 나눈다.
- 요청 범위 밖의 인덱스·제약·삭제를 끼워 넣지 않는다. 잠금·데이터 손실 위험이 있는 rename/drop/type 축소/NOT NULL/unique/대량 UPDATE는 영향 행·복구 가능성을 조사하고 필요한 결정을 사용자에게 요청한다. 없는 데이터를 임의로 채워 제약을 통과시키지 않는다.
- 엔티티/SQL/기능 문서/API 문서가 같은 계약을 나타내도록 갱신한다. 설계가 미결정이면 선택지와 장단점·권장안을 짧게 제시한다. 담당자를 특정 이름으로 가정하지 않는다.
- 빈 PostgreSQL에 전체 Flyway 적용, 기존 버전에서 upgrade, 두 번째 실행 no-op, JPA `validate`, 제약/repository 실패 경로를 로컬/Testcontainers에서 검증한다. Docker 부재 등으로 일부 테스트를 못 했다면 결과를 숨기지 않는다. `flyway clean`, `repair`, `baselineOnMigrate`, `ddl-auto=update`로 실패를 덮지 않는다.

## 개발 RDS 적용은 별도 게이트

사용자가 **이번 작업에서 개발 RDS 반영을 명시적으로 요청**한 경우에만 아래를 진행한다. 그 외에는 로컬 검증 결과와 적용 계획을 보고하고 멈춘다.

1. 접속 설정을 비밀값 없이 검사한다. DB host가 기대한 개발 RDS인지, database가 `tripin_dev`인지, TLS가 `verify-full`인지 확인한다. 운영처럼 보이거나 대상이 불명확하면 적용하지 않는다.
2. `flyway_schema_history`, 현재 변경 대상 테이블·행 수·제약, 자동 백업 또는 이관 전 수동 스냅샷의 **존재와 사용 가능 상태**를 읽기 전용으로 확인한다. 필요한 스냅샷을 확인할 수 없으면 적용하지 않는다.
3. 적용할 migration, 예상 영향·잠금·복구 방법을 제시한다. 공유 RDS를 코드보다 먼저 고치지 않고, 검증된 동일 migration만 Flyway로 적용한다. 수동 `psql -f`는 Flyway 이력 밖의 우회 경로로 사용하지 않는다.
4. 적용 후 Flyway 이력/validate, `ddl-auto=validate` 기동, 핵심 조회·제약, 전후 행 수를 확인한다. 실패 시 history를 수동 수정하거나 무조건적인 역방향 SQL을 실행하지 않는다. 가능한 복구는 보정 migration 또는 검증된 snapshot 복원으로 구분한다.

운영 RDS 반영, 보안 그룹 개방, 스냅샷 삭제, 대량 데이터 수정·삭제, PR 생성/병합은 이 스킬 호출만으로 허가되지 않는다.

## 결과 보고

변경한 엔티티·Flyway 버전·문서, 로컬/Testcontainers 결과, 개발 RDS 적용 여부와 검증 결과를 사실대로 구분한다. PR을 요청받았다면 본문에 변경 이유와 사용법, 스키마 영향, 테스트, 실제 RDS 적용 상태(미적용이면 미적용), 복구 방법을 넣는다. 미실행 항목에 완료 표시를 하지 않는다.
