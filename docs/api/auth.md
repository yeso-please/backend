# 1. 인증

- 계약 상태: agreed
- 모듈: `auth`
- 관련 기능 명세: [회원가입/로그인/인증 모듈](../features/auth-login-signup.md)
- 범위: 이메일·비밀번호 로컬 로그인만. 소셜 로그인은 MVP 범위 밖이다.

## 공통

refresh token은 응답 body에 담지 않는다. 가입·로그인·refresh 성공 시 서버가 `Set-Cookie`로 내려준다.

```text
Set-Cookie: refresh_token=...; HttpOnly; SameSite=Lax; Path=/api/auth; Max-Age=1209600[; Secure]
```

`Secure`는 `app.auth.cookie-secure` 설정을 따른다. local(HTTP)은 `false`, 그 외 HTTPS 환경은 `true`다.

**`AuthResponse`** — 가입·로그인·refresh의 공통 성공 응답

```json
{
  "user": {"id": 1, "email": "user@example.com", "nickname": "tester", "profileImage": null},
  "accessToken": "eyJhbGciOi...",
  "tokenType": "Bearer",
  "expiresInSeconds": 1800,
  "onboardingCompleted": false
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `user.profileImage` | `string?` | MVP에서는 항상 `null` |
| `expiresInSeconds` | `number` | access token 수명(초) |
| `onboardingCompleted` | `boolean` | 최신 완료 온보딩 제출이 있으면 `true`. 프론트는 이 값으로 첫 화면(온보딩/메인)을 정한다 |

**오류 코드**

| code | HTTP | 상황 |
|---|---:|---|
| `AUTH_DUPLICATE_EMAIL` | 409 | 정규화 후 이미 사용 중인 이메일 |
| `AUTH_INVALID_CREDENTIALS` | 401 | 이메일 없음 또는 비밀번호 불일치(구분하지 않음) |
| `AUTH_INVALID_REFRESH_TOKEN` | 401 | refresh cookie 없음·위조·만료·폐기·재사용 |
| `AUTH_UNAUTHENTICATED` | 401 | access token 없음·무효 |
| `AUTH_USER_NOT_FOUND` | 404 | 토큰의 사용자가 삭제됨 |

---

### 1-1. 회원가입

> `WORK-01` · `호출: 공개` · `✅ 구현`

```
POST /api/auth/signup
```

**Request Body**

```json
{"email": "user@example.com", "password": "password123", "nickname": "tester"}
```

| 필드 | 타입 | 필수 | 제약 |
|---|---|---|---|
| `email` | `string` | 예 | trim+lowercase 정규화, 이메일 형식, 최대 190자, 정규화 후 중복 불가 |
| `password` | `string` | 예 | 8~64자이며 UTF-8 72byte 이하. BCrypt 해시로만 저장 |
| `nickname` | `string` | 예 | trim 후 1~30자 |

**Response `201 Created`** — `AuthResponse` + refresh cookie

| 오류 | HTTP | code |
|---|---:|---|
| 형식 오류(필드별 한글 메시지) | 400 | `COMMON_INVALID_REQUEST` |
| 이메일 중복 | 409 | `AUTH_DUPLICATE_EMAIL` |

**Side effects** — `users` 1건, 새 family의 `refresh_tokens` 해시 1건 생성.

---

### 1-2. 로그인

> `WORK-01` · `호출: 공개` · `✅ 구현`

```
POST /api/auth/login
```

**Request Body**

```json
{"email": "user@example.com", "password": "password123"}
```

`email`은 가입과 같은 방식으로 정규화해 비교한다.

**Response `200 OK`** — `AuthResponse` + refresh cookie

| 오류 | HTTP | code |
|---|---:|---|
| 이메일 없음·비밀번호 불일치 | 401 | `AUTH_INVALID_CREDENTIALS` |

두 경우 모두 같은 메시지 "이메일 또는 비밀번호가 올바르지 않습니다."를 반환해 계정 존재 여부를 숨긴다.

**Side effects** — 새 family의 `refresh_tokens` 해시 1건 생성.

---

### 1-3. 토큰 갱신

> `WORK-01` · `호출: refresh cookie` · `✅ 구현`

```
POST /api/auth/refresh
```

요청 body 없음. 브라우저가 `refresh_token` cookie를 자동으로 보낸다.

**Response `200 OK`** — 새 `AuthResponse` + 회전된 refresh cookie. 이전 refresh token은 즉시 폐기된다.

| 오류 | HTTP | code |
|---|---:|---|
| cookie 없음·위조·만료·폐기 | 401 | `AUTH_INVALID_REFRESH_TOKEN` |

- **재사용 탐지**: 이미 회전으로 폐기된 token이 다시 오면 탈취로 보고 같은 family의 미만료 token을 모두 폐기한다. 방금 발급된 최신 token도 무효가 되어 재로그인해야 한다.
- **동시 요청**: 같은 token의 동시 요청은 row lock으로 직렬화된다. 정확히 하나만 200이고, 나머지는 재사용으로 판정되어 401과 family 폐기가 적용된다.
- **멱등성**: 없다. 같은 token의 두 번째 호출은 401이다.
- 프론트는 access 만료로 401을 받으면 이 API를 한 번 호출하고 원 요청을 재시도한다. 이 API도 401이면 로그인 화면으로 보낸다.

**Side effects** — 기존 row에 `revoked_at`·`replaced_by_token_id` 기록, 같은 family로 새 row 1건 생성.

---

### 1-4. 로그아웃

> `WORK-01` · `호출: 공개` · `✅ 구현`

```
POST /api/auth/logout
```

요청 body 없음. cookie가 없어도 된다.

**Response `204 No Content`** — cookie 유무·폐기 여부와 관계없이 항상 204. `Set-Cookie: refresh_token=; Max-Age=0; Path=/api/auth`로 cookie를 지운다.

---

### 1-5. 내 정보

> `WORK-01` · `호출: 회원` · `✅ 구현`

```
GET /api/users/me
```

**Response `200 OK`**

```json
{"id": 1, "email": "user@example.com", "nickname": "tester", "profileImage": null, "onboardingCompleted": true}
```

| 오류 | HTTP | code |
|---|---:|---|
| 토큰 없음·무효 | 401 | `AUTH_UNAUTHENTICATED` |
| 토큰의 사용자가 없음 | 404 | `AUTH_USER_NOT_FOUND` |
