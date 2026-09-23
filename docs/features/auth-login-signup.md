# 회원가입 / 로그인 / 인증 모듈

- 상태: done
- 담당 범위: auth
- 작성일: 2026-09-18
- 갱신일: 2026-09-21
- 관련 이슈: #11 (WORK-01)
- 관련 API: [Auth API](../api/auth.md)

## 목표

이메일/비밀번호로 회원가입·로그인하고, 발급된 access JWT + opaque refresh 토큰(HttpOnly cookie)으로 이후 요청을
인증할 수 있다. [`docs/mvp/implementation-workpack.md`](../mvp/implementation-workpack.md)의 WORK-01 계약을
따른다.

## 범위

### 포함

- 이메일/비밀번호 회원가입 (`POST /api/auth/signup`) — email trim+lowercase, nickname trim, password는
  8~64자이자 UTF-8 72 byte 이하
- 이메일/비밀번호 로그인 (`POST /api/auth/login`) — 계정 미존재/비밀번호 불일치 동일 401
- access 토큰 재발급 + refresh 토큰 rotate (`POST /api/auth/refresh`) — refresh는 `refresh_token` HttpOnly cookie로만 오간다
- 로그아웃 — refresh 토큰 폐기 (`POST /api/auth/logout`, cookie 유무와 무관하게 항상 204)
- 내 정보 + 온보딩 상태 조회 (`GET /api/users/me`)
- refresh 토큰 family 회전: 폐기된 토큰 재사용 시 같은 family의 미만료 토큰 전부 폐기, row lock으로
  동시 refresh는 정확히 하나만 성공
- JWT 기반 stateless 인증 인프라: `JwtAuthenticationFilter`, `SecurityConfig`,
  `ApiAuthenticationEntryPoint`/`ApiAccessDeniedHandler`, `@CurrentUser`/`@CurrentUserId` 사용자 정보 주입,
  공통 예외 응답(`GlobalExceptionHandler`)

### 제외 (후속)

- 소셜 로그인(카카오/구글 인가코드 교환) — API-DESIGN-DRAFT §1 설계는 되어 있으나 미구현
- 로그인된 상태에서 소셜 계정 추가 연결(MVP 포함 아님)
- 비밀번호 재설정(찾기) — 메일 발송 인프라, 1회용 재설정 토큰 테이블, rate limit 정책이 필요한 별도 작업
- 온보딩(WORK-02) — `/users/me`의 `onboardingCompleted`는 온보딩 모듈이 없어 항상 `false`다
- 온보딩/추천 등 인증이 필요한 다른 도메인 API — 이번 작업은 인증 모듈 자체만 다룸

## 사용자 흐름

1. 사용자가 `POST /api/auth/signup` 또는 `/login`으로 access 토큰(body)과 refresh 토큰(HttpOnly cookie)을
   발급받는다.
2. 이후 요청은 `Authorization: Bearer {accessToken}` 헤더로 인증한다.
3. access 토큰이 만료되면 `POST /api/auth/refresh`를 호출한다 — 브라우저가 `refresh_token` cookie를 자동으로
   함께 보내며, 서버는 새 access 토큰과 rotate된 새 refresh cookie를 내려준다. 이전 refresh 토큰은 즉시
   무효화된다.
4. 로그아웃 시 `POST /api/auth/logout`으로 보유 중인 refresh 토큰을 폐기하고 cookie를 지운다.

## 완료 기준

- [x] 유효한 회원가입 요청이 201과 함께 access 토큰(body) + refresh 토큰(HttpOnly cookie)을 반환한다.
- [x] 이메일은 trim+lowercase 정규화 후 비교하며, 정규화 후 중복이면 409를 반환한다.
- [x] 닉네임은 trim되어 저장된다.
- [x] 형식이 잘못된 요청(이메일 형식, 비밀번호 8~64자 또는 UTF-8 72byte 초과, 닉네임 길이)은 400과
      한국어 필드별 메시지를 반환한다.
- [x] 로그인 실패(이메일 없음/비밀번호 불일치)는 동일한 401 메시지를 반환한다(계정 존재 여부 비노출).
- [x] 인증되지 않은 `/api/users/me` 요청은 401을 반환한다.
- [x] 유효한 access 토큰으로 `/api/users/me`를 호출하면 200과 내 정보 + `onboardingCompleted`를 반환한다.
- [x] refresh cookie로 재발급하면 새 access 토큰 + 새 refresh cookie가 발급되고, 기존 refresh 토큰은 즉시
      폐기(rotate)된다.
- [x] 이미 회전(rotate)되어 폐기된 refresh 토큰이 재사용되면 401을 반환하고 같은 family의 미만료 토큰을
      전부 폐기한다.
- [x] 동시에 같은 refresh 토큰으로 두 요청이 들어오면 정확히 하나만 200을 받는다(row lock).
- [x] 로그아웃한 refresh 토큰으로 재발급을 시도하면 401을 반환하며, logout 응답은 cookie 유무와 무관하게
      항상 204다.
- [x] refresh 토큰 cookie는 `HttpOnly; SameSite=Lax; Path=/api/auth`이며, `app.auth.cookie-secure`
      profile 설정에 따라 Secure가 붙는다(local=false, 그 외=true).
- [x] 성공·실패·동시성 통합 테스트가 있다(`AuthIntegrationTest`), JWT 위조/만료/type 단위 테스트가
      있다(`JwtTokenProviderTest`).
- [x] API 문서가 구현과 일치한다.

## 구현 메모

- Entity: `auth.domain.User`, `auth.domain.RefreshToken`(`familyId`/`revokedAt`/`replacedByTokenId`)
- Repository: `auth.infrastructure.UserRepository`,
  `auth.infrastructure.RefreshTokenRepository`(`findByTokenHashForUpdate`로 PESSIMISTIC_WRITE lock,
  `revokeActiveByFamilyId` bulk 폐기)
- Service: `auth.application.AuthService` — signup/login/refresh/logout/getMe, email/nickname 정규화
- Controller: `auth.presentation.AuthController`(cookie 설정/해제), `auth.presentation.UserController`
- DTO: `SignupRequest`, `LoginRequest`, `AuthResponse`, `UserSummaryResponse`, `UserMeResponse`
- 예외: `auth.domain.AuthException`(베이스) → `DuplicateEmailException`(409), `InvalidCredentialsException`(401),
  `InvalidRefreshTokenException`(401, 토큰 없음/만료/위조/재사용 공통), `UserNotFoundException`(404)
- 인프라: `JwtTokenProvider`(access만 JWT+jti, refresh는 256bit CSPRNG opaque 값 + SHA-256 해시 대조),
  `RefreshTokenCookieFactory`, `AuthCookieProperties`(`app.auth.cookie-secure`),
  `Utf8MaxByteSize`/`Utf8MaxByteSizeValidator`(password byte 길이 검증),
  `JwtAuthenticationFilter`, `SecurityConfig`, `ApiAuthenticationEntryPoint`, `ApiAccessDeniedHandler`
- 마이그레이션: `V2__refresh_token_rotation.sql` — `refresh_tokens`에 `family_id`/`revoked_at`/
  `replaced_by_token_id` 추가, `revoked` boolean 컬럼 제거

## 결정과 미해결 사항

- **refresh 토큰을 opaque로 전환**: PR #8/ADR-0001은 refresh도 서명된 JWT로 발급했으나, WORK-01 계약이 256bit
  이상 opaque 토큰을 요구해 [ADR-0003](../adr/0003-opaque-refresh-token-rotation.md)로 이를 대체했다.
  ADR-0001은 `superseded`로 표시했다.
- **refresh 토큰을 body가 아닌 HttpOnly cookie로 전달**: XSS로 인한 JS 접근을 차단하기 위해서다. 대신 CSRF는
  `SameSite=Lax` + 상태 변경 요청이 모두 POST라는 점으로 완화한다(별도 CSRF 토큰은 이번 범위에 포함하지 않음).
  cookie `Path=/api/auth`로 범위를 최소화했다.
- **동시성 제어**: `SELECT ... FOR UPDATE`(`@Lock(PESSIMISTIC_WRITE)`)로 같은 refresh row에 대한 동시 요청을
  직렬화한다. 재사용 탐지로 인한 family 폐기(bulk update)는 트랜잭션이 예외로 롤백돼도 반드시 커밋되어야 하므로
  `@Transactional(noRollbackFor = InvalidRefreshTokenException.class)`를 사용했고, 같은 트랜잭션 안에서 이미
  로드된 엔티티가 폐기 반영 전 상태로 남지 않도록 `@Modifying(clearAutomatically = true)`를 함께 적용했다.
- **미해결**: 소셜 로그인(카카오/구글)은 아직 미구현. 온보딩(WORK-02)이 구현되기 전까지 `onboardingCompleted`는
  항상 `false`다.

## 변경 이력

| 날짜 | 변경 | 이유 |
|---|---|---|
| 2026-09-18 | 이메일 회원가입/로그인/refresh/logout/me 구현, auth 패키지 구조 전환 | 최초 인증 모듈 구현 |
| 2026-09-21 | opaque refresh + HttpOnly cookie, family 회전/재사용 탐지, row lock, email/nickname 정규화, UTF-8 byte 검증, `V2` migration | WORK-01 계약(이슈 #11) 반영 |
