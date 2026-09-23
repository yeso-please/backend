# CORS 정책

## 목적

브라우저에서 호출하는 API는 신뢰하는 프론트엔드 Origin만 허용한다. 인증 요청은 JWT를 `Authorization` 헤더로 보내므로 `allowCredentials=true`와 와일드카드 Origin을 함께 사용하지 않는다.

## 설정

`CORS_ALLOWED_ORIGINS` 환경변수에 쉼표로 구분한 정확한 Origin을 지정한다.

```text
CORS_ALLOWED_ORIGINS=https://app.example.com,https://staging.example.com
```

로컬 기본값은 `http://localhost:3000,http://localhost:5173`이다. Origin에는 경로를 넣지 않고 scheme·host·port만 넣는다.

## 허용 계약

- 경로: `/api/**`
- 메서드: `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `OPTIONS`
- 요청 헤더: `Authorization`, `Content-Type`, `Idempotency-Key`
- 노출 헤더: `Location`
- preflight cache: 1시간

목록에 없는 Origin의 요청은 CORS 응답 헤더를 받지 못한다. 새 웹 클라이언트를 배포할 때는 해당 Origin을 환경변수에 추가하고, 와일드카드로 우회하지 않는다.
