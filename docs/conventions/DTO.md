# DTO

DTO는 Java `record`로 작성합니다. Request와 Response는 역할이 다르므로 재사용하지 않습니다.

```java
public record CreateTripRequest(
        @NotNull Long regionId,
        @NotNull Transport transport
) {
}

public record TripResponse(
        Long id,
        TripPlanStatus status
) {
}
```

Request에만 검증 어노테이션을 붙이고 Controller에서 반드시 `@Valid`를 사용합니다.

```java
public ResponseEntity<TripResponse> create(
        @Valid @RequestBody CreateTripRequest request) {
    // ...
}
```

엔티티를 API 응답으로 직접 반환하지 않습니다. 비밀번호, 토큰 해시, 내부 상태가 노출되지 않도록 Response DTO에서 외부 공개 필드를 명시합니다. 비밀번호가 포함된 Request를 로그에 남길 때는 값을 마스킹합니다.

이름은 `CreateTripRequest`, `UpdateTripRequest`, `TripResponse`, `TripListResponse` 형식을 사용합니다.
