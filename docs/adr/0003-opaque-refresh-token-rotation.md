# 0003. Refresh 토큰: 256bit opaque 값 + family 회전/재사용 탐지

- 상태: accepted
- 결정일: 2026-09-21
- 결정자: 인증 모듈 WORK-01 정합화(claude)
- 관련 작업: [회원가입/로그인/인증 모듈](../features/auth-login-signup.md)
- 대체 문서: [0001. Refresh 토큰: 서명된 JWT + 서버측 해시 대조로 rotate/revoke](0001-jwt-refresh-token-rotation.md)

## Context and Problem Statement

[`docs/mvp/implementation-workpack.md`](../mvp/implementation-workpack.md)의 WORK-01은 refresh 토큰을
"256 bit 이상 opaque token"으로, 전달 방식을 `refresh_token` HttpOnly cookie로 명시한다. 기존 구현(PR #8,
ADR-0001)은 refresh도 access와 동일하게 서명된 JWT로 발급하고 body로 반환했다 — 작업서 계약과 두 지점에서
어긋난다.

## Decision Drivers

- WORK-01 계약: "refresh는 256 bit 이상 opaque token, 14일, SHA-256 hash만 저장", "cookie
  `refresh_token; HttpOnly; SameSite=Lax; Path=/api/auth`", "폐기 token 재사용 시 같은 family의 미만료
  token 전부 폐기", "동시 refresh는 정확히 하나만 성공"
- JWT는 서명이 유효한 한 클레임만으로 "아직 유효하다"고 판정되므로, opaque 값보다 얻는 이점이 없다(어차피
  매번 DB 해시 대조를 거치므로 서명 검증은 중복 비용).
- body로 refresh 토큰을 반환하면 클라이언트 JS가 원문에 접근할 수 있어 XSS 시 탈취 표면이 넓어진다.

## Considered Options

### Option 1: refresh도 서명된 JWT로 발급(ADR-0001 유지)

- 장점: access와 발급/파싱 코드 재사용, 서명 검증으로 위조 토큰을 DB 조회 전에 걸러냄
- 단점: WORK-01이 명시한 "opaque token" 계약과 불일치, `exp` 클레임과 DB `expires_at` 이중 관리 필요

### Option 2: 256bit CSPRNG opaque 값 + DB 해시 대조

- 장점: 계약과 정확히 일치, 클레임 위조 우려 자체가 없음, TTL을 DB `expires_at` 하나로만 관리
- 단점: access와 발급 코드가 이원화됨(JwtTokenProvider가 두 종류의 발급 로직을 가짐)

## Decision Outcome

Option 2 채택. `JwtTokenProvider.generateOpaqueRefreshToken()`이 `SecureRandom`으로 32byte(256bit)를
생성해 URL-safe Base64로 인코딩한다. 원문은 응답 body에 담지 않고 `refresh_token` HttpOnly cookie로만
전달하며(`RefreshTokenCookieFactory`), DB에는 SHA-256 해시만 저장한다.

rotate/재사용 탐지를 위해 `refresh_tokens`에 `family_id`(최초 발급부터 이어지는 rotate 체인),
`revoked_at`, `replaced_by_token_id`를 추가했다(`V2__refresh_token_rotation.sql`). refresh 처리 순서:

1. `SELECT ... FOR UPDATE`(`@Lock(PESSIMISTIC_WRITE)`)로 해당 row를 lock한다 — 동시에 같은 토큰으로 들어온
   다른 요청은 이 트랜잭션이 끝날 때까지 대기한다.
2. 이미 `revoked_at`이 있으면(= 이미 rotate된 토큰의 재사용) 탈취로 간주해 같은 `family_id`의 미만료 토큰을
   전부 폐기하고 401을 반환한다.
3. 그렇지 않으면 새 토큰을 같은 family로 발급하고, 기존 row를 `revoked_at`+`replaced_by_token_id`로 폐기한다.

## Consequences

### Positive

- WORK-01 계약과 정확히 일치한다(토큰 형태, 전달 방식, family 회전, 동시성 보장).
- refresh 토큰 원문이 JS에서 접근 불가능한 HttpOnly cookie에만 존재해 XSS 탈취 표면이 줄어든다.
- 탈취된 토큰이 재사용되면 해당 사용자의 전체 세션 family가 무효화되어 최신 정상 세션도 함께 로그아웃되지만,
  그만큼 탈취 피해 범위를 능동적으로 제한한다(ADR-0001의 Follow-up 항목이었던 "탈취 재사용 시 일괄 무효화"를
  구현).

### Negative

- family 폐기가 예외(401)와 함께 일어나므로, 이 bulk 폐기는 `@Transactional(noRollbackFor =
  InvalidRefreshTokenException.class)`로 커밋을 보장해야 한다 — 일반적인 "예외=롤백" 습관과 어긋나 주석 없이는
  놓치기 쉽다.
- refresh_token cookie는 `Path=/api/auth`로 한정했지만 여전히 브라우저가 자동 전송하므로 CSRF 완화를
  `SameSite=Lax`와 "상태 변경 요청은 모두 POST"에 의존한다 — 별도 CSRF 토큰은 이번 범위에 포함하지 않았다.

## Follow-up

- [ ] CSRF 방어를 SameSite=Lax 이상으로 강화할지(예: double-submit 토큰) 검토
- [ ] 만료된/폐기된 `refresh_tokens` row를 주기적으로 정리하는 배치(현재는 무기한 누적, ADR-0001에서 이월)
