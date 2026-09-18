# 인증 API

- 상태: implemented (이메일 회원가입/로그인만 — 소셜 로그인은 미구현)
- 갱신일: 2026-09-18
- 관련 기능 명세: [회원가입/로그인/인증 모듈](../features/auth-login-signup.md)

## POST /api/auth/signup

- 목적: 이메일/비밀번호로 신규 계정을 생성하고 즉시 로그인 처리(JWT 발급)한다.
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
| email | string | O | 이메일 형식, 최대 190자, 중복 불가 |
| password | string | O | 8~64자 (BCrypt 해시로만 저장) |
| nickname | string | O | 1~30자 |

### Responses

##### 201 Created

```json
{
  "accessToken": "...",
  "refreshToken": "...",
  "tokenType": "Bearer",
  "expiresIn": 1800
}
```

##### 400 Bad Request

형식이 올바르지 않다(이메일 형식, 비밀번호 길이, 닉네임 길이 등). 필드별 메시지를 한글로 반환한다.

##### 409 Conflict

이미 사용 중인 이메일이다.

#### Side effects

- `users`에 1건을 생성한다.
- `refresh_tokens`에 발급된 refresh 토큰의 해시를 1건 저장한다.

#### Related

- [작업 명세](../features/auth-login-signup.md)
- [API 응답 형식](../conventions/API-응답-형식.md)

---

## POST /api/auth/login

- 목적: 이메일/비밀번호로 로그인해 access+refresh 토큰을 발급한다.
- 인증: 불필요

### Request

Body:

```json
{
  "email": "user@example.com",
  "password": "password123"
}
```

### Responses

##### 200 OK

`POST /api/auth/signup`의 201 응답과 동일한 `TokenResponse` 형식.

##### 401 Unauthorized

```json
{
  "status": 401,
  "message": "이메일 또는 비밀번호가 올바르지 않습니다."
}
```

이메일이 존재하지 않는 경우와 비밀번호가 틀린 경우 **동일한 메시지**를 반환한다(계정 존재 여부 비노출).

#### Side effects

- `refresh_tokens`에 새 refresh 토큰의 해시를 1건 저장한다.

---

## POST /api/auth/refresh

- 목적: refresh 토큰으로 access 토큰을 재발급한다. refresh 토큰도 함께 rotate된다.
- 인증: 불필요 (refresh 토큰 자체가 자격 증명)

### Request

Body:

```json
{
  "refreshToken": "..."
}
```

### Responses

##### 200 OK

새로 발급된 `TokenResponse`(access+refresh 모두 새 값). 응답으로 받은 새 refresh 토큰으로 클라이언트가
보유 값을 반드시 교체해야 한다.

##### 401 Unauthorized

```json
{
  "status": 401,
  "message": "인증이 만료되었습니다. 다시 로그인해주세요."
}
```

refresh 토큰이 만료·위조·폐기(revoked)되었거나, 이미 한 번 사용되어 rotate된 이전 값을 재사용한 경우.

#### Side effects

- 기존 refresh 토큰 row를 `revoked=true`로 변경한다.
- 새 refresh 토큰의 해시를 `refresh_tokens`에 1건 저장한다.

#### 멱등성

멱등하지 않다 — 같은 refresh 토큰으로 두 번째 호출하면 첫 번째 호출에서 이미 rotate되어 401을 반환한다.

---

## POST /api/auth/logout

- 목적: 전달받은 refresh 토큰을 폐기한다.
- 인증: 불필요 (refresh 토큰 자체가 대상 식별자)

### Request

Body:

```json
{
  "refreshToken": "..."
}
```

### Responses

##### 204 No Content

토큰이 존재했는지 여부와 무관하게 항상 204를 반환한다(이미 폐기된 토큰으로 다시 호출해도 에러 아님).

---

## GET /api/users/me

- 목적: 로그인한 사용자 본인 정보를 조회한다.
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
  "createdAt": "2026-09-18T20:36:20.383"
}
```

##### 401 Unauthorized

인증 토큰이 없거나 유효하지 않다.

```json
{
  "status": 401,
  "message": "인증이 필요합니다."
}
```

#### Related

- [작업 명세](../features/auth-login-signup.md)
- [사용자 정보 주입](../conventions/유저-정보-주입.md)
