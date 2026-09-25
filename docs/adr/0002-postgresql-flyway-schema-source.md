# 0002. PostgreSQL과 Flyway를 스키마의 단일 출처로 사용한다

- 상태: accepted
- 결정일: 2026-09-20
- 관련: [WORK-00](../archive/features/work-00-postgresql-foundation.md)

## Context

초기 저장소는 SQLite와 `ddl-auto=update`를 사용했다. 데모는 별도의 H2 파일을 사용하며 MVP는 RDS PostgreSQL로 배포된다. DB별 자동 DDL 결과가 달라지면 로컬, CI, RDS 스키마가 조용히 갈라지고 데이터 이관의 재현성이 사라진다.

## Decision

- 모든 신규 환경은 PostgreSQL 17을 사용한다.
- append-only Flyway SQL이 스키마의 단일 출처다.
- Hibernate는 모든 환경에서 `ddl-auto=validate`만 사용한다.
- local은 Docker Compose, test/CI는 Testcontainers PostgreSQL을 사용한다.
- RDS에는 local/Testcontainers에서 검증된 같은 migration만 적용한다.
- pgvector는 초기 V1에서 제외하고 검색 규모와 extension version을 측정한 뒤 별도 migration으로 도입한다.

## Consequences

- 엔티티 변경은 반드시 새 Flyway migration과 함께 리뷰한다.
- 이미 공유 DB에 적용된 migration은 수정하거나 재번호화하지 않는다.
- SQLite/H2 raw dump를 RDS에 직접 적용할 수 없으며 WORK-09의 명시적 변환기가 필요하다.
- Docker가 없는 개발 PC에서는 통합 테스트가 skip될 수 있지만 CI에서는 반드시 실행된다.
- production schema를 애플리케이션이 임의 변경하지 않는다.

### 보완: 데이터를 지우는 migration (2026-09-25)

`DELETE`·`DROP`처럼 데이터를 지우는 migration은 Flyway로 되돌릴 수 없다. 예: `V6__member_only_trips.sql`(비회원 참여 데이터 삭제).

- 파일 맨 위 주석에 무엇을 지우는지와 이유를 적는다.
- 공유 DB(개발 RDS)에 적용하기 전에 스냅샷을 뜨고, 적용 후 행 수·`flyway validate`를 확인한다.
- 운영 DB에는 자동 적용하지 않는다.

## Alternatives considered

### Hibernate `ddl-auto=update`

빠르지만 변경 이력, 리뷰 가능한 SQL, 재현 가능한 upgrade 경로가 없어 제외했다.

### RDS 스키마를 수동으로 먼저 생성

콘솔/클라이언트에서 수행한 변경이 저장소에 남지 않아 환경 drift를 만들므로 제외했다.

### 초기부터 pgvector 사용

MVP 후보 규모가 아직 작고 Python은 임베딩만 담당하기로 했으므로, extension 의존성과 인덱스 튜닝을 먼저 도입하지 않는다.
