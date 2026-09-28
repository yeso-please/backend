# ADR(Architecture Decision Record)

구조·보안·데이터 저장소·API 호환성처럼 여러 기능에 영향을 주거나 되돌리기 어려운 결정은 ADR로 남깁니다. ADR은 정답을 선언하는 문서가 아니라, 당시의 선택과 trade-off를 보존하는 문서입니다.

## 위치와 상태

```text
docs/adr/0004-short-title.md
```

상태는 `proposed`, `accepted`, `rejected`, `deprecated`, `superseded` 중 하나를 사용합니다.

## 템플릿

````markdown
# 0001. 로컬 개발 데이터베이스로 SQLite 사용

- 상태: accepted
- 결정일: 2026-09-17
- 결정자:
- 관련 이슈: #

## Context and Problem Statement

어떤 문제와 제약이 있었는가?

## Decision Drivers

- 로컬 설치 비용
- 팀 개발 환경 재현성
- 운영 데이터베이스와의 차이

## Considered Options

### Option 1: SQLite

- 장점:
- 단점:

### Option 2: PostgreSQL

- 장점:
- 단점:

## Decision Outcome

어떤 선택을 했고, 왜 선택했는가?

## Consequences

### Positive

-

### Negative

-

## Follow-up

- [ ] 운영 데이터베이스 확정
````

새 결정이 기존 ADR을 대체하면 기존 파일을 삭제하지 않습니다. 기존 ADR의 상태를 `superseded`로 바꾸고 새 ADR에서 이전 ADR을 링크합니다.

## 결정 목록

- [0001. Refresh 토큰: 서명된 JWT + 서버측 해시 대조로 rotate/revoke](0001-jwt-refresh-token-rotation.md) — superseded by 0003
- [0002. PostgreSQL과 Flyway를 스키마의 단일 출처로 사용한다](0002-postgresql-flyway-schema-source.md) — accepted
- [0003. Refresh 토큰: 256bit opaque 값 + family 회전/재사용 탐지](0003-opaque-refresh-token-rotation.md) — accepted
