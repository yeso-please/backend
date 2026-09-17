# REST API 명세

REST API 명세는 OpenAPI의 구조를 사람이 읽기 쉬운 Markdown으로 유지합니다. 각 endpoint에는 path, HTTP method, 인증, 입력, 성공 응답, 알려진 실패 응답을 반드시 기록합니다.

구현보다 먼저 명세를 작성하고, Controller·DTO·예외 처리와 내용이 일치하도록 유지합니다. Swagger가 추가되면 이 문서를 대체하지 않고, 사람이 검토하는 API 계약과 자동 생성 문서의 기준을 맞춥니다.

## 파일과 이름

```text
docs/api/
├── README.md
├── auth.md
├── trips.md
└── attractions.md
```

도메인별로 파일을 나눕니다. endpoint가 많아지면 기능별 파일로 나누되, 같은 리소스의 생성·조회·수정·삭제는 가능한 한 함께 둡니다.

## endpoint 템플릿

````markdown
### POST /api/trips

- 상태: implemented
- 목적: 로그인 사용자의 여행 계획을 생성한다.
- 인증: Bearer JWT required
- 권한: 본인 리소스

#### Request

Headers:

| 이름 | 값 | 필수 |
|---|---|---|
| Authorization | Bearer `{accessToken}` | O |
| Content-Type | application/json | O |

Body:

```json
{
  "regionId": 1,
  "transport": "WALK"
}
```

#### Responses

##### 201 Created

```json
{
  "id": 1,
  "status": "CONFIRMED"
}
```

##### 400 Bad Request

```json
{
  "status": 400,
  "message": "입력값이 올바르지 않습니다."
}
```

##### 401 Unauthorized

인증 토큰이 없거나 유효하지 않다.

#### Side effects

- `trip_plans`에 1건을 생성한다.

#### Related

- [Trip feature](../features/trip-create.md)
- [API response convention](../conventions/API-응답-형식.md)
````

## 반드시 기록할 항목

- 실제 호출 path와 HTTP method
- 인증·인가 요구사항
- Path, query, header, body의 필드와 필수 여부
- 성공 상태 코드와 응답 예시
- 예상 가능한 400·401·403·404·409 응답
- 페이지네이션, 정렬, 필터, 기본값
- 데이터 변경이나 외부 API 호출 같은 side effect
- 멱등성, 재시도, 동시성 제약
- 관련 작업 명세와 ADR

## 변경 규칙

기존 응답 필드 삭제·이름 변경, 필수 필드 추가, 상태 코드 변경은 호환성에 영향을 주는 변경입니다. 작업 명세와 ADR에 영향 범위를 기록하고, 기존 클라이언트가 사용할 수 있는 전환 방법을 함께 적습니다.

단순한 설명·오타 수정은 API 버전을 올리지 않아도 되지만, 계약이 바뀌면 문서와 테스트를 같은 PR에서 변경합니다.
