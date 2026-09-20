# 공통 오류 응답

- 상태: implemented
- 마지막 갱신일: 2026-09-20
- 구현: `shared.exception`, `shared.security`

Controller, validation, Security 계층은 모두 같은 JSON 모양을 사용한다.

```json
{
  "timestamp": "2026-09-20T10:00:00Z",
  "status": 400,
  "code": "COMMON_INVALID_REQUEST",
  "message": "요청 값이 올바르지 않습니다.",
  "path": "/api/example",
  "fieldErrors": [
    {
      "field": "startDate",
      "message": "필수 값입니다."
    }
  ]
}
```

## 공통 코드

| HTTP | code | 사용 시점 |
|---:|---|---|
| 400 | `COMMON_INVALID_REQUEST` | validation, JSON 파싱, 파라미터 변환, 지원하지 않는 method |
| 401 | `AUTH_UNAUTHENTICATED` | 인증이 없거나 유효하지 않음 |
| 403 | `AUTH_ACCESS_DENIED` | 인증됐지만 권한이 없음 |
| 500 | `COMMON_INTERNAL_ERROR` | 예상하지 못한 서버 오류 |

500 응답은 내부 exception 메시지와 stack trace를 노출하지 않는다. 전체 stack trace는 서버 로그에만 기록한다.

## 인증 사용자 주입

WORK-01의 JWT principal은 `AuthenticatedUserPrincipal`을 구현한다. Controller는 SecurityContext를 직접 읽지 않고 다음 계약을 사용한다.

```java
ResponseEntity<?> endpoint(@CurrentUserId Long userId) {
    // ...
}
```

인증 principal이 없으면 `AUTH_UNAUTHENTICATED`로 변환된다.
