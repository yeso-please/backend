# 0001. Refresh 토큰: 서명된 JWT + 서버측 해시 대조로 rotate/revoke

- 상태: superseded by [0003](0003-opaque-refresh-token-rotation.md)
- 결정일: 2026-09-18
- 결정자: 인증 모듈 구현(claude)
- 관련 작업: [회원가입/로그인/인증 모듈](../archive/features/auth-login-signup.md)

> **superseded**: WORK-01 계약(`docs/archive/implementation-workpack.md`)이 refresh 토큰을 256bit 이상
> opaque 값으로 명시해, [ADR-0003](0003-opaque-refresh-token-rotation.md)에서 아래 Option 1 대신
> Option 2(opaque)를 채택했다. 이 문서는 당시의 trade-off 기록으로 남긴다.

## Context and Problem Statement

세션 대신 JWT(access+refresh)를 쓰기로 이미 설계돼 있다(API-DESIGN-DRAFT.md §1). access 토큰은 서명 검증만으로
매 요청을 처리할 수 있어 문제가 없지만, refresh 토큰은 로그아웃·재사용 탐지를 위해 서버가 "이 토큰이 아직
유효한가"를 판단할 수 있어야 한다. 순수 JWT는 만료 전까지 서명만 맞으면 항상 유효하다고 판정되므로, 그 자체로는
폐기(revoke)가 불가능하다.

## Decision Drivers

- FEATURE-SPEC.md §1.5: "로그아웃: 전달받은 refresh 토큰을 revoked=true 처리", "갱신: ... refresh 토큰 자체는
  재사용 시 회전(rotate) — 탈취 시 재사용 탐지 여지를 남김"
- `RefreshToken` 엔티티가 이미 `token_hash`/`expires_at`/`revoked` 컬럼으로 설계돼 있음(API-DESIGN-DRAFT.md §2)
- jjwt 라이브러리가 이미 의존성에 포함(access+refresh 모두 JWT로 발급하는 전제)

## Considered Options

### Option 1: refresh도 서명된 JWT로 발급 + DB에 해시를 저장해 rotate/revoke 판정

- 장점: access와 동일한 발급/검증 코드 경로 재사용, DB 조회 전에 서명·만료를 먼저 걸러낼 수 있어 위조 토큰이
  DB까지 가지 않음, 기존 `RefreshToken` 스키마와 정확히 맞음
- 단점: JWT 자체의 만료 클레임과 DB의 `expires_at`을 별도로 관리해야 함(두 값이 어긋나지 않게 발급 시점에
  동일한 TTL로 계산)

### Option 2: refresh는 순수 랜덤 opaque 토큰(서명 없음), DB 해시 대조만으로 검증

- 장점: 클레임 위조 우려 자체가 없음, 더 단순
- 단점: access와 발급/파싱 코드가 이원화됨, "JWT 발급/검증(access+refresh)"라는 기존 주석·의존성 설계 의도와
  어긋남

## Decision Outcome

Option 1 채택. refresh 토큰도 `type=refresh` 클레임을 가진 JWT로 발급하고, 원문을 SHA-256 해시해
`refresh_tokens.token_hash`에 저장한다. 검증 순서는 (1) JWT 서명·만료·type 확인 → (2) 해시로 DB 조회 →
(3) `revoked`/`expires_at`/소유자(user_id) 일치 확인이다. `POST /api/auth/refresh` 성공 시 기존 row를
`revoked=true`로 바꾸고 새 토큰 쌍을 발급한다(rotate). 이미 rotate된 토큰의 재사용은 401로 거부된다 — 탈취
탐지를 위한 별도 알림/전체 세션 무효화 로직은 이번 범위에 포함하지 않는다(Follow-up 참고).

## Consequences

### Positive

- 로그아웃이 실제로 서버측에서 즉시 반영된다(순수 JWT라면 만료까지 계속 유효했을 것).
- refresh 토큰이 탈취돼 재사용되면 정상 사용자의 다음 refresh 호출이 401로 실패해 이상 징후를 알아챌 수 있는
  최소한의 여지가 생긴다.
- access/refresh 발급·파싱 로직이 `JwtTokenProvider` 하나로 통일된다.

### Negative

- 매 refresh 요청마다 DB 조회 1회가 추가된다(access 토큰 자체 검증만으로 끝나지 않음) — 로그인 유지 목적상
  access보다 훨씬 드물게 호출되므로 트래픽에 미치는 영향은 크지 않다고 판단.
- JWT의 `exp` 클레임과 DB의 `expires_at`이 이중으로 존재해 둘 중 하나만 갱신하는 실수가 나올 수 있다 —
  `AuthService.issueTokens`에서 항상 같은 `jwtProperties.refreshTokenTtlDays` 값으로 함께 계산해 어긋나지
  않게 한다.

## Follow-up

- [ ] 탈취 재사용 탐지 시 해당 사용자의 모든 refresh 토큰을 일괄 무효화하는 정책(현재는 재사용된 토큰 1건만
      거부, 나머지 세션은 영향 없음)
- [ ] 만료된 `refresh_tokens` row를 주기적으로 정리하는 배치(현재는 무기한 누적)
