---
name: flyway-rds-sync
description: Convert TriPin JPA/schema changes into append-only Flyway migrations, verify them on local PostgreSQL and Testcontainers, detect drift, and apply them to an explicitly selected development RDS. Use when entities, constraints, indexes, or database schema change; never auto-apply production changes.
---

# Flyway·RDS 스키마 동기화

Flyway migration이 스키마의 단일 출처다. Hibernate `ddl-auto`는 `validate`만 사용한다. 코드 변경을 감지해 migration을 작성·검증할 수 있지만 RDS 적용 대상과 시점은 자동 추정하지 않는다.

## 사전 점검

1. `docs/mvp/decisions.md`, `docs/conventions/`, 현재 entity, repository query, 기존 `db/migration`을 읽는다.
2. git 상태를 확인하고 사용자 변경을 보존한다. 이미 공유 DB에 적용된 migration은 수정·재번호화하지 않는다.
3. entity 변경과 실제 migration 차이를 표로 만든다: table/column/type/null/default/FK/unique/index/data backfill.
4. 다음 Flyway version을 기존 파일에서 계산한다. 한 migration에는 하나의 논리적 변경을 담는다.

## migration 작성

- expand/backfill/contract가 필요한 변경은 여러 migration으로 분리한다. 새 필수 컬럼은 nullable 추가→데이터 채움→NOT NULL 순서로 처리한다.
- rename/drop/type 축소, 대량 table rewrite, unique 추가는 영향 행과 잠금 시간을 먼저 측정한다. 데이터 손실 가능 작업은 명시 승인 없이는 작성까지만 하고 적용하지 않는다.
- PostgreSQL 문법과 대상 RDS 엔진 버전에서 지원되는 extension만 사용한다. pgvector는 버전 확인과 성능 측정 뒤 별도 migration으로 둔다.
- rollback SQL을 무조건 약속하지 않는다. 복구 방법은 역 migration 또는 snapshot restore 중 실제 가능한 방식을 PR에 기록한다.
- 애플리케이션 기동 시 production schema를 자동 수정하는 설정을 추가하지 않는다.

## 검증 순서

1. 빈 로컬 PostgreSQL에 `flyway migrate` 후 애플리케이션 `ddl-auto=validate` 기동.
2. 이전 migration까지만 적용한 DB에 새 migration을 올리는 upgrade 테스트.
3. Testcontainers 통합 테스트와 repository/constraint 실패 테스트.
4. `flyway validate`와 `flyway info` 확인, schema drift 검사.
5. migration을 두 번 실행해 두 번째가 no-op인지 확인한다.

## 개발 RDS 적용

- 사용자가 명시한 `dev` RDS에만 적용한다. host/database/user가 예상 대상인지 비밀값 없이 표시한다.
- 자동 백업 상태 또는 수동 snapshot과 복구 가능성을 확인한다. snapshot 진행 중이거나 대상이 불명확하면 중단한다.
- 현재 `flyway info`, pending migration, 예상 DDL·잠금·데이터 영향 요약을 보여준 뒤 적용한다.
- 적용 후 `flyway validate`, 애플리케이션 health, 핵심 query, 행 수/제약을 검증하고 결과를 기록한다.
- 실패하면 임의로 schema history를 고치거나 repair하지 않는다. 원인과 복구 선택지를 보고한다.

운영 RDS에는 이 스킬이 자동 적용하지 않는다. 명시된 운영 배포 작업, 백업/복구 계획, 변경 승인 없이는 local/dev 검증에서 멈춘다.
