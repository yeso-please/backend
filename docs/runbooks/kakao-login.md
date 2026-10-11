# 카카오 로그인 설정

카카오 로그인(API [1-6](../api/auth.md#1-6-카카오-로그인))을 환경별로 켜는 절차다. 비밀값은 저장소에 커밋하지 않는다.

## 카카오 개발자 콘솔

식당 검색과 같은 카카오 앱을 쓴다.

1. 내 애플리케이션 → 앱 선택 → 플랫폼 → Web에 프론트 도메인 등록(local `http://localhost:5173`, dev·prod 프론트 주소)
2. 제품 설정 → 카카오 로그인 → **활성화**
3. Redirect URI 등록: `<프론트 주소>/auth/kakao/callback` (local `http://localhost:5173/auth/kakao/callback`). local 외에는 HTTPS만 등록한다
4. 동의항목 → **닉네임(`profile_nickname`) 필수 동의**. 이메일·프로필 사진은 요청하지 않는다
5. 보안 → **Client Secret 발급 후 활성화**
6. REST API 키와 Client Secret은 팀 비밀 저장소로만 공유한다

## 서버 설정

| 설정 | 환경변수 | 값 |
|---|---|---|
| `auth.kakao.client-id` | `KAKAO_LOGIN_CLIENT_ID` | 로그인용 앱의 REST API 키. 비우면 식당 검색 키(`KAKAO_REST_API_KEY`)를 그대로 쓴다(같은 앱) |
| `auth.kakao.client-secret` | `KAKAO_CLIENT_SECRET` | 위 5번의 Client Secret |
| `auth.kakao.allowed-redirect-uris` | `KAKAO_ALLOWED_REDIRECT_URIS` | 쉼표로 구분. 콘솔의 Redirect URI와 **정확히** 같아야 한다 |

- local profile은 `KAKAO_ALLOWED_REDIRECT_URIS`가 없으면 `http://localhost:5173/auth/kakao/callback`을 허용한다. 그 외 profile은 기본값이 없다.
- 로컬에서는 `config/application-secret.yaml`(gitignore)에 `auth.kakao.client-secret`을 넣어도 된다. 예시는 `config/application-secret.example.yaml`.
- 값이 비어 있어도 서버는 기동된다. 카카오 로그인 요청만 `503 AUTH_KAKAO_NOT_CONFIGURED`다.

## 확인

- 로그 `Kakao login failed reason=...`로 실패 사유(`AUTH_KAKAO_INVALID_CODE`, `AUTH_KAKAO_UNAVAILABLE` 등)를, `User signed up ... method=KAKAO|EMAIL`로 가입 경로를 센다. 인가 코드·카카오 토큰·client secret·카카오 회원번호는 로그에 남기지 않는다.
- 카카오가 `invalid_client`(KOE010 등)를 주면 `502 AUTH_KAKAO_UNAVAILABLE`과 함께 `Kakao token exchange returned status=401 error=invalid_client` 로그가 남는다 — client secret 설정을 확인한다.
- Redirect URI 불일치는 콘솔 등록값과 `KAKAO_ALLOWED_REDIRECT_URIS`, 프론트가 인가 요청에 쓴 값이 셋 다 같은지 확인한다.
