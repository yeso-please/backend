# 회원가입 / 로그인 / 인증 모듈

- 상태: done
- 담당 범위: auth
- 작성일: 2026-09-18
- 갱신일: 2026-09-18
- 관련 이슈: -
- 관련 API: [Auth API](../api/auth.md)

## 목표

이메일/비밀번호로 회원가입·로그인하고, 발급된 JWT(access+refresh)로 이후 요청을 인증할 수 있다.

## 범위

### 포함

- 이메일/비밀번호 회원가입 (`POST /api/auth/signup`)
- 이메일/비밀번호 로그인 (`POST /api/auth/login`)
- refresh 토큰으로 access 토큰 재발급, refresh 토큰 rotate (`POST /api/auth/refresh`)
- 로그아웃 — refresh 토큰 폐기 (`POST /api/auth/logout`)
- 내 정보 조회 (`GET /api/users/me`)
- JWT 기반 stateless 인증 인프라: `JwtAuthenticationFilter`, `SecurityConfig`,
  `AuthenticationEntryPoint`/`AccessDeniedHandler`, `@CurrentUser` 사용자 정보 주입,
  공통 예외 응답(`GlobalExceptionHandler`)

### 제외 (후속)

- 소셜 로그인(카카오/구글 인가코드 교환) — `POST /api/auth/social/{provider}` (API-DESIGN-DRAFT §1 설계는 되어 있으나 미구현)
- 로그인된 상태에서 소셜 계정 추가 연결(API-DESIGN-DRAFT §1.4, MVP 포함 아님이 이미 문서에 명시됨)
- 비밀번호 재설정(찾기) — API-DESIGN-DRAFT.md/FEATURE-SPEC.md 어디에도 설계돼 있지 않음. 메일 발송 인프라,
  1회용 재설정 토큰 테이블, rate limit 정책을 새로 설계해야 하는 별도 작업(작업 명세 미작성)
- 온보딩/추천 등 인증이 필요한 다른 도메인 API — 이번 작업은 인증 모듈 자체만 다룸

## 사용자 흐름

1. 사용자가 `POST /api/auth/signup` 또는 `/login`으로 access+refresh 토큰을 발급받는다.
2. 이후 요청은 `Authorization: Bearer {accessToken}` 헤더로 인증한다.
3. access 토큰이 만료되면 `POST /api/auth/refresh`로 재발급받는다 — 이때 refresh 토큰도 함께 rotate되어
   응답의 새 refresh 토큰으로 교체해야 하고, 이전 값은 즉시 무효화된다.
4. 로그아웃 시 `POST /api/auth/logout`으로 보유 중인 refresh 토큰을 폐기한다.

## 완료 기준

- [x] 유효한 회원가입 요청이 201과 함께 access+refresh 토큰을 반환한다.
- [x] 이메일이 중복되면 409를 반환한다.
- [x] 형식이 잘못된 요청(이메일 형식, 비밀번호 8자 미만, 닉네임 길이)은 400과 한국어 필드별 메시지를 반환한다.
- [x] 로그인 실패(이메일 없음/비밀번호 불일치)는 동일한 401 메시지를 반환한다(계정 존재 여부 비노출).
- [x] 인증되지 않은 `/api/users/me` 요청은 401을 반환한다.
- [x] 유효한 access 토큰으로 `/api/users/me`를 호출하면 200과 내 정보를 반환한다.
- [x] refresh 토큰으로 재발급하면 새 access+refresh 토큰이 발급되고, 기존 refresh 토큰은 즉시 폐기(rotate)된다.
- [x] 이미 회전되었거나 폐기된 refresh 토큰 재사용은 401을 반환한다.
- [x] 로그아웃한 refresh 토큰으로 재발급을 시도하면 401을 반환한다.
- [x] 성공·실패 통합 테스트가 있다(`AuthIntegrationTest`).
- [x] API 문서가 구현과 일치한다.

## 구현 메모

- Entity: `auth.domain.User`, `auth.domain.RefreshToken` (기존 `SocialAccount`/`SocialProvider`는 이번 작업 범위 밖이라
  이동만 하고 로직은 추가하지 않음)
- Repository: `auth.infrastructure.UserRepository`, `auth.infrastructure.RefreshTokenRepository`
- Service: `auth.application.AuthService`
- Controller: `auth.presentation.AuthController`, `auth.presentation.UserController`
- DTO: `SignupRequest`, `LoginRequest`, `RefreshRequest`, `LogoutRequest`, `TokenResponse`, `UserResponse`
- 예외: `auth.domain.AuthException`(베이스) → `DuplicateEmailException`(409), `InvalidCredentialsException`(401),
  `InvalidRefreshTokenException`(401), `UserNotFoundException`(404)
- 인프라: `JwtTokenProvider`(access/refresh 모두 JWT로 발급, refresh는 SHA-256 해시로 DB 대조),
  `JwtAuthenticationFilter`, `SecurityConfig`, `JwtAuthenticationEntryPoint`, `JwtAccessDeniedHandler`
- 마이그레이션: 없음(`ddl-auto: update`로 기존 스키마 그대로 사용, 테이블은 이미 API-DESIGN-DRAFT §2 설계대로 존재)

## 결정과 미해결 사항

- **패키지 구조 전환**: 이번 작업에서 `User`/`RefreshToken`/`SocialAccount`/`SocialProvider`/`JwtProperties`를
  평면 `domain`/`config` 패키지에서 `auth/domain`, `auth/infrastructure`로 옮겼다
  (docs/conventions/모듈-의존성.md의 "기존 클래스는 수정할 때 점진적으로 이동" 원칙 적용). `TripPlan` 등 다른
  엔티티는 `User`를 참조하므로 import만 추가하고 패키지는 그대로 두었다 — 각 도메인이 실제로 만들어질 때
  점진적으로 이동한다.
- **refresh 토큰도 JWT로 발급**: access와 동일하게 서명된 JWT로 만들되, `refresh_tokens.token_hash`에 해시를
  저장해 rotate/revoke를 서버 상태로 제어한다(서명 검증만으로는 폐기가 불가능한 JWT의 한계를 보완).
- **미해결**: 소셜 로그인(카카오/구글)은 아직 미구현 — API-DESIGN-DRAFT.md §9의 "카카오 외 구글도 필수인지" 결정과
  함께 별도 작업 명세로 진행 필요.

## 변경 이력

| 날짜 | 변경 | 이유 |
|---|---|---|
| 2026-09-18 | 이메일 회원가입/로그인/refresh/logout/me 구현, auth 패키지 구조 전환 | 최초 인증 모듈 구현 |
