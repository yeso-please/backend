# OpenAPI·Swagger 공통 기반

## 목적

프론트엔드와 백엔드가 실제 구현과 같은 API 계약을 빠르게 확인하고, JWT 인증 API를 브라우저에서 검증할 수 있도록 OpenAPI 3와 Swagger UI를 제공합니다.

## 제공 범위

- Swagger UI: `/swagger-ui.html`
- OpenAPI JSON: `/v3/api-docs`
- Bearer JWT 보안 스키마(`bearerAuth`)
- 문서 엔드포인트의 익명 접근 허용

## 운영 방법

로컬에서는 기본값으로 문서를 활성화합니다. 외부에 문서를 노출하면 안 되는 환경은 아래 환경변수로 JSON과 UI를 모두 끕니다.

```text
SPRINGDOC_API_DOCS_ENABLED=false
SPRINGDOC_SWAGGER_UI_ENABLED=false
```

새 API는 Markdown 계약 문서도 계속 갱신하며, Swagger는 이를 대체하지 않습니다.
