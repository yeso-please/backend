# REST API 명세

REST API 명세는 OpenAPI의 구조를 사람이 읽기 쉬운 Markdown으로 유지합니다. 각 endpoint에는 path, HTTP method, 인증, 입력, 성공 응답, 알려진 실패 응답을 반드시 기록합니다.

구현보다 먼저 명세를 작성하고, Controller·DTO·예외 처리와 내용이 일치하도록 유지합니다. Swagger가 추가되면 이 문서를 대체하지 않고, 사람이 검토하는 API 계약과 자동 생성 문서의 기준을 맞춥니다.

## 파일과 이름

```text
docs/api/
├── README.md       개요·호출 주체·공통 오류·구현 체크리스트
├── auth.md         § 1  auth
├── profile.md      § 2  profile
├── trip.md         § 3~6 trip (context·invite·course·diary)
└── attraction.md   § 7  attraction
```

HTTP를 노출하는 모듈(BC)마다 파일 하나를 둡니다. endpoint는 Controller가 속한 모듈의 파일에 두고, 파일 안의 절은 `presentation` 하위 패키지를 따릅니다. 이 폴더는 구현 여부와 관계없는 MVP 목표 계약이며, 구현 진척은 README의 체크리스트로 표시합니다.

## endpoint 템플릿

절 머리에는 계약 상태(`agreed`·`draft`), 공통 응답 타입, 오류 코드 표를 둡니다. endpoint는 다음 모양입니다.

````markdown
### 5-3. 일정 편집

> `WORK-07` · `호출: 편집자` · `⬜ 미구현`

```
PATCH /api/courses/{tripId}/schedule
```

**Request Body**

```json
{"version": 3, "operations": [{"op": "REMOVE", "itemId": "a-105"}]}
```

| 필드 | 타입 | 필수 | 제약 |
|---|---|---|---|
| `version` | `number` | 예 | 직전에 받은 값 |

**Response `200 OK`** — `Course`

| 오류 | HTTP | code |
|---|---:|---|
| 버전 불일치 | 409 | `COURSE_VERSION_CONFLICT` |

**Side effects** — 무엇을 만들고 바꾸는지.

**구현과의 차이** — `🔧 변경 필요`일 때만.
````

- 배지: `WORK-nn` · `호출: 공개|회원|소유자|열람자|편집자|인증된 주체` · `✅ 구현|🔧 변경 필요|⬜ 미구현`. 호출 표기의 뜻은 `docs/api/README.md`에 있습니다.
- 구현 상태를 바꾸면 README 체크리스트의 같은 행도 함께 바꿉니다.
- 미결 항목은 본문에 흩어 두지 않고 파일 끝 "결정 필요" 표에 모읍니다.

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
