# OpenAPI 문서

## 접속 경로

- UI: `GET /swagger-ui.html`
- JSON: `GET /v3/api-docs`

두 경로는 로그인 없이 접근할 수 있습니다. API 호출 자체의 권한은 변경되지 않습니다.

## 인증

Swagger UI 우측 **Authorize**에 로그인·가입 응답의 `accessToken`을 입력하면 `Authorization: Bearer {token}` 헤더가 필요한 API를 시험할 수 있습니다. refresh token이나 비밀값을 문서 화면에 저장하지 않습니다.

## 환경별 비활성화

외부 노출이 불필요한 환경은 `SPRINGDOC_API_DOCS_ENABLED=false`와 `SPRINGDOC_SWAGGER_UI_ENABLED=false`를 함께 설정합니다.
