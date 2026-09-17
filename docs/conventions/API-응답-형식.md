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
  "status": 404,
  "message": "여행 계획을 찾을 수 없습니다."
}
```

메시지는 사용자에게 보여줄 수 있는 한국어로 작성합니다. SQL, 스택 트레이스, 내부 파일 경로는 응답에 포함하지 않습니다. 기계적으로 구분할 코드가 실제로 필요해질 때만 `code` 필드를 추가합니다.

Service는 `ResponseEntity`를 반환하지 않고 예외를 던집니다. HTTP 변환은 `GlobalExceptionHandler`가 담당합니다.
