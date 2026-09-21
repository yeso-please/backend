# API 응답 형식

## 성공

성공 응답은 별도 래퍼 없이 DTO를 반환합니다. HTTP 상태 코드가 성공 여부를 표현하므로 `success` 필드를 중복해서 사용하지 않습니다.

```java
return ResponseEntity.ok(tripService.getTrip(tripId));
```

- 조회·수정: `200 OK`
- 생성: `201 Created`
- 삭제: `204 No Content`

## 실패

모든 실패 응답은 다음 형식을 사용합니다.

```json
{
  "timestamp": "2026-09-21T00:00:00Z",
  "status": 404,
  "code": "TRIP_NOT_FOUND",
  "message": "여행 계획을 찾을 수 없습니다.",
  "path": "/api/trips/1"
}
```

모든 실패는 `timestamp`, `status`, `code`, `message`, `path`를 반환합니다. 검증 실패에는 `fieldErrors` 배열도 반환합니다. 메시지는 사용자에게 보여줄 수 있는 한국어로 작성하며 SQL, 스택 트레이스, 내부 파일 경로는 응답에 포함하지 않습니다.

Service는 `ResponseEntity`를 반환하지 않고 예외를 던집니다. HTTP 변환은 `GlobalExceptionHandler`가 담당합니다.
