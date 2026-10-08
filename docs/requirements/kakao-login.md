# 카카오 로그인 요구사항

- 상태: draft (팀 리뷰 전)
- 위치: 합의되면 정책은 `product.md`, 계약은 `api/auth.md`로 옮기고 이 문서는 지운다
- 작성일: 2026-10-08
- 범위: 백엔드 `auth` 모듈 + 프론트 로그인 화면
- 관련 문서: [제품 정책 – 범위](../product.md#범위) (현재 "MVP 이후 논의"), [API 1장 인증](../api/auth.md), [ADR 0003 refresh token 회전](../adr/0003-opaque-refresh-token-rotation.md)

---

## 1. 배경과 목표

### 왜 필요한가

TriPin은 사람을 끌어들이는 경로가 대부분 카카오톡입니다. 여행 초대, 친구 초대, 코스 공유 링크를 모두 카카오톡 공유로 보냅니다. 그런데 초대를 수락하려면 회원이어야 해서, 링크를 받은 사람은 지금 이런 과정을 거칩니다.

```text
카톡 링크 클릭 → 이메일·비밀번호·닉네임 입력 가입 → 온보딩 설문 → 초대 수락
```

가입 단계가 가장 큰 이탈 지점입니다. 카카오 로그인을 붙이면 이 단계가 "카카오로 계속하기" 한 번으로 줄어듭니다.

### 목표

1. 카카오 계정으로 **가입과 로그인을 한 번에** 할 수 있다.
2. 초대·친구 링크로 들어온 사람이 카카오 로그인 후 **원래 하던 흐름(초대 수락 등)으로 돌아간다.**
3. 기존 이메일 로그인, 토큰 구조, 다른 API는 **바뀌지 않는다.**

### 이번 범위가 아닌 것

| 항목 | 이유 |
|---|---|
| 기존 이메일 계정과 카카오 계정 연결·병합 | product.md에서 "계정 전환·병합"을 MVP 이후로 미뤘다. 이번엔 별개 계정으로 취급 |
| 구글 등 다른 소셜 로그인 | `SocialProvider.GOOGLE`은 있지만 이번 범위 밖 |
| 회원 탈퇴, 카카오 "연결 끊기" 알림 수신 | 회원 탈퇴 정책 자체가 후속 결정 사항 |
| 카카오 친구 목록 연동, 카카오 메시지 서버 발송 | 친구는 링크로만 맺는 정책 유지 |
| 카카오 프로필 이미지 표시 | `profileImage`는 현재 MVP 전체에서 `null`. 필요하면 별도 결정 |
| 모바일 앱(네이티브 SDK) 로그인 | 현재 클라이언트는 웹 |

### 현재 코드 상태 (이미 준비된 것)

| 항목 | 상태 |
|---|---|
| `social_accounts` 테이블 (`provider`, `provider_user_id`, unique 제약) | V1 baseline에 **있음** |
| `SocialAccount` 엔티티, `SocialProvider.KAKAO` | **있음** (사용하는 곳 없음) |
| `users.email`, `users.password_hash` nullable | **있음**. 단 `ck_users_local_credentials` 제약으로 **둘 다 있거나 둘 다 없어야** 함 |
| access token(JWT) + refresh cookie 발급·회전 | **있음**. 카카오 로그인도 그대로 재사용 |
| 카카오 REST API 키 설정 (`KAKAO_REST_API_KEY`) | 식당 검색용으로 **있음**. 같은 카카오 앱을 쓸지 결정 필요 (9장) |

---

## 2. 용어

| 용어 | 뜻 |
|---|---|
| 인가 코드 (`code`) | 카카오가 로그인 성공 후 redirect URI로 넘겨주는 일회용 코드. 10분 유효, 한 번만 쓸 수 있음 |
| 카카오 토큰 | 인가 코드를 교환해 받는 카카오의 access/refresh token. **TriPin 토큰과 다름** |
| 카카오 회원번호 | 카카오가 앱마다 부여하는 사용자 고유 ID (`id`). `social_accounts.provider_user_id`에 저장 |
| `state` | 로그인 요청과 콜백을 짝짓는 난수. CSRF 방지 + 로그인 후 돌아갈 위치 보관 |
| 소셜 전용 계정 | 비밀번호 없이 카카오로만 로그인하는 TriPin 회원 |

---

## 3. 전체 흐름

```text
[프론트] 카카오로 계속하기 클릭
   │  state 생성 → sessionStorage에 {state, returnTo} 저장
   ▼
[카카오] kauth.kakao.com/oauth/authorize?client_id&redirect_uri&response_type=code&state
   │  사용자 로그인·동의
   ▼
[프론트] /auth/kakao/callback?code=...&state=...
   │  state 검증 (저장한 값과 다르면 중단)
   ▼
[백엔드] POST /api/auth/kakao  {code, redirectUri}
   │  1) 카카오 토큰 교환 (client secret은 서버에만)
   │  2) 카카오 사용자 정보 조회 → 회원번호, 닉네임
   │  3) social_accounts로 회원 찾기 → 없으면 새 회원 생성
   │  4) 카카오 토큰은 버림 (저장하지 않음)
   │  5) TriPin access token + refresh cookie 발급
   ▼
[프론트] AuthResponse 수신
   │  onboardingCompleted=false → 온보딩 → returnTo
   │  onboardingCompleted=true  → returnTo (예: /invite/{token})
```

인가 코드를 받는 쪽은 **프론트**, 카카오와 토큰을 교환하는 쪽은 **백엔드**입니다. 이렇게 하면 client secret이 브라우저에 노출되지 않고, 로그인 이후 토큰 구조(access token은 body, refresh token은 쿠키)를 이메일 로그인과 똑같이 유지할 수 있습니다.

---

## 4. 기능 요구사항 (FR)

### FR-1. 카카오 로그인 시작 (프론트)

| ID | 요구사항 |
|---|---|
| FR-1.1 | 로그인 화면과 가입 화면에 "카카오로 계속하기" 버튼을 둔다. 카카오 디자인 가이드(노란 버튼, 공식 문구)를 따른다 |
| FR-1.2 | 버튼을 누르면 프론트가 추측 불가능한 `state`(최소 128bit 난수)를 만들어 `sessionStorage`에 로그인 후 돌아갈 경로(`returnTo`)와 함께 저장한다 |
| FR-1.3 | 카카오 인가 페이지로 이동한다. 파라미터는 `client_id`(REST API 키), `redirect_uri`, `response_type=code`, `state` |
| FR-1.4 | `returnTo`는 **앱 내부 경로만** 허용한다(`/`로 시작하고 `//`로 시작하지 않음). 외부 URL로 돌려보내지 않는다(open redirect 방지) |
| FR-1.5 | 초대 링크(`/invite/{token}`), 친구 링크, 공유 링크 화면에서 로그인을 시작하면 그 화면 경로를 `returnTo`로 저장한다 |

### FR-2. 콜백 처리 (프론트)

| ID | 요구사항 |
|---|---|
| FR-2.1 | 콜백 라우트(`/auth/kakao/callback`)는 URL의 `state`가 저장한 값과 다르거나 없으면 백엔드를 호출하지 않고 "로그인을 다시 시도해 주세요"를 보여준다 |
| FR-2.2 | 사용자가 카카오 동의 화면에서 취소하면 카카오가 `error=access_denied`로 돌려준다. 이때 오류 없이 로그인 화면으로 돌아간다 |
| FR-2.3 | `code`는 한 번만 백엔드로 보낸다. 새로고침·뒤로가기로 같은 코드를 다시 보내지 않도록, 처리 후 `history.replaceState`로 URL에서 `code`·`state`를 지운다 |
| FR-2.4 | 백엔드 응답을 받으면 이메일 로그인과 같은 방식으로 access token을 보관하고, `onboardingCompleted`가 `false`면 온보딩 → `returnTo`, `true`면 바로 `returnTo`로 보낸다 |

### FR-3. 카카오 로그인 API (백엔드)

```
POST /api/auth/kakao
```

> `호출: 공개` · `⬜ 미구현`

**Request Body**

```json
{"code": "인가코드", "redirectUri": "https://tripin.example.com/auth/kakao/callback"}
```

| 필드 | 타입 | 필수 | 제약 |
|---|---|---|---|
| `code` | `string` | 예 | 1~512자 |
| `redirectUri` | `string` | 예 | 서버 설정의 허용 목록과 **정확히 일치**해야 함 |

**Response** — `AuthResponse` + refresh cookie (이메일 로그인과 같은 모양)

| 상황 | 상태 |
|---|---|
| 처음 카카오로 들어온 사람 (새 회원 생성) | `201 Created` |
| 이미 가입한 카카오 회원 | `200 OK` |

| ID | 요구사항 |
|---|---|
| FR-3.1 | 서버는 `code`, `redirectUri`, REST API 키, client secret으로 카카오 토큰 교환(`POST https://kauth.kakao.com/oauth/token`)을 한다 |
| FR-3.2 | 받은 카카오 access token으로 사용자 정보(`GET https://kapi.kakao.com/v2/user/me`)를 조회해 **회원번호**와 **닉네임**을 얻는다 |
| FR-3.3 | `social_accounts`에서 `(KAKAO, 회원번호)`로 회원을 찾는다. 있으면 그 회원으로 로그인한다 |
| FR-3.4 | 없으면 새 회원(`users`)과 `social_accounts`를 **한 트랜잭션**으로 만든다. 이메일·비밀번호는 비워 둔다(소셜 전용 계정) |
| FR-3.5 | 닉네임은 카카오 닉네임을 trim해 최대 30자로 자른다. 비어 있거나 동의하지 않았으면 `여행자` + 4자리 숫자 같은 기본 닉네임을 준다 |
| FR-3.6 | 기존 회원이 다시 로그인할 때는 닉네임을 **덮어쓰지 않는다** (TriPin 안에서 정한 이름 유지) |
| FR-3.7 | 로그인 성공 시 이메일 로그인과 같은 방식으로 새 refresh token family를 만들고 access token을 발급한다 |
| FR-3.8 | 카카오 access/refresh token은 사용자 정보 조회 후 **저장하지 않고 버린다** |
| FR-3.9 | 새 회원의 `onboardingCompleted`는 `false`다. 이후 온보딩·여행 생성·초대 수락 규칙은 이메일 회원과 완전히 같다 |

**오류**

| 상황 | HTTP | code |
|---|---:|---|
| `code`·`redirectUri` 누락·형식 오류 | 400 | `COMMON_INVALID_REQUEST` |
| `redirectUri`가 허용 목록에 없음 | 400 | `AUTH_KAKAO_INVALID_REDIRECT_URI` |
| 인가 코드가 만료·재사용·위조됨 (카카오가 `invalid_grant`) | 401 | `AUTH_KAKAO_INVALID_CODE` |
| 카카오 서버 장애·timeout·예상 밖 응답 | 502 | `AUTH_KAKAO_UNAVAILABLE` |
| 서버에 카카오 키·secret 설정이 없음 | 503 | `AUTH_KAKAO_NOT_CONFIGURED` |

### FR-4. 같은 사람의 동시 첫 로그인

| ID | 요구사항 |
|---|---|
| FR-4.1 | 같은 카카오 회원번호로 첫 로그인이 동시에 두 번 들어와도 TriPin 회원은 **하나만** 생긴다. `uq_social_provider_user` unique 제약 위반이 나면 생성한 쪽을 롤백하고, 이미 만들어진 회원으로 로그인시킨다 |
| FR-4.2 | 위 경우 두 요청 모두 성공 응답을 받는다(하나는 `201`, 하나는 `200`) |

### FR-5. 기존 API에 미치는 영향

| ID | 요구사항 |
|---|---|
| FR-5.1 | `AuthResponse.user.email`과 `GET /api/users/me`의 `email`은 소셜 전용 계정이면 **`null`**이다. ⚠️ 지금 계약은 항상 값이 있다고 가정하므로 **호환성 변경**이다. 프론트는 `email`이 없는 경우를 처리해야 한다 |
| FR-5.2 | `GET /api/users/me`에 `loginMethods` 배열을 추가한다. 값은 `EMAIL`, `KAKAO` (예: `["KAKAO"]`). 마이페이지에서 "카카오로 로그인 중" 표시에 쓴다 |
| FR-5.3 | 소셜 전용 계정은 이메일이 없으므로 `POST /api/auth/login`으로 로그인할 수 없다. 별도 처리 없이 기존 `401 AUTH_INVALID_CREDENTIALS`가 된다 |
| FR-5.4 | `POST /api/auth/refresh`, `POST /api/auth/logout`은 바뀌지 않는다. 로그아웃은 **TriPin 세션만** 끝내고 카카오 계정 로그아웃은 하지 않는다 |
| FR-5.5 | 이메일 가입(`POST /api/auth/signup`)은 그대로 둔다. 카카오 회원과 같은 사람이 이메일로 따로 가입하면 **별개의 회원**이 된다(병합 없음, 범위 밖) |

### FR-6. 카카오 동의 항목

| 항목 | 설정 | 용도 |
|---|---|---|
| 닉네임 (`profile_nickname`) | 필수 동의 | TriPin 닉네임 초기값 |
| 프로필 사진 (`profile_image`) | 요청하지 않음 | 현재 사용처 없음 |
| 카카오계정 이메일 (`account_email`) | **요청하지 않음** (9장 결정 필요) | 이메일을 받지 않으면 비즈 앱 전환·검수 없이 시작 가능 |

---

## 5. 비기능 요구사항 (NFR)

### NFR-1. 보안

| ID | 요구사항 |
|---|---|
| NFR-1.1 | **client secret은 서버에만** 둔다. `config/application-secret.yaml` 또는 환경변수로 넣고 저장소에 커밋하지 않는다. 카카오 개발자 콘솔에서 Client Secret을 **활성화**한다 |
| NFR-1.2 | 서버는 `redirectUri`를 환경별 **허용 목록**과 정확히 비교한다(부분 일치·와일드카드 금지). 허용 목록은 카카오 콘솔에 등록한 Redirect URI와 같아야 한다 |
| NFR-1.3 | 운영 환경의 Redirect URI는 **HTTPS만** 허용한다. `http://localhost`는 local 프로필에서만 허용한다 |
| NFR-1.4 | `state`로 로그인 CSRF를 막는다(FR-1.2·FR-2.1) |
| NFR-1.5 | 인가 코드, 카카오 토큰, client secret, TriPin 토큰은 **로그에 남기지 않는다.** 카카오 회원번호도 로그에는 마스킹하거나 내부 user id로 대신 적는다 |
| NFR-1.6 | 카카오 토큰은 DB·캐시·로그 어디에도 저장하지 않는다(FR-3.8) |
| NFR-1.7 | refresh token 정책(HttpOnly·SameSite=Lax·`Path=/api/auth`·회전·재사용 탐지)은 ADR 0003을 그대로 따른다. 카카오 로그인이라고 예외를 두지 않는다 |
| NFR-1.8 | `POST /api/auth/kakao`도 로그인 API와 같은 수준의 요청 빈도 제한 대상에 넣는다(제한을 도입할 때) |

### NFR-2. 성능

| ID | 요구사항 |
|---|---|
| NFR-2.1 | 카카오 호출(토큰 교환, 사용자 정보)마다 **연결 timeout 2초, 응답 timeout 3초**를 둔다. 기존 카카오 Local 클라이언트의 `timeout-millis: 3000`과 맞춘다 |
| NFR-2.2 | `POST /api/auth/kakao` 전체 응답 시간은 정상 상황에서 **p95 2초 이내**를 목표로 한다 (대부분 카카오 왕복 2회 시간) |
| NFR-2.3 | 카카오 호출 중에는 DB 트랜잭션을 열어 두지 않는다. 카카오에서 사용자 정보를 받은 **뒤에** 회원 조회·생성 트랜잭션을 시작한다 |

### NFR-3. 가용성·장애 대응

| ID | 요구사항 |
|---|---|
| NFR-3.1 | 카카오 장애는 **카카오 로그인만** 실패시킨다. 이메일 로그인, refresh, 다른 모든 API는 영향이 없어야 한다 |
| NFR-3.2 | 인가 코드 교환은 **재시도하지 않는다.** 코드는 한 번만 쓸 수 있어서 재시도하면 `invalid_grant`가 되기 때문이다. 사용자 정보 조회는 timeout·5xx일 때 1회까지 재시도할 수 있다 |
| NFR-3.3 | 카카오 장애 시 프론트는 "카카오 로그인이 잠시 안 돼요. 이메일로 로그인할 수 있어요"를 보여준다 |
| NFR-3.4 | 카카오 키·secret 설정이 비어 있어도 애플리케이션은 기동된다. 카카오 로그인 요청만 `503 AUTH_KAKAO_NOT_CONFIGURED`가 된다 (식당 검색의 기존 방식과 같음) |

### NFR-4. 개인정보

| ID | 요구사항 |
|---|---|
| NFR-4.1 | 서비스에 꼭 필요한 동의 항목만 요청한다(FR-6). 카카오에서 받은 정보 중 **회원번호와 닉네임만** 저장한다 |
| NFR-4.2 | 개인정보 처리방침에 "카카오로부터 받는 항목(회원번호, 닉네임)과 이용 목적"을 추가한다 |
| NFR-4.3 | 다른 참여자·공유 링크 뷰어에게는 지금처럼 닉네임만 노출한다. 카카오 회원번호는 어떤 API 응답에도 넣지 않는다 |

### NFR-5. 호환성

| ID | 요구사항 |
|---|---|
| NFR-5.1 | 기존 이메일 회원의 데이터·로그인 방식·토큰은 바뀌지 않는다 |
| NFR-5.2 | DB 변경은 Flyway migration으로만 한다. 현재 스키마로 충분하면(이메일을 받지 않는 경우) **migration 없이** 구현할 수 있어야 한다 |
| NFR-5.3 | `email`이 `null`이 될 수 있다는 계약 변경(FR-5.1)은 `docs/api/auth.md`와 프론트 가이드를 **같은 PR에서** 고치고, PR 본문에 영향 화면(마이페이지 등)을 적는다 |

### NFR-6. 테스트

| ID | 요구사항 |
|---|---|
| NFR-6.1 | 카카오 호출은 인터페이스(예: `KakaoOAuthClient`) 뒤에 두고, 테스트에서는 가짜 응답으로 바꾼다. **CI에서 실제 카카오를 호출하지 않는다** |
| NFR-6.2 | 팀 테스트 규칙([conventions/테스트.md](../conventions/테스트.md))의 "엔드포인트 PR 최소 테스트"를 따른다: 신규·기존 회원 성공 1건씩, 오류 code마다 1건(`$.code` 검증), validation 실패 1건 |
| NFR-6.3 | 동시 첫 로그인(FR-4)은 동시성 테스트 1건으로 회원이 하나만 생기는지 확인한다 |
| NFR-6.4 | 응답에 카카오 토큰·회원번호가 없는지, refresh cookie가 이메일 로그인과 같은 속성으로 나가는지 확인한다 |

### NFR-7. 운영·관측

| ID | 요구사항 |
|---|---|
| NFR-7.1 | 카카오 로그인 성공·실패를 사유별(`INVALID_CODE`, `UNAVAILABLE`, `INVALID_REDIRECT_URI` 등)로 셀 수 있게 로그를 남긴다. 개인 식별 정보는 넣지 않는다 |
| NFR-7.2 | 신규 가입 중 카카오 가입 비율을 볼 수 있게, 가입 로그에 가입 경로(`EMAIL`·`KAKAO`)를 남긴다 |
| NFR-7.3 | 환경별 카카오 콘솔 설정을 runbook에 적는다: 플랫폼 Web 사이트 도메인, Redirect URI(local·dev·prod), 카카오 로그인 활성화, 동의 항목, Client Secret |

---

## 6. 데이터

| 테이블 | 변경 |
|---|---|
| `users` | 이메일을 받지 않으면 **변경 없음**. 소셜 전용 계정은 `email = NULL`, `password_hash = NULL` (현재 CHECK 제약을 만족) |
| `social_accounts` | 변경 없음. `(provider='KAKAO', provider_user_id=카카오 회원번호, user_id)` 1건 |
| `refresh_tokens` | 변경 없음 |

카카오 이메일을 받기로 결정하면(9장 D-2) 현재 `ck_users_local_credentials` 제약("이메일과 비밀번호는 둘 다 있거나 둘 다 없음")과 충돌합니다. 이 경우 `social_accounts`에 이메일 컬럼을 두는 쪽을 권장합니다. `users.email`에 넣으면 이메일 unique 제약과 이메일 로그인 계정이 엉키기 때문입니다.

---

## 7. 화면 요구사항 요약 (프론트)

| 화면 | 할 일 |
|---|---|
| 로그인·가입 | "카카오로 계속하기" 버튼 추가 (FR-1) |
| 초대·친구·공유 링크 진입 화면 | 로그인 유도 시 현재 경로를 `returnTo`로 저장 (FR-1.5) |
| `/auth/kakao/callback` | state 검증, 취소 처리, 백엔드 호출, URL 정리, 분기 (FR-2) |
| 마이페이지 | `email`이 `null`이면 숨기고 "카카오로 로그인 중" 표시 (FR-5.1·5.2) |
| 오류 | `AUTH_KAKAO_INVALID_CODE` → "다시 시도해 주세요" 후 로그인 화면. `AUTH_KAKAO_UNAVAILABLE` → 이메일 로그인 안내 (NFR-3.3) |

---

## 8. 완료 기준

- [ ] 처음 카카오로 로그인하면 `201`과 함께 회원이 생기고, 온보딩 화면으로 간다
- [ ] 같은 카카오 계정으로 다시 로그인하면 `200`이고 같은 회원(`user.id`)이다
- [ ] 초대 링크에서 카카오 로그인 → 온보딩 → 초대 수락까지 끊기지 않는다
- [ ] 카카오 동의 화면에서 취소하면 오류 없이 로그인 화면으로 돌아온다
- [ ] 같은 인가 코드를 두 번 보내면 두 번째는 `401 AUTH_KAKAO_INVALID_CODE`다
- [ ] 허용되지 않은 `redirectUri`는 카카오를 호출하지 않고 `400 AUTH_KAKAO_INVALID_REDIRECT_URI`다
- [ ] 카카오 장애(가짜 서버로 5xx·timeout) 시 `502 AUTH_KAKAO_UNAVAILABLE`이고, 이메일 로그인은 정상이다
- [ ] 동시 첫 로그인 2건에도 회원이 1명만 생긴다
- [ ] refresh·logout이 카카오 회원에게도 이메일 회원과 똑같이 동작한다
- [ ] 응답·로그·DB에 카카오 토큰과 인가 코드가 남지 않는다
- [ ] `docs/product.md` 결정 기록, `docs/api/auth.md` 새 절(1-6), API README 체크리스트, 프론트 API 가이드가 갱신됐다

---

## 9. 결정 필요

| # | 질문 | 선택지 | 권장 |
|---|---|---|---|
| D-1 | 카카오 로그인을 MVP 이후 첫 작업으로 올릴지 | 지금 / MVP 흐름 안정화 후 | MVP 흐름 안정화 후 바로 |
| D-2 | 카카오 이메일을 받을지 | 받지 않음 / 선택 동의로 받음 / 필수 동의(비즈 앱 필요) | **받지 않음.** 쓸 곳이 없고, 받으면 스키마 제약·계정 병합 문제가 함께 따라온다 |
| D-3 | 카카오 앱을 식당 검색용과 같이 쓸지 | 같은 앱 / 로그인 전용 앱 분리 | 같은 앱 (카카오톡 공유·지도·로그인을 한 앱에서 관리). 단 Client Secret과 Redirect URI는 로그인 전용 설정 |
| D-4 | 카카오 프로필 사진을 쓸지 | 안 씀 / `profileImage`에 저장 | 안 씀 (MVP 전체에서 `profileImage`가 `null`) |
| D-5 | 신규 여부를 상태 코드(`201`/`200`)로만 알릴지, `AuthResponse`에 `newUser` 필드도 추가할지 | 상태 코드만 / 필드 추가 | 상태 코드만 (기존 응답 모양 유지) |
| D-6 | 이미 이메일로 가입한 사람에게 "카카오 계정 연결" 기능을 언제 줄지 | 이번 범위 / 후속 | 후속 (계정 병합 정책과 함께) |

---

## 10. 반영할 문서 (구현 PR에서)

| 문서 | 내용 |
|---|---|
| `docs/product.md` | 범위 표에서 카카오 로그인을 옮기고, 결정 기록에 한 줄 추가 |
| `docs/api/auth.md` | `1-6. 카카오 로그인` 절 추가, `email` nullable 반영, 오류 코드 표에 `AUTH_KAKAO_*` 추가 |
| `docs/api/README.md` | 구현 체크리스트에 행 추가 |
| `docs/runbooks/` | 카카오 개발자 콘솔 환경별 설정 절차 |
| 프론트 `frontend-api-guide.md` | 카카오 로그인 흐름, `email: null` 처리 |
| ADR (선택) | 인가 코드를 프론트가 받고 백엔드가 교환하는 방식을 고른 이유 |

---

## 11. 구현 가이드 (현재 코드 기준)

작업자가 바로 시작할 수 있도록 기존 코드에서 따라 할 패턴과 고칠 곳을 정리했다. 경로는 `src/main/java/com/yeso/backend/` 기준이다.

### 따라 할 기존 패턴

| 할 일 | 참고할 코드 |
|---|---|
| 카카오 HTTP 클라이언트 (인터페이스 + `RestClient` 구현, timeout, 오류 변환) | `trip/infrastructure/KakaoLocalClient.java`, `HttpKakaoLocalClient.java` |
| 설정 바인딩 (`@ConfigurationProperties`, 비어 있어도 기동) | `trip/infrastructure/RestaurantProperties.java`, `application.yml`의 `course.restaurant.kakao` |
| 테스트용 가짜 외부 클라이언트 (`@Primary` 등록, 테스트마다 `reset()`) | `src/test/.../trip/infrastructure/FakeKakaoLocalClient.java`, `support/TestInfraConfig.java`, `support/IntegrationTest.java` |
| 토큰 발급 + refresh cookie 응답 | `auth/application/AuthService.java`의 `issueNewFamily`, `auth/presentation/AuthController.java`의 `withRefreshCookie` |
| 도메인 예외 → 오류 응답 | `auth/domain/InvalidCredentialsException.java`, `shared/exception/ErrorCode.java` |
| 통합 테스트 | `src/test/.../auth/AuthIntegrationTest.java` |

### 새로 만들 것

| 파일 (제안) | 내용 |
|---|---|
| `auth/infrastructure/KakaoOAuthClient.java` | 인터페이스. `exchangeCode(code, redirectUri)` → 카카오 access token, `fetchUser(accessToken)` → 회원번호·닉네임 |
| `auth/infrastructure/HttpKakaoOAuthClient.java` | 구현. `kauth.kakao.com/oauth/token`(form-urlencoded), `kapi.kakao.com/v2/user/me`. 400 `invalid_grant` → `AUTH_KAKAO_INVALID_CODE`, 5xx·timeout → `AUTH_KAKAO_UNAVAILABLE` |
| `auth/infrastructure/KakaoOAuthProperties.java` | `client-id`, `client-secret`, `allowed-redirect-uris`, `timeout-millis` |
| `auth/domain/SocialAccountRepository.java` | `findByProviderAndProviderUserId(...)` |
| `auth/presentation/KakaoLoginRequest.java` | `code`, `redirectUri` (Bean Validation) |
| `auth/domain/Kakao*Exception.java` | 4장 FR-3 오류 표의 4개 code |
| `src/test/.../auth/infrastructure/FakeKakaoOAuthClient.java` | 성공·`invalid_grant`·장애를 테스트마다 지정 |

### 고칠 곳

| 파일 | 변경 |
|---|---|
| `auth/application/AuthService.java` | `loginWithKakao(code, redirectUri)` 추가. **카카오 호출은 트랜잭션 밖에서** 하고(NFR-2.3) 회원 조회·생성만 트랜잭션으로 묶는다. 클래스에 `@Transactional`이 걸려 있으므로 카카오 호출 단계를 별도 빈으로 분리하거나 이 메서드에 `@Transactional(propagation = NOT_SUPPORTED)`를 쓴다 |
| `auth/presentation/AuthController.java` | `POST /api/auth/kakao` 추가. 신규면 `201`, 기존이면 `200`으로 `withRefreshCookie` 호출 |
| `shared/exception/ErrorCode.java` | `AUTH_KAKAO_INVALID_REDIRECT_URI`(400), `AUTH_KAKAO_INVALID_CODE`(401), `AUTH_KAKAO_UNAVAILABLE`(502), `AUTH_KAKAO_NOT_CONFIGURED`(503) |
| `auth/presentation/UserMeResponse.java` | `loginMethods` 추가 (FR-5.2) |
| `application.yml` | `auth.kakao.*` 설정 블록. 비밀값은 `KAKAO_CLIENT_SECRET` 환경변수 또는 `config/application-secret.yaml` |
| `.env.example` | `KAKAO_CLIENT_SECRET=`, `KAKAO_ALLOWED_REDIRECT_URIS=` 추가 |
| `auth/infrastructure/SecurityConfig.java` | 변경 없음. `/api/auth/**`가 이미 `permitAll` |

### 주의할 점

- **email `null` 처리**: `JwtTokenProvider.generateAccessToken(userId, email)`이 email claim을 넣고 인증 principal이 이 값을 읽는다. 카카오 회원은 `null`이므로, principal의 email을 쓰는 곳이 없는지 확인하고 `null`에서도 토큰 발급·인증이 되는지 테스트한다.
- **동시 첫 로그인(FR-4)**: `uq_social_provider_user` 위반(`DataIntegrityViolationException`)을 잡아 기존 회원을 다시 조회한다. 위반이 난 트랜잭션 안에서 재조회하면 실패하므로 **새 트랜잭션**에서 조회한다.
- **migration 불필요**: 이메일을 받지 않으면(D-2 권장안) 현재 스키마로 충분하다. `ck_users_local_credentials`를 건드리지 않는다.
- **카카오 콘솔 설정**: 내 애플리케이션 → 카카오 로그인 활성화, Redirect URI 등록(`http://localhost:5173/auth/kakao/callback` 등 환경별), 동의항목에서 닉네임 필수, 보안 → Client Secret 발급·활성화.
