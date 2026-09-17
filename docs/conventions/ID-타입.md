# ID 타입

엔티티 PK, 연관관계 식별자, Service 파라미터, DTO의 내부 ID는 `Long`으로 통일합니다.

```java
TripResponse getTrip(Long tripId);
void deleteTrip(Long tripId);
```

현재 프로젝트의 JPA PK 구성과 일치하므로 새 기능에서도 `Integer`나 숫자를 담은 `String`을 사용하지 않습니다. OAuth provider의 사용자 ID처럼 외부 시스템이 문자열로 정의한 식별자는 예외입니다.
