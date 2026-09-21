# 인증 API

- 상태: implemented (이메일 회원가입/로그인만 — 소셜 로그인은 미구현)
- 갱신일: 2026-09-21
- 관련 기능 명세: [회원가입/로그인/인증 모듈](../features/auth-login-signup.md)

refresh 토큰은 응답 body에 담기지 않는다. `POST /api/auth/signup`, `/login`, `/refresh` 성공 시 서버가
`refresh_token` HttpOnly cookie를 `Set-Cookie`로 내려주고, 클라이언트는 이후 `/api/auth/refresh`,
`/api/auth/logout` 호출 시 브라우저가 자동으로 동봉하는 이 cookie만 있으면 된다.

cookie 속성: `HttpOnly; SameSite=Lax; Path=/api/auth`, `Max-Age`는 refresh TTL(14일)과 동일하다. `Secure`는
`app.auth.cookie-secure` profile 설정을 따른다 — local profile은 HTTP라 `false`, 그 외(운영/dev-rds 등
HTTPS 환경)는 `true`다.

## POST /api/auth/signup

- 목적: 이메일/비밀번호로 신규 계정을 생성하고 즉시 로그인 처리한다.
- 인증: 불필요

### Request

Headers:

| 이름 | 값 | 필수 |
|---|---|---|
| Content-Type | application/json | O |

Body:

```json
{
  "email": "user@example.com",
  "password": "password123",
  "nickname": "tester"
}
```

| 필드 | 타입 | 필수 | 제약 |
|---|---|---|---|
| email | string | O | trim+lowercase 정규화, 이메일 형식, 최대 190자, 정규화 후 중복 불가 |
| password | string | O | 8~64자이며 UTF-8 기준 72byte 이하 (BCrypt 해시로만 저장) |
| nickname | string | O | trim 후 1~30자 |

### Responses

##### 201 Created

`Set-Cookie: refresh_token=...; HttpOnly; SameSite=Lax; Path=/api/auth; Max-Age=1209600[; Secure]`

```json
{
  "user": {
    "id": 1,
    "email": "user@example.com",
    "nickname": "tester",
    "profileImage": null
  },
  "accessToken": "...",
  "tokenType": "Bearer",
  "expiresInSeconds": 1800,
  "onboardingCompleted": false
}
```

##### 400 Bad Request

형식이 올바르지 않다(이메일 형식, 비밀번호 길이/byte 초과, 닉네임 길이 등). 필드별 메시지를 한글로 반환한다.

##### 409 Conflict

정규화(trim+lowercase) 후 이미 사용 중인 이메일이다.

#### Side effects

- `users`에 1건을 생성한다.
- `refresh_tokens`에 새 family로 발급된 refresh 토큰의 해시를 1건 저장한다.

#### Related

- [작업 명세](../features/auth-login-signup.md)
- [API 응답 형식](../conventions/API-응답-형식.md)

---

## POST /api/auth/login

- 목적: 이메일/비밀번호로 로그인해 access 토큰(body) + refresh 토큰(cookie)을 발급한다.
- 인증: 불필요

### Request

Body:

```json
{
  "email": "user@example.com",
  "password": "password123"
}
```

email은 signup과 동일하게 trim+lowercase로 정규화한 뒤 비교한다.

### Responses

##### 200 OK

`POST /api/auth/signup`의 201 응답과 동일한 `AuthResponse` 형식 + `Set-Cookie`.

##### 401 Unauthorized

```json
{
  "status": 401,
  "code": "AUTH_INVALID_CREDENTIALS",
  "message": "이메일 또는 비밀번호가 올바르지 않습니다."
}
```

이메일이 존재하지 않는 경우와 비밀번호가 틀린 경우 **동일한 메시지**를 반환한다(계정 존재 여부 비노출).

#### Side effects

- `refresh_tokens`에 새 family로 발급된 refresh 토큰의 해시를 1건 저장한다.

---

## POST /api/auth/refresh

- 목적: refresh 토큰으로 access 토큰을 재발급한다. refresh 토큰도 함께 rotate된다.
- 인증: 불필요(`refresh_token` cookie 자체가 자격 증명)

### Request

Headers/Cookie: `Cookie: refresh_token=...` (브라우저가 자동 동봉)

Body: 없음

### Responses

##### 200 OK

새로 발급된 `AuthResponse` + rotate된 `Set-Cookie: refresh_token=...`(새 값). 이전 값은 응답과 동시에
서버에서 폐기된다.

##### 401 Unauthorized

```json
{
  "status": 401,
  "code": "AUTH_INVALID_REFRESH_TOKEN",
  "message": "인증이 만료되었습니다. 다시 로그인해주세요."
}
```

다음 중 하나라도 해당하면 401이다: cookie가 없음, 토큰이 DB에 없음(형식 오류/위조), 만료, 이미 폐기됨.

**재사용 탐지**: 이미 rotate로 폐기된 토큰이 다시 제시되면 탈취로 간주해 같은 family(최초 로그인/가입부터
이어진 rotate 체인)의 미만료 토큰을 전부 폐기한다 — 방금 발급된 최신 토큰도 함께 무효화되어 사용자는
재로그인해야 한다.

**동시 요청**: 같은 refresh 토큰으로 동시에 여러 요청이 들어오면 DB row lock으로 직렬화되어 정확히 하나만
200을 받는다. 나머지는 rotate된 토큰을 재사용한 것으로 판정되어 401 + family 폐기가 적용된다.

#### Side effects

- 기존 refresh 토큰 row를 `revoked_at`으로 폐기하고 `replaced_by_token_id`로 새 row를 가리킨다.
- 새 refresh 토큰의 해시를 같은 family로 `refresh_tokens`에 1건 저장한다.
- 재사용 탐지 시: 같은 `family_id`의 미만료 row를 전부 폐기한다.

#### 멱등성

멱등하지 않다 — 같은 refresh 토큰으로 두 번째 호출하면 첫 번째 호출에서 이미 rotate되어 401 + family
폐기를 반환한다.

---

## POST /api/auth/logout

- 목적: 보유 중인 refresh 토큰을 폐기하고 cookie를 지운다.
- 인증: 불필요

### Request

Headers/Cookie: `Cookie: refresh_token=...` (없어도 된다)

Body: 없음

### Responses

##### 204 No Content

cookie가 있었는지, 토큰이 이미 폐기됐었는지 여부와 무관하게 항상 204를 반환한다.
`Set-Cookie: refresh_token=; Max-Age=0; Path=/api/auth`로 cookie를 지운다.

---

## GET /api/users/me

- 목적: 로그인한 사용자 본인 정보와 온보딩 완료 여부를 조회한다.
- 인증: Bearer JWT required
- 권한: 본인 리소스

### Request

Headers:

| 이름 | 값 | 필수 |
|---|---|---|
| Authorization | Bearer `{accessToken}` | O |

### Responses

##### 200 OK

```json
{
  "id": 1,
  "email": "user@example.com",
  "nickname": "tester",
  "profileImage": null,
  "onboardingCompleted": false
}
```

`onboardingCompleted`는 온보딩 모듈(WORK-02)이 아직 구현되지 않아 항상 `false`다.

##### 401 Unauthorized

인증 토큰이 없거나 유효하지 않다.

```json
{
  "status": 401,
  "code": "AUTH_UNAUTHENTICATED",
  "message": "로그인이 필요합니다."
}
```

#### Related

- [작업 명세](../features/auth-login-signup.md)
- [사용자 정보 주입](../conventions/유저-정보-주입.md)
