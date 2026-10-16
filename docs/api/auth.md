# 1. 인증

- 계약 상태: agreed
- 모듈: `auth`
- 범위: 이메일·비밀번호 로컬 로그인과 카카오 로그인(1-6). 다른 소셜 로그인과 계정 연결·병합은 범위 밖이다.

## 공통

refresh token은 응답 body에 담지 않는다. 가입·로그인·refresh 성공 시 서버가 `Set-Cookie`로 내려준다.

```text
Set-Cookie: refresh_token=...; HttpOnly; SameSite=Lax; Path=/api/auth; Max-Age=1209600[; Secure]
```

`Secure`는 `app.auth.cookie-secure` 설정을 따른다. local(HTTP)은 `false`, 그 외 HTTPS 환경은 `true`다.

**`AuthResponse`** — 가입·로그인·카카오 로그인·refresh의 공통 성공 응답

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
| `user.email` | `string?` | 카카오로 가입한 소셜 전용 회원은 `null`. 프론트는 값이 없는 경우를 처리해야 한다 |
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
| `AUTH_KAKAO_INVALID_REDIRECT_URI` | 400 | `redirectUri`가 서버 허용 목록과 정확히 같지 않음 |
| `AUTH_KAKAO_INVALID_CODE` | 401 | 카카오가 인가 코드를 거부함(만료·재사용·위조) |
| `AUTH_KAKAO_UNAVAILABLE` | 502 | 카카오 서버 장애·timeout·예상 밖 응답 |
| `AUTH_KAKAO_NOT_CONFIGURED` | 503 | 서버에 카카오 키·secret·허용 redirectUri 설정이 없음 |

---

### 1-1. 회원가입

> `호출: 공개` · `✅ 구현`

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

> `호출: 공개` · `✅ 구현`

```
POST /api/auth/login
```

**Request Body**

```json
{"email": "user@example.com", "password": "password123"}
```

`email`은 가입과 같은 방식으로 정규화해 비교한다. 카카오로 가입한 소셜 전용 회원은 비밀번호가 없어 이 API로 로그인할 수 없다(`AUTH_INVALID_CREDENTIALS`).

**Response `200 OK`** — `AuthResponse` + refresh cookie

| 오류 | HTTP | code |
|---|---:|---|
| 이메일 없음·비밀번호 불일치 | 401 | `AUTH_INVALID_CREDENTIALS` |

두 경우 모두 같은 메시지 "이메일 또는 비밀번호가 올바르지 않습니다."를 반환해 계정 존재 여부를 숨긴다.

**Side effects** — 새 family의 `refresh_tokens` 해시 1건 생성.

---

### 1-3. 토큰 갱신

> `호출: refresh cookie` · `✅ 구현`

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

> `호출: 공개` · `✅ 구현`

```
POST /api/auth/logout
```

요청 body 없음. cookie가 없어도 된다.

**Response `204 No Content`** — cookie 유무·폐기 여부와 관계없이 항상 204. `Set-Cookie: refresh_token=; Max-Age=0; Path=/api/auth`로 cookie를 지운다. 카카오 회원도 TriPin 세션만 끝내고 카카오 계정 로그아웃은 하지 않는다.

---

### 1-5. 내 정보

> `호출: 회원` · `✅ 구현`

```
GET /api/users/me
```

**Response `200 OK`**

```json
{"id": 1, "email": "user@example.com", "nickname": "tester", "profileImage": null, "onboardingCompleted": true, "loginMethods": ["EMAIL"]}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `email` | `string?` | 카카오 소셜 전용 회원은 `null`. 마이페이지는 숨기고 "카카오로 로그인 중"을 보여준다 |
| `loginMethods` | `string[]` | 이 회원이 로그인할 수 있는 방법. `EMAIL`, `KAKAO` (예: `["KAKAO"]`) |

| 오류 | HTTP | code |
|---|---:|---|
| 토큰 없음·무효 | 401 | `AUTH_UNAUTHENTICATED` |
| 토큰의 사용자가 없음 | 404 | `AUTH_USER_NOT_FOUND` |

---

### 1-6. 카카오 로그인

> `호출: 공개` · `✅ 구현`

```
POST /api/auth/kakao
```

프론트가 카카오 인가 페이지(`kauth.kakao.com/oauth/authorize`)에서 받은 인가 코드를 보내면, 서버가 client secret으로 카카오 토큰을 교환하고 사용자 정보(회원번호·닉네임)를 조회한다. 가입과 로그인을 한 번에 처리한다. CSRF 방지용 `state` 생성·검증은 프론트가 한다.

**Request Body**

```json
{"code": "인가코드", "redirectUri": "http://localhost:5173/auth/kakao/callback"}
```

| 필드 | 타입 | 필수 | 제약 |
|---|---|---|---|
| `code` | `string` | 예 | 1~512자. 한 번만 쓸 수 있다 |
| `redirectUri` | `string` | 예 | 서버 허용 목록(`auth.kakao.allowed-redirect-uris`)과 **정확히 일치**. 인가 요청에 쓴 값과 같아야 한다 |

**Response** — `AuthResponse` + refresh cookie (이메일 로그인과 같은 모양·cookie 속성)

| 상황 | 상태 |
|---|---|
| 처음 카카오로 들어온 사람(새 회원 생성) | `201 Created` |
| 이미 가입한 카카오 회원 | `200 OK` |

- 새 회원은 이메일·비밀번호가 없는 소셜 전용 회원이다. `user.email`은 `null`, `onboardingCompleted`는 `false`다.
- 닉네임은 카카오 닉네임을 trim해 최대 30자로 자른다. 비어 있거나 동의하지 않았으면 `여행자` + 4자리 숫자다. 기존 회원이 다시 로그인할 때는 닉네임을 덮어쓰지 않는다.
- 같은 카카오 계정이라도 이메일로 따로 가입한 회원과는 별개 회원이다(병합 없음).
- 카카오 회원번호와 카카오 토큰은 응답에 넣지 않고, 카카오 토큰은 저장하지 않는다.

| 오류 | HTTP | code |
|---|---:|---|
| `code`·`redirectUri` 누락·형식 오류 | 400 | `COMMON_INVALID_REQUEST` |
| `redirectUri`가 허용 목록에 없음(카카오를 호출하지 않음) | 400 | `AUTH_KAKAO_INVALID_REDIRECT_URI` |
| 인가 코드 만료·재사용·위조 | 401 | `AUTH_KAKAO_INVALID_CODE` |
| 카카오 장애·timeout·예상 밖 응답 | 502 | `AUTH_KAKAO_UNAVAILABLE` |
| 서버에 카카오 설정이 없음 | 503 | `AUTH_KAKAO_NOT_CONFIGURED` |

- **재시도**: 인가 코드 교환은 재시도하지 않는다(코드는 한 번만 쓸 수 있다). 사용자 정보 조회는 timeout·5xx일 때 1회 재시도한다. 카카오 호출마다 연결 2초·응답 3초 timeout이다.
- **동시 첫 로그인**: 같은 카카오 회원번호의 첫 로그인이 동시에 와도 회원은 하나만 생기고 두 요청 모두 성공한다(하나는 `201`, 하나는 `200`).
- 프론트는 `AUTH_KAKAO_INVALID_CODE`면 다시 로그인하게 하고, `AUTH_KAKAO_UNAVAILABLE`이면 이메일 로그인을 안내한다.

**Side effects** — 새 회원이면 `users`·`social_accounts` 각 1건, 그리고 새 family의 `refresh_tokens` 해시 1건 생성.
