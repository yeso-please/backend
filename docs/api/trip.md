# 여행 API

`trip` 모듈이 노출하는 API다. 절은 `presentation` 하위 패키지(`context`, `invite`, `course`, `diary`)를 따른다. 호출 주체 표기는 [README](README.md#호출-주체와-인증)를 따른다.

**여행 정책 (2026-09-24)** — [결정](../product.md#참여와-권한)

- 여행은 **로그인한 회원**만 만들고 참여한다. 초대받은 사람도 가입·로그인과 최초 설문을 마친 뒤 수락한다.
- 여행을 만들면 그 기간을 바로 차지한다. 내가 만들었거나 참여 중인 여행과 겹치는 날짜는 선택할 수 없고, 쓰려면 기존 여행에서 먼저 탈퇴한다.
- 만든 뒤 시작일·종료일은 바꿀 수 없다. 날짜별 장소와 방문 순서만 바꾼다. **확정 단계는 없다.**
- 생성자와 참여자는 **동등한 권한**이다. 코스 수정, 친구 초대, 탈퇴를 똑같이 한다. 삭제는 없고 개인 탈퇴만 있으며, 마지막 참여자가 탈퇴하면 여행이 삭제된다.
- 추천은 **요청한 사람의 취향**으로 한다. 최초 코스는 생성자만 만들고(생성자가 탈퇴했으면 남은 참여자), 이후 재생성·대체 후보는 누른 사람 기준이다.
- 참여하지 않은 사람(비회원 포함)에게는 공유 링크로 **이미 만들어진 코스의 조회만** 허용한다(4-13). share session은 `/api/shared/**`에서만 인정한다(2026-09-25).
- **종료일이 지난 여행은 읽기 전용**이다. 지역·코스·이동수단 변경, 초대 발급·수락은 `409 TRIP_ENDED`. 조회, 공유 링크, 탈퇴는 된다. "종료일이 지났다"는 `endDate < 오늘`(Asia/Seoul).

---

# 3. 여행 context·지역

- 계약 상태: agreed
- 날짜: 시작일과 `nights`(0~6)를 고르면 `endDate = startDate + nights`, `days = nights + 1`.

**`TripContext`** — 3-3·3-4·3-5의 응답

```json
{
  "id": 42,
  "startDate": "2026-10-10",
  "endDate": "2026-10-12",
  "nights": 2,
  "transport": "WALK",
  "originLat": 37.5665,
  "originLng": 126.978,
  "regionSigCd": null,
  "regionSelection": null,
  "scheduleDensity": null,
  "hasCourse": false,
  "version": 0,
  "dayWindows": [
    {"dayIndex": 0, "date": "2026-10-10", "windowStart": "12:00", "windowEnd": "20:00"},
    {"dayIndex": 1, "date": "2026-10-11", "windowStart": "09:00", "windowEnd": "20:00"},
    {"dayIndex": 2, "date": "2026-10-12", "windowStart": "09:00", "windowEnd": "17:00"}
  ]
}
```

| 필드 | 설명 |
|---|---|
| `transport` | `WALK` \| `CAR` \| `PUBLIC_TRANSIT` |
| `regionSigCd`, `regionSelection`, `scheduleDensity` | 지역을 정하기(3-7) 전에는 `null`. `regionSelection`은 `RANDOM` \| `CONDITIONAL` \| `MANUAL`. `scheduleDensity`는 3-7에서 정하고, 코스가 생긴 뒤에는 5-1에서만 바뀐다 |
| `hasCourse` | 코스에 일정 항목이 있는지. 3-7 `replaceCourse`로 비운 코스는 `false` |
| `version` | 여행의 낙관적 잠금 값(`trip_plans.version`). context 수정(3-5), 지역 정하기(3-7), 코스 생성·재생성(5-1)·편집(5-3)이 모두 이 값 하나를 올린다. 5장 `Course.version`과 같은 수다 |
| `dayWindows` | 날짜별 기본 가용 시간. 당일치기 12:00~20:00, 여러 날이면 첫날 12:00~20:00·중간 09:00~20:00·마지막 날 09:00~17:00 |

**오류 코드**

| code | HTTP | 상황 |
|---|---:|---|
| `TRIP_INVALID_START_DATE` | 400 | 시작일이 오늘이거나 과거 |
| `TRIP_INVALID_NIGHTS` | 400 | `nights`가 0~6 밖 |
| `TRIP_INVALID_TRANSPORT` | 400 | 허용하지 않는 이동수단 |
| `TRIP_INVALID_ORIGIN` | 400 | 출발지 lat·lng 중 하나만 있거나 범위 밖 |
| `TRIP_NOT_FOUND` | 404 | 없거나 내가 참여하지 않은 여행(구분하지 않음) |
| `TRIP_DATE_OVERLAP` | 409 | 내가 만들었거나 참여 중인 여행과 날짜가 겹침. `details.conflicts` 포함 |
| `TRIP_CONTEXT_LOCKED` | 409 | 코스가 있는 여행의 지역·밀도를 `replaceCourse: true` 없이 바꾸려 함(3-7). 메시지는 확인 후 다시 보내라고 안내한다 |
| `TRIP_VERSION_CONFLICT` | 409 | 보낸 `version`이 현재 값과 다름(3-5·3-7·5-1·5-3 공통) |
| `TRIP_ENDED` | 409 | 종료일이 지난 여행을 바꾸려 함 |
| `ONBOARDING_REQUIRED` | 409 | 최초 설문을 마치지 않고 여행을 만들려 함 |
| `DRAW_INVALID_MODE` | 400 | `mode` 값 오류 |
| `DRAW_NO_CONDITION_SELECTED` | 400 | `CONDITIONAL`인데 조건이 비었음 |
| `REGION_NOT_FOUND` | 404 | `MANUAL`로 보낸 `sigCd`가 없는 지역 |
| `DRAW_REGION_NOT_ELIGIBLE` | 422 | `MANUAL`로 고른 지역이 이 일수·밀도로 코스를 만들 수 없음 |
| `DRAW_NO_ELIGIBLE_REGION` | 422 | 추첨 가능한 지역이 없음 |

`TRIP_DATE_OVERLAP` 예시:

```json
{
  "status": 409,
  "code": "TRIP_DATE_OVERLAP",
  "message": "이미 있는 여행과 날짜가 겹칩니다.",
  "details": {"conflicts": [{"tripId": 8, "title": "제주 여행", "startDate": "2026-10-11", "endDate": "2026-10-13"}]}
}
```

---

### 3-1. 선택 불가 날짜

> `호출: 회원` · `✅ 구현`

```
GET /api/trips/unavailable-dates?from=2026-10-01&to=2026-12-31
```

| Query | 필수 | 설명 |
|---|---|---|
| `from`, `to` | 예 | 이 범위와 겹치는 내 여행(만든 것·참여 중인 것)만 반환 |

**Response `200 OK`** — 캘린더 비활성 표시용

```json
[{"tripId": 1, "startDate": "2026-10-01", "endDate": "2026-10-04"}]
```

---

### 3-2. 날짜 중복 미리 확인

> `호출: 회원` · `🔧 변경 필요`

```
POST /api/trips/context/check
```

저장하지 않고 중복 여부만 본다.

**Request Body**

```json
{"startDate": "2026-10-10", "nights": 2}
```

**Response `200 OK`**

```json
{
  "available": false,
  "endDate": "2026-10-12",
  "conflicts": [{"tripId": 1, "title": "10월 1일부터 3박 4일 여행", "startDate": "2026-10-01", "endDate": "2026-10-04"}],
  "dayWindows": ["...TripContext.dayWindows와 같음"],
  "eligibleRegionCount": 12
}
```

`available=false`여도 200이다. 캘린더에 바로 표시하기 위한 API이기 때문이다.

- `eligibleRegionCount`: 요청한 `nights`의 일수로 RELAXED 기준 추첨 가능한 지역 수(7-1의 `eligibleCount`와 같은 계산). 0이면 프론트는 "이 기간에 맞는 지역이 아직 없어요"라고 경고한다. 만들기를 막지는 않는다.
- `conflicts[].title`: 코스 제목이 없으면 서버가 대체 제목 `M월 D일부터 N박 N+1일 여행`(당일치기는 `M월 D일 당일 여행`)을 준다(예: `10월 10일부터 2박 3일 여행`).

| 오류 | HTTP | code |
|---|---:|---|
| 시작일·박수 규칙 위반 | 400 | `TRIP_INVALID_START_DATE`, `TRIP_INVALID_NIGHTS` |

**구현과의 차이** — `eligibleRegionCount`가 없다(#41).

---

### 3-3. 여행 만들기

> `호출: 회원` · `✅ 구현`

```
POST /api/trips
```

**Request Body**

```json
{"startDate": "2026-10-10", "nights": 2, "transport": "WALK", "originLat": 37.5665, "originLng": 126.9780}
```

| 필드 | 타입 | 필수 | 제약 |
|---|---|---|---|
| `startDate` | `string` | 예 | 내일 이후 |
| `nights` | `number` | 예 | 0~6 |
| `transport` | `string` | 예 | `WALK` \| `CAR` \| `PUBLIC_TRANSIT` |
| `originLat`, `originLng` | `number` | 아니오 | 함께 주거나 함께 생략. 거리 조건 추첨에 쓴다 |

**Response `201 Created`** — `TripContext`

| 오류 | HTTP | code |
|---|---:|---|
| 입력 규칙 위반 | 400 | `TRIP_INVALID_*` |
| 최초 설문 미완료 | 409 | `ONBOARDING_REQUIRED` |
| 내 여행과 겹침 | 409 | `TRIP_DATE_OVERLAP` |

**Side effects** — `trip_plans` 1건, `trip_participants`에 만든 사람 1건. 같은 사용자의 동시 생성 요청은 직렬화되어 겹치는 두 여행이 함께 생기지 않는다.

---

### 3-4. 여행 context 조회

> `호출: 참여자` · `✅ 구현`

```
GET /api/trips/{tripId}/context
```

**Response `200 OK`** — `TripContext`. `version`은 코스 편집(5-3)에도 그대로 쓰는 여행 버전이다.

| 오류 | HTTP | code |
|---|---:|---|
| 없거나 참여자가 아님 | 404 | `TRIP_NOT_FOUND` |

---

### 3-5. 이동수단·출발지 수정

> `호출: 참여자` · `🔧 변경 필요`

```
PATCH /api/trips/{tripId}/context
```

시작일·종료일은 바꿀 수 없다. 이동수단과 출발지는 코스가 있어도 바꿀 수 있다. 코스가 있으면 5-3 편집 뒤처럼 서버가 모든 날의 이동시간·시작 시각을 다시 계산한다. 다시 계산한 결과가 가용 시간을 넘기면 `422 COURSE_SCHEDULE_INFEASIBLE`로 거부하고 아무것도 바꾸지 않는다.

**Request Body**

```json
{"transport": "CAR", "originLat": 37.5665, "originLng": 126.9780, "version": 0}
```

| 필드 | 타입 | 필수 | 제약 |
|---|---|---|---|
| `transport` | `string` | 아니오 | 3-3과 같음 |
| `originLat`, `originLng` | `number` | 아니오 | 3-3과 같음. 둘 다 `null`이면 출발지 삭제 |
| `version` | `number` | 예 | 직전에 받은 여행 `version`(`TripContext`·`Course` 어느 쪽 값이든 같다) |

`startDate`·`nights`를 보내면 `400 COMMON_INVALID_REQUEST`다.

**Response `200 OK`** — `TripContext`, 증가한 `version`

| 오류 | HTTP | code |
|---|---:|---|
| 여행 종료일이 지남 | 409 | `TRIP_ENDED` |
| 입력 규칙 위반 | 400 | `TRIP_INVALID_*`, `COMMON_INVALID_REQUEST` |
| 없거나 참여자가 아님 | 404 | `TRIP_NOT_FOUND` |
| 버전 불일치 | 409 | `TRIP_VERSION_CONFLICT` |
| 코스 재계산 결과가 가용 시간 초과 | 422 | `COURSE_SCHEDULE_INFEASIBLE` |

**구현과의 차이** — 코스가 있으면 `409 TRIP_CONTEXT_LOCKED`로 거부한다. 계약은 변경을 허용하고 코스 이동시간을 다시 계산한다.

---

### 3-6. 내 여행 목록

> `호출: 회원` · `✅ 구현`

```
GET /api/trips?period=UPCOMING
```

| Query | 필수 | 설명 |
|---|---|---|
| `period` | 아니오 | `UPCOMING`(종료일이 오늘 이후) \| `PAST`(종료일이 지남). 생략하면 전체 |

**Response `200 OK`** — 내가 만들었거나 참여 중인 여행, `startDate` 오름차순. 캘린더와 "내 여행" 목록이 함께 쓴다.

```json
[
  {
    "tripId": 42,
    "title": "경주, 신라의 시간을 걷는 2일",
    "regionSigCd": "47130",
    "regionName": "경상북도 경주시",
    "startDate": "2026-10-10",
    "endDate": "2026-10-12",
    "nights": 2,
    "participants": [{"userId": 1, "nickname": "나"}, {"userId": 12, "nickname": "여행친구"}],
    "hasCourse": true,
    "myDiaryId": null,
    "updatedAt": "2026-10-01T21:00:00"
  }
]
```

- `title`은 코스 제목이다. 코스 제목이 없으면 서버가 대체 제목 `M월 D일부터 N박 N+1일 여행`(당일치기는 `M월 D일 당일 여행`)을 준다(예: `10월 10일부터 2박 3일 여행`). `null`이 아니다.
- `myDiaryId`는 이 여행에 내가 쓴 여행기 ID, 없으면 `null`이다([6장](#6-여행기사진-지도)).
- `period`: `UPCOMING`은 `endDate >= 오늘`, `PAST`는 `endDate < 오늘`(Asia/Seoul).

---

### 3-7. 지역 정하기

> `호출: 참여자` · `⬜ 미구현`

```
POST /api/trips/{tripId}/region
```

랜덤 추첨, 조건 추첨, 지도에서 직접 선택이 모두 이 API다. 다시 뽑기도 같은 요청을 다시 보낸다. 이전 결과를 제외하지 않으므로 같은 지역이 연속으로 나올 수 있다.

**코스가 있으면(`hasCourse: true`) `replaceCourse: true`를 보내야 한다.** 없으면 `409 TRIP_CONTEXT_LOCKED`다. 프론트는 먼저 "코스를 비우고 다시 만들어요"로 확인을 받고 `replaceCourse: true`로 다시 보낸다. 이때 지역·밀도를 바꾸고 코스를 **비운다**(일정 항목·식당 선택 삭제). 코스 기록과 최초 생성 완료 표시는 남으므로 다음 5-1은 재생성이며 참여자 누구나 할 수 있다.

**Request Body**

```json
{"mode": "CONDITIONAL", "conditions": ["DISTANCE", "MY_TASTE"], "sigCd": null, "scheduleDensity": "RELAXED", "replaceCourse": false, "version": 0}
```

| 필드 | 타입 | 필수 | 제약 |
|---|---|---|---|
| `mode` | `string` | 예 | `RANDOM` \| `CONDITIONAL` \| `MANUAL` |
| `conditions` | `string[]` | `CONDITIONAL`이면 예 | `DISTANCE` \| `MY_TASTE` 중 1개 이상 |
| `sigCd` | `string` | `MANUAL`이면 예 | 지도에서 고른 지역 |
| `scheduleDensity` | `string` | 아니오 | `RELAXED` \| `PACKED`. 생략하면 여행에 이미 밀도가 있으면 그 값, 없으면(`null`) 요청자의 최신 온보딩 값 |
| `replaceCourse` | `boolean` | 아니오 | 기본 `false`. 코스가 있을 때 `true`여야 한다. 코스가 없으면 무시한다 |
| `version` | `number` | 예 | 직전에 받은 여행 `version`(`TripContext`·`Course` 공통). 다른 참여자가 먼저 바꿨으면 409 |

**규칙**

- 후보는 이 여행의 일수·밀도로 코스를 만들 수 있는 지역이다([7-1](attraction.md#7-1-지역-목록)의 `drawEligible`). `MANUAL`도 이 조건을 만족해야 한다.
- `RANDOM`: 후보마다 같은 확률.
- `CONDITIONAL`: `weight = 1 + 적용된 조건 점수의 합`으로 확률 추첨한다. 최고점 선택이 아니다.
  - `DISTANCE`: `1 - clamp(출발지~지역 중심 거리km / 300, 0, 1)`
  - `MY_TASTE`: 지역의 추천 가능 관광지 N개와 **요청자** 취향 벡터의 코사인 유사도 중 상위 min(5,N)개 평균 `c`를 `(c + 1) / 2`로 0~1에 맞춘다
- 데이터가 없는 조건은 무시하고 `ignoredConditions`에 이유를 담는다. 전부 무시되면 균등 추첨과 경고를 반환한다.
- 코스가 있는데 `replaceCourse`가 `true`가 아니면 추첨·선택 전에 `409 TRIP_CONTEXT_LOCKED`로 거부한다.
- 프론트는 7-1을 부를 때 여행의 `scheduleDensity`를 넘긴다(없으면 생략).

**Response `200 OK`**

```json
{
  "tripId": 42,
  "regionSigCd": "47130",
  "province": "경상북도",
  "city": "경주시",
  "regionSelection": "CONDITIONAL",
  "scheduleDensity": "RELAXED",
  "appliedConditions": ["MY_TASTE"],
  "ignoredConditions": [{"condition": "DISTANCE", "reason": "ORIGIN_MISSING"}],
  "candidateCount": 187,
  "warnings": [],
  "version": 1
}
```

| `ignoredConditions[].reason` | 뜻 |
|---|---|
| `ORIGIN_MISSING` | 여행에 출발지가 없음 |
| `TASTE_NOT_READY` | 요청자의 취향 벡터가 없음 |

| `warnings[]` | 뜻 |
|---|---|
| `ALL_CONDITIONS_IGNORED` | 모든 조건이 무시되어 균등 추첨함 |

| 오류 | HTTP | code |
|---|---:|---|
| 여행 종료일이 지남 | 409 | `TRIP_ENDED` |
| 모드·조건 오류 | 400 | `DRAW_INVALID_MODE`, `DRAW_NO_CONDITION_SELECTED` |
| 없거나 참여자가 아님 | 404 | `TRIP_NOT_FOUND` |
| 없는 지역(`MANUAL`) | 404 | `REGION_NOT_FOUND` |
| 버전 불일치 | 409 | `TRIP_VERSION_CONFLICT` |
| 코스가 있는데 `replaceCourse` 없음 | 409 | `TRIP_CONTEXT_LOCKED` |
| 고른 지역이 부적격 | 422 | `DRAW_REGION_NOT_ELIGIBLE` |
| 후보 0개 | 422 | `DRAW_NO_ELIGIBLE_REGION` |

**Side effects** — 여행의 지역·선택 방식·밀도를 바꾸고 `version`을 올린다. `replaceCourse: true`면 코스 항목·식당 선택을 비운다(코스 기록과 최초 생성 완료 표시는 남는다).

---

### 3-8. 참여자 목록

> `호출: 참여자` · `✅ 구현`

```
GET /api/trips/{tripId}/participants
```

**Response `200 OK`** — 참여한 순서

```json
[
  {"userId": 1, "nickname": "만든사람", "isCreator": true, "joinedAt": "2026-10-01T10:00:00"},
  {"userId": 12, "nickname": "여행친구", "isCreator": false, "joinedAt": "2026-10-01T12:00:00"}
]
```

`isCreator`는 표시용일 뿐 권한 차이는 없다. 참여자의 설문 답변·MBTI는 다른 참여자에게 노출하지 않는다.

---

### 3-9. 여행 탈퇴

> `호출: 참여자` · `✅ 구현`

```
DELETE /api/trips/{tripId}/participants/me
```

"내 여행 목록에서 삭제"가 이 API다.

**Response `204 No Content`**

- 내 캘린더와 목록에서 여행이 사라지고 그 기간을 다시 쓸 수 있다.
- 다른 참여자의 여행은 그대로다. 코스도 바꾸지 않는다.
- **마지막 참여자가 탈퇴하면 여행과 코스·초대·공유 링크를 삭제한다.**
- 내가 쓴 여행기는 남는다([6장](#6-여행기사진-지도)).
- 같은 여행의 탈퇴는 여행 단위로 직렬화된다. 마지막 두 명이 동시에 나가도 여행이 참여자 없이 남지 않는다.
- 코스의 `updatedBy`·`tasteBasis`에 남은 탈퇴자는 닉네임 그대로 표시한다.

| 오류 | HTTP | code |
|---|---:|---|
| 없거나 참여자가 아님 | 404 | `TRIP_NOT_FOUND` |

---

# 4. 초대·공유

- 계약 상태: agreed
- 초대는 **회원을 여행 참여자로 들이는** 것이고, 공유는 **참여하지 않은 사람에게 읽기 전용으로 보여주는** 것이다.
- 초대 경로는 두 가지다. ① 초대 링크를 카카오톡 공유나 복사로 보낸다(서버는 메시지를 보내지 않는다). ② 친구 목록에서 바로 초대하면 친구의 "받은 초대"에 뜬다(푸시 알림 없음).
- 참여자는 여행마다 **최대 8명**이다. 8명이면 수락이 `409 TRIP_FULL`이다. 초대 발급·친구 초대 보내기는 막지 않는다. 내보내기(강퇴) 기능은 없다.
- 수락 조건: 로그인, 최초 설문 완료, 참여자 8명 미만, 수락자의 기존 여행과 날짜가 겹치지 않음. 수락하면 같은 여행이 수락자의 캘린더에도 보인다. 일정은 복사하지 않는다.

**token 종류**

| 용도 | 접두사 | 전달 | 기본 수명 |
|---|---|---|---|
| 초대 링크 | `iv_` | URL path | 7일 (1~30일), 폐기 전까지 여러 명 수락 가능 |
| 공유 링크 | `sl_` | URL path | 7일 (1~30일) |
| share session | `ss_` | HttpOnly cookie `share_session`, `Path=/api/shared` | 2시간 |

256bit CSPRNG, DB에는 SHA-256 해시만 둔다. 원문은 발급 응답에서 한 번만 준다. 용도가 다른 token은 `TOKEN_AUDIENCE_MISMATCH`로 걸러진다.

**오류 코드**

| code | HTTP | 상황 |
|---|---:|---|
| `INVALID_EXPIRES_IN_DAYS` | 400 | `expiresInDays`가 1~30 밖 |
| `TOKEN_AUDIENCE_MISMATCH` | 400 | 다른 용도의 token |
| `SHARE_SESSION_INVALID` | 401 | share session cookie 없음·만료 |
| `TRIP_NOT_FOUND` | 404 | 없거나 참여자가 아닌 여행 |
| `INVITE_NOT_FOUND` | 404 | 없는 초대이거나 이 여행·나에게 온 초대가 아님 |
| `SHARE_LINK_NOT_FOUND` | 404 | 없는 공유 링크이거나 이 여행의 링크가 아님 |
| `FRIEND_NOT_FOUND` | 404 | 친구가 아닌 사용자를 초대 |
| `ONBOARDING_REQUIRED` | 409 | 최초 설문을 마치지 않고 수락 |
| `TRIP_DATE_OVERLAP` | 409 | 수락자의 기존 여행과 날짜가 겹침. `details.conflicts` |
| `INVITE_ALREADY_HANDLED` | 409 | 이미 수락·거절·취소된 친구 초대를 처리·취소, 또는 이미 참여 중인 친구를 초대 |
| `TRIP_ENDED` | 409 | 종료일이 지난 여행에 초대·수락 |
| `TRIP_FULL` | 409 | 참여자가 이미 8명인 여행에 수락(4-5·4-8) |
| `INVITE_EXPIRED`, `INVITE_REVOKED` | 410 | 초대 링크 만료·폐기 |
| `SHARE_LINK_EXPIRED`, `SHARE_LINK_REVOKED` | 410 | 공유 링크 만료·폐기 |

**`LinkSummary`** — 초대·공유 링크 목록의 원소

```json
{"id": 1, "expiresAt": "2026-09-30T12:00:00", "revoked": false, "createdBy": {"userId": 1, "nickname": "만든사람"}, "createdAt": "2026-09-23T12:00:00"}
```

---

### 4-1. 초대 링크 발급

> `호출: 참여자` · `✅ 구현`

```
POST /api/trips/{tripId}/invites
```

**Request Body**

```json
{"expiresInDays": 7}
```

| 필드 | 타입 | 필수 | 제약 |
|---|---|---|---|
| `expiresInDays` | `number` | 아니오 | 1~30. 기본 7 |

**Response `201 Created`**

```json
{"id": 1, "token": "iv_3Qk9...", "expiresAt": "2026-09-30T12:00:00", "createdAt": "2026-09-23T12:00:00"}
```

| 오류 | HTTP | code |
|---|---:|---|
| 여행 종료일이 지남 | 409 | `TRIP_ENDED` |
| 기간 오류 | 400 | `INVALID_EXPIRES_IN_DAYS` |
| 없거나 참여자가 아님 | 404 | `TRIP_NOT_FOUND` |

---

### 4-2. 초대 링크 목록

> `호출: 참여자` · `✅ 구현`

```
GET /api/trips/{tripId}/invites
```

**Response `200 OK`** — `LinkSummary[]`, 최신순

---

### 4-3. 초대 링크 폐기

> `호출: 참여자` · `✅ 구현`

```
DELETE /api/trips/{tripId}/invites/{inviteId}
```

**Response `204 No Content`** — 이후 수락을 막는다. 이미 참여한 사람은 그대로다.

| 오류 | HTTP | code |
|---|---:|---|
| 없거나 다른 여행의 초대 | 404 | `INVITE_NOT_FOUND` |

---

### 4-4. 초대 링크 미리보기

> `호출: 공개` · `✅ 구현`

```
GET /api/invites/{token}
```

로그인 전에도 어떤 여행인지 보여주는 최소 정보.

**Response `200 OK`**

```json
{"valid": true, "title": "10월 3일부터 2박 3일 여행", "startDate": "2026-10-03", "endDate": "2026-10-05", "regionName": "경상북도 경주시", "inviterNickname": "여행친구", "participantCount": 2}
```

코스·참여자 이름 목록은 노출하지 않는다. `title`은 코스 제목, 없으면 3-6과 같은 대체 제목이다.

| 오류 | HTTP | code |
|---|---:|---|
| 다른 용도의 token | 400 | `TOKEN_AUDIENCE_MISMATCH` |
| 없는 token | 404 | `INVITE_NOT_FOUND` |
| 만료·폐기 | 410 | `INVITE_EXPIRED`, `INVITE_REVOKED` |

---

### 4-5. 초대 링크 수락

> `호출: 회원` · `✅ 구현`

```
POST /api/invites/{token}/accept
```

요청 body 없음. 프론트는 비로그인이면 로그인·가입으로, 설문 미완료면 설문으로 보낸 뒤 이 요청을 보낸다.

**Response `201 Created`** — 참여한 여행의 `TripContext`. 이미 참여 중이면 `200 OK`로 같은 응답(멱등).

| 오류 | HTTP | code |
|---|---:|---|
| 여행 종료일이 지남 | 409 | `TRIP_ENDED` |
| 다른 용도의 token | 400 | `TOKEN_AUDIENCE_MISMATCH` |
| 없는 token | 404 | `INVITE_NOT_FOUND` |
| 설문 미완료 | 409 | `ONBOARDING_REQUIRED` |
| 내 여행과 날짜 겹침 | 409 | `TRIP_DATE_OVERLAP` |
| 참여자 8명 | 409 | `TRIP_FULL` |
| 만료·폐기 | 410 | `INVITE_EXPIRED`, `INVITE_REVOKED` |

**Side effects** — `trip_participants`에 1건. 같은 사용자의 동시 수락·여행 생성은 직렬화되어 겹치는 두 여행에 동시에 들어가지 않는다. 정원 검사는 여행 단위로 직렬화해 동시 수락으로 8명을 넘지 않는다.

---

### 4-6. 친구 초대

> `호출: 참여자` · `⬜ 미구현`

```
POST /api/trips/{tripId}/friend-invites
```

**Request Body**

```json
{"friendUserId": 12}
```

**Response `201 Created`**

```json
{"id": 9, "tripId": 42, "invitee": {"userId": 12, "nickname": "여행친구"}, "status": "PENDING", "createdAt": "2026-10-01T12:00:00"}
```

- `status`: `PENDING`(대기), `ACCEPTED`, `DECLINED`, `CANCELLED`(4-15로 취소) 중 하나.
- 대기 중인 초대는 **(여행, 초대받는 사람)마다 하나**다. 누가 보냈든 대기 중인 초대가 이미 있으면 새로 만들지 않고 기존 초대를 `200 OK`로 돌려준다(멱등).
- 이미 참여 중인 친구면 `409 INVITE_ALREADY_HANDLED`다.
- `DECLINED`·`CANCELLED` 뒤에는 다시 초대할 수 있다. 새 초대(`201`)를 만든다.
- 친구의 기존 여행과 날짜가 겹쳐도 초대는 보낼 수 있다. 겹침은 수락(4-8) 때 `409 TRIP_DATE_OVERLAP`으로 막는다.
- 친구 관계는 **보낼 때만** 확인한다. 보낸 뒤 친구를 끊어도(2-10) 대기 중인 초대는 남고 수락할 수 있다.
- 친구가 초대 링크(4-5)로 먼저 참여하면 대기 중인 친구 초대는 `ACCEPTED`로 바뀌고 받은 초대 목록에서 사라진다.

**구현 메모** — 친구 초대는 trip이 소유하는 새 테이블(예: `trip_friend_invitations`: `trip_plan_id`, `inviter_user_id`, `invitee_user_id`, `status`, 처리 시각, `(trip_plan_id, invitee_user_id) WHERE status = 'PENDING'` 부분 unique)에 둔다. token 기반인 `trip_invitations`(`token_hash NOT NULL`)를 재사용하지 않는다. 친구 여부는 trip이 profile 테이블을 직접 읽지 않고 profile `application`의 조회 계약(예: `FriendQueryService.areFriends(userId, otherUserId)`)으로 확인한다([모듈·의존성](../conventions/모듈-의존성.md)).

| 오류 | HTTP | code |
|---|---:|---|
| 여행 종료일이 지남 | 409 | `TRIP_ENDED` |
| 이미 참여 중 | 409 | `INVITE_ALREADY_HANDLED` |
| 친구가 아님 | 404 | `FRIEND_NOT_FOUND` |
| 없거나 참여자가 아님 | 404 | `TRIP_NOT_FOUND` |

---

### 4-7. 받은 초대 목록

> `호출: 회원` · `⬜ 미구현`

```
GET /api/me/trip-invites
```

**Response `200 OK`** — 대기 중인 친구 초대, 최신순

```json
[
  {"id": 9, "trip": {"tripId": 42, "title": "10월 10일부터 2박 3일 여행", "startDate": "2026-10-10", "endDate": "2026-10-12", "regionName": "경상북도 경주시"},
   "inviter": {"userId": 1, "nickname": "만든사람"}, "dateConflict": false, "createdAt": "2026-10-01T12:00:00"}
]
```

`dateConflict`가 `true`면 프론트는 수락 버튼을 비활성화하고 이유를 보여준다. `trip.title`은 코스 제목, 없으면 3-6과 같은 대체 제목이다. 취소(`CANCELLED`)된 초대는 목록에서 사라진다.

---

### 4-8. 받은 초대 수락·거절

> `호출: 회원(초대받은 사람)` · `⬜ 미구현`

```
POST /api/me/trip-invites/{id}/accept
POST /api/me/trip-invites/{id}/decline
```

**Response** — 수락은 `201 Created` + 참여한 여행의 `TripContext`, 거절은 `204 No Content`.

| 오류 | HTTP | code |
|---|---:|---|
| 여행 종료일이 지남 | 409 | `TRIP_ENDED` |
| 없거나 나에게 온 초대가 아님 | 404 | `INVITE_NOT_FOUND` |
| 이미 처리됨 | 409 | `INVITE_ALREADY_HANDLED` |
| 설문 미완료(수락) | 409 | `ONBOARDING_REQUIRED` |
| 내 여행과 날짜 겹침(수락) | 409 | `TRIP_DATE_OVERLAP` |
| 참여자 8명(수락) | 409 | `TRIP_FULL` |

취소된 초대를 수락·거절하면 `409 INVITE_ALREADY_HANDLED`다. 초대한 사람이 그 사이 탈퇴하거나 친구를 끊어도 초대는 유효하다. 여행이 삭제되면 초대도 함께 삭제된다. 종료일이 지난 여행의 초대는 수락할 수 없고(`409 TRIP_ENDED`) 거절은 된다.

---

### 4-9. 공유 링크 발급

> `호출: 참여자` · `✅ 구현`

```
POST /api/courses/{tripId}/share-links
```

참여하지 않은 사람에게 코스를 **읽기 전용**으로 보여주는 링크. 코스를 만드는 중에도 공유할 수 있다.

**Request Body**

```json
{"expiresInDays": 7}
```

**Response `201 Created`**

```json
{"id": 1, "token": "sl_7Hs4...", "expiresAt": "2026-09-30T12:00:00", "createdAt": "2026-09-23T12:00:00"}
```

| 오류 | HTTP | code |
|---|---:|---|
| 기간 오류 | 400 | `INVALID_EXPIRES_IN_DAYS` |
| 없거나 참여자가 아님 | 404 | `TRIP_NOT_FOUND` |

---

### 4-10. 공유 링크 목록

> `호출: 참여자` · `✅ 구현`

```
GET /api/courses/{tripId}/share-links
```

**Response `200 OK`** — `LinkSummary[]`, 최신순

---

### 4-11. 공유 링크 폐기

> `호출: 참여자` · `✅ 구현`

```
DELETE /api/courses/{tripId}/share-links/{linkId}
```

**Response `204 No Content`** — 이미 발급된 share session도 즉시 무효다.

| 오류 | HTTP | code |
|---|---:|---|
| 없거나 다른 여행의 링크 | 404 | `SHARE_LINK_NOT_FOUND` |

---

### 4-12. 공유 링크 열기

> `호출: 공개` · `✅ 구현`

```
GET /api/shared/courses/{token}
```

URL의 token을 HttpOnly cookie로 바꾸고 token 없는 URL로 보낸다. 브라우저 기록·referrer·접근 로그에 token이 남지 않게 하기 위해서다.

**Response `303 See Other`**

```text
Location: /api/shared/courses
Set-Cookie: share_session=ss_...; HttpOnly; SameSite=Lax; Path=/api/shared; Max-Age=7200
```

`Path=/api/shared`인 이유: share session은 `/api/shared/**`에서만 인정한다. 비회원 뷰어는 지역 카드·지도·관광지 API(7장)를 부르지 않고 4-13 응답만으로 코스 화면을 그린다(2026-09-25).

**프론트 흐름** — 공유 URL은 프론트 라우트(`/shared/{token}`)다. 프론트가 이 API를 `fetch(…, {credentials: 'include'})`로 부르면 브라우저가 303을 따라가 4-13의 JSON을 받는다. 이후 화면 URL에는 token을 남기지 않는다(`history.replaceState`).

| 오류 | HTTP | code |
|---|---:|---|
| 다른 용도의 token | 400 | `TOKEN_AUDIENCE_MISMATCH` |
| 없는 token | 404 | `SHARE_LINK_NOT_FOUND` |
| 만료·폐기 | 410 | `SHARE_LINK_EXPIRED`, `SHARE_LINK_REVOKED` |

마지막 참여자가 탈퇴해 여행이 삭제되면(3-9) 공유 링크 행도 함께 삭제되므로 이 API는 `404 SHARE_LINK_NOT_FOUND`다.

---

### 4-13. 공유 코스 조회

> `호출: 공유 링크 소지자` · `🔧 변경 필요`

```
GET /api/shared/courses
```

**Response `200 OK`** — [5장](#5-코스)의 `Course`, `myRole: "VIEWER"`. 공유 링크 소지자가 코스를 보는 유일한 경로다. 뷰어는 7장(지역 카드·지도 핀·관광지 상세)을 부를 수 없으므로 **이 응답만으로 코스 화면(지도 마커·항목 카드)을 그린다.** 관광지 항목의 `name`·`category`·`thumbnailUrl`·`address`·`lat`·`lng`, 식사 항목의 `restaurant` 스냅샷이 그 용도다. 참여자 닉네임이 드러나는 `tasteBasis`·`updatedBy`는 `null`이다.

코스가 아직 없으면 404가 아니라 다음처럼 응답한다.

| 필드 | 코스 없음 |
|---|---|
| `tripId`, `myRole`, `startDate`, `endDate`, `version` | 값 있음. `version`은 여행 버전(숫자) |
| `regionSigCd`, `regionName`, `scheduleDensity` | 지역을 정했으면 값, 정하기 전이면 `null` |
| `title`, `titleSource`, `recommendationMode`, `assumptions`, `tasteBasis`, `updatedBy`, `updatedAt` | `null` |
| `days`, `warnings` | `[]` |

| 오류 | HTTP | code |
|---|---:|---|
| cookie 없음·만료 | 401 | `SHARE_SESSION_INVALID` |
| 링크가 그 사이 만료·폐기됨 | 410 | `SHARE_LINK_EXPIRED`, `SHARE_LINK_REVOKED` |

여행이 삭제되면(마지막 참여자 탈퇴) 공유 링크와 share session이 함께 삭제되므로 `401 SHARE_SESSION_INVALID`다. 링크 원문으로 다시 열면 4-12가 `404 SHARE_LINK_NOT_FOUND`를 준다. 프론트는 둘 다 "더 이상 열 수 없는 링크"로 보여준다.

**구현과의 차이** — 코스 저장이 아직 없어 `{tripId, myRole, startDate, endDate, days: []}`만 반환한다.

---

### 4-14. 보낸 친구 초대 목록

> `호출: 참여자` · `⬜ 미구현`

```
GET /api/trips/{tripId}/friend-invites
```

**Response `200 OK`** — 이 여행에서 보낸 친구 초대 전체, 최신순. 누가 보냈든 참여자 모두에게 같은 목록이다.

```json
[
  {"id": 9, "invitee": {"userId": 12, "nickname": "여행친구"}, "inviter": {"userId": 1, "nickname": "만든사람"},
   "status": "PENDING", "createdAt": "2026-10-01T12:00:00"}
]
```

`status`는 `PENDING` \| `ACCEPTED` \| `DECLINED` \| `CANCELLED`.

| 오류 | HTTP | code |
|---|---:|---|
| 없거나 참여자가 아님 | 404 | `TRIP_NOT_FOUND` |

---

### 4-15. 친구 초대 취소

> `호출: 참여자` · `⬜ 미구현`

```
DELETE /api/trips/{tripId}/friend-invites/{inviteId}
```

참여자 누구나 대기 중인 친구 초대를 취소한다(보낸 사람이 아니어도 된다). `PENDING` → `CANCELLED`.

**Response `204 No Content`** — 초대받은 사람의 받은 초대 목록(4-7)에서 사라진다.

| 오류 | HTTP | code |
|---|---:|---|
| 없거나 참여자가 아닌 여행 | 404 | `TRIP_NOT_FOUND` |
| 없거나 이 여행의 초대가 아님 | 404 | `INVITE_NOT_FOUND` |
| `PENDING`이 아님 | 409 | `INVITE_ALREADY_HANDLED` |

---

### 제거한 구현

2026-09-24 정책으로 비회원 참여가 없어져 아래 API를 제거했다(V6 migration이 guest 데이터를 삭제한다).

| Method | Path | 대체 |
|---|---|---|
| POST | `/api/invites/{token}/participants` | 4-5 초대 링크 수락(회원) |
| POST | `/api/invite-participants/{id}/onboarding` | 회원 온보딩 [2-2](profile.md#2-2-온보딩-제출) |
| GET | `/api/trips/{tripId}/invites/{inviteId}` | 4-2 목록 |
| PATCH | `/api/trips/{tripId}/invites/{inviteId}` | 4-3 폐기 |
| PATCH | `/api/courses/{tripId}/share-links/{linkId}` | 4-11 폐기 |

guest session(`gs_` token, `Authorization: Bearer gs_…`)과 초대·공유의 `permission`(VIEW·EDIT)도 함께 없앴다.

---

# 5. 코스

- 계약 상태: agreed
- 정책 소스: [결정](../product.md#코스와-편집), [코스 조립](../design/recommendation.md#7-코스-조립)

**코스란** — 여행의 날짜별 일정표(어디를 몇 시에 가는지)다. 여행마다 코스는 하나이며 **course id = trip id**다. 확정 단계는 없다. 만든 뒤 참여자 누구나 계속 고친다.

```text
3-7 지역 정하기 → 5-1 코스 생성(요청자 취향, 서버 저장)
             → 5-3 편집 반복(여행 버전 증가)
             → 5-1 재생성(누른 사람 취향, 기존 편집을 덮어씀)
```

- 모든 편집은 `version`을 보내고, 서버가 일정 전체의 시간·이동·식사·밀도 상한을 다시 계산한다. 클라이언트가 보낸 시간·거리는 믿지 않는다.
- `version`은 코스 전용 값이 아니라 **여행의 `trip_plans.version` 하나**다. 여행 context 수정(3-5), 지역 정하기(3-7), 코스 생성·재생성(5-1), 편집(5-3)이 모두 같은 값을 올리고, 요청·응답의 `version`은 모두 이 수를 가리킨다. 재생성해도 계속 증가하며 1로 돌아가지 않는다. 불일치는 `409 TRIP_VERSION_CONFLICT`이고, 예전 화면의 요청은 이것으로 걸러진다.
- 이 장은 **밖에서 관찰되는 동작**만 정한다. 후보 점수식, 날짜 배정·순서 알고리즘, 폴백 세부, 제목 프롬프트는 [데이터·추천·코스 설계](../design/recommendation.md) §5~§8을 따른다. 5장을 구현하는 사람은 그 문서를 함께 읽는다.
- 코스는 여행이 삭제될 때만 함께 삭제된다. 3-7 `replaceCourse: true`로 지역·밀도를 바꾸면 코스가 **비워진다**(항목·식당 선택 삭제, 코스 기록과 최초 생성 완료 표시는 유지). 비운 코스는 5-2~5-6에서 코스가 없는 것과 같다(`404 COURSE_NOT_FOUND`, 4-13은 `days: []`). 다음 5-1은 재생성이다.
- 이동수단·출발지(3-5)는 코스가 있어도 바꿀 수 있고, 서버가 이동시간을 다시 계산한다.
- 밀도(RELAXED 4·PACKED 6)는 **자동 생성의 목표**다. 수동 편집(5-3)은 밀도 상한을 넘겨 추가할 수 있고 가용 시간만 검사한다.

**`Course`**

```json
{
  "tripId": 42,
  "version": 3,
  "myRole": "PARTICIPANT",
  "regionSigCd": "47130",
  "regionName": "경상북도 경주시",
  "startDate": "2026-10-10",
  "endDate": "2026-10-11",
  "scheduleDensity": "RELAXED",
  "title": "경주, 역사를 따라 걷는 2일",
  "titleSource": "RULE",
  "recommendationMode": "PERSONALIZED",
  "tasteBasis": {"userId": 1, "nickname": "만든사람"},
  "assumptions": {
    "regionArrivalTime": "12:00",
    "regionDepartureTime": "17:00",
    "mealPreferences": {"lunchStart": "12:00", "dinnerStart": "18:00", "durationMinutes": 60}
  },
  "days": [
    {
      "dayIndex": 0,
      "date": "2026-10-10",
      "windowStart": "12:00",
      "windowEnd": "20:00",
      "items": [
        {"itemId": "m-31", "type": "MEAL", "meal": "LUNCH", "startTime": "12:00", "durationMinutes": 60, "restaurant": null},
        {"itemId": "a-101", "type": "ATTRACTION", "attractionId": 5012, "name": "대릉원", "category": "HISTORY_CULTURE",
         "thumbnailUrl": "https://…", "address": "경북 경주시 황남동 …", "lat": 35.838, "lng": 129.211, "startTime": "13:25", "durationMinutes": 90,
         "travelFromPreviousMinutes": 25, "estimated": true, "source": "RECOMMEND", "reason": "역사 선호와 맞아요"}
      ]
    }
  ],
  "warnings": [{"code": "ROUTE_TIME_ESTIMATED", "dayIndex": null, "itemId": null}],
  "updatedBy": {"userId": 12, "nickname": "여행친구"},
  "updatedAt": "2026-10-01T21:00:00"
}
```

| 필드 | 설명 |
|---|---|
| `version` | 여행 버전(`TripContext.version`과 같은 수). 다음 5-1·5-3·3-5·3-7 요청에 그대로 보낸다 |
| `myRole` | `PARTICIPANT`(수정 가능) \| `VIEWER`(공유 링크, 읽기 전용). 프론트는 이것으로 편집 UI를 켠다 |
| `titleSource` | `RULE` \| `LLM`. MVP 기본은 규칙 제목 `{지역명}, {대표 테마}를 따라 걷는 {일수}일`(`RULE`)이다. LLM 제목은 서버 설정으로 켰을 때만 시도하고(`LLM`), 실패·timeout이면 `RULE`로 대신한다 |
| `recommendationMode` | `PERSONALIZED`(취향 반영) \| `TOUR_OFFICIAL`(임베딩 장애 → TourAPI 공식 코스) \| `RULE_BASED`(취향 없는 규칙 코스) |
| `assumptions` | 코스 계산에 쓴 고정 기본값(지역 도착·출발 시각, 점심·저녁 시작 시각, 식사 길이). 사용자가 바꾸는 값이 아니며 표시용이다 |
| `tasteBasis` | 이 코스를 생성·재생성할 때 취향·제외 조건을 쓴 사람. `myRole: VIEWER`면 `null` |
| `items[].itemId` | 서버가 부여하는 안정적 ID. 관광지 `a-{n}`, 식사 `m-{n}`. 편집 대상 지정에 쓴다. 재생성하면 모두 새 ID가 된다 |
| `items[].type` | `ATTRACTION` \| `MEAL`. 아래 타입별 필드 표 참고 |
| `items[].category` | `NATURE`(자연) \| `HISTORY_CULTURE`(역사·문화) \| `ACTIVITY`(체험·레포츠) \| `WALK_REST`(산책·휴식) \| `ETC`(기타) |
| `items[].durationMinutes` | 관광지는 유형별 기본 체류(짧은 장소 60·일반 90·대형 문화·레포츠 120분). 실측이 아니므로 `estimated: true` |
| `items[].travelFromPreviousMinutes` | 직선거리 × 보정계수 / 속도로 추정(도보 1.25·4km/h, 자동차 1.35·35km/h, 대중교통 1.50·25km/h, 최소 5분). 첫 항목은 `null` |
| `items[].source` | `RECOMMEND`(자동 추천) \| `MANUAL`(사용자가 추가·교체) |
| `items[].reason` | 추천 이유. 실제 태그·장소에 근거한 문장만. `MANUAL`이면 `null` |
| `items[].restaurant` | 식사 슬롯에 고른 식당 스냅샷(`RestaurantSnapshot`). 없으면 `null` |
| `updatedBy` | 마지막으로 코스를 바꾼 참여자. `myRole: VIEWER`면 `null` |

**항목 타입별 필드** — 표에 없는 필드는 그 타입에 없다.

| 필드 | ATTRACTION | MEAL |
|---|---|---|
| `itemId`, `type`, `startTime`, `durationMinutes` | 필수 | 필수 |
| `attractionId`, `name`, `category`, `thumbnailUrl`, `address`, `lat`, `lng` | 필수(`thumbnailUrl`·`address`는 nullable) | — |
| `travelFromPreviousMinutes` | 그날 첫 항목이면 `null` | — (식당 이동은 계산하지 않는다) |
| `estimated`, `source`, `reason` | 필수(`reason`은 nullable) | — |
| `meal` | — | `LUNCH` \| `DINNER` |
| `restaurant` | — | `RestaurantSnapshot` 또는 `null` |

**`RestaurantSnapshot`**

```json
{
  "provider": "TOUR_API",
  "externalId": "2871024",
  "name": "황남맷돌순두부",
  "category": "한식",
  "address": "경북 경주시 …",
  "roadAddress": "경북 경주시 …",
  "lat": 35.83, "lng": 129.21,
  "phone": "054-…",
  "placeUrl": null,
  "imageUrl": "https://…",
  "representativeMenu": "순두부찌개",
  "evidenceLabels": ["한국관광공사 등록 음식점"],
  "sources": [{"name": "한국관광공사 TourAPI", "url": "https://…", "fetchedAt": "2026-09-30T00:00:00"}]
}
```

- `provider`: `TOUR_API` \| `FARM_RESTAURANT`(농가맛집) \| `MODEL_RESTAURANT`(모범·향토음식점) \| `GOOD_PRICE`(착한가격업소) \| `KAKAO`
- `representativeMenu`는 원천이 제공한 경우에만 채운다. 카카오 결과는 항상 `null`이며 맛·평점·인기·대표성 문구를 만들지 않는다.

**`warnings[].code`**

| code | 뜻 |
|---|---|
| `ROUTE_TIME_ESTIMATED` | 이동시간이 직선거리 추정이다. 실제 도로 시간이 아니라고 표시한다. MVP에서는 항상 붙는다 |
| `DENSITY_TARGET_NOT_MET` | 시간·식사 제약 또는 지역 후보 부족 때문에 밀도 상한보다 적게 배치됨. `dayIndex` 포함 |
| `MEAL_SKIPPED_OUTSIDE_WINDOW` | 식사 슬롯이 가용 시간 밖이라 넣지 않음 |
| `PERSONALIZATION_FALLBACK` | 취향 임베딩을 쓸 수 없어 `TOUR_OFFICIAL`·`RULE_BASED`로 만듦 |
| `TITLE_GENERATION_FAILED` | LLM 제목을 켰는데 실패·timeout이라 규칙 제목을 사용함. LLM을 끈 기본 설정에서는 붙지 않는다 |
| `ATTRACTION_NO_LONGER_RECOMMENDABLE` | 품질 기준에서 빠진 장소가 일정에 있음. `itemId` 포함. 항목은 그대로 두고 다른 편집을 막지 않는다. 프론트는 교체(5-4)를 제안한다. 공유 화면(4-13)도 그대로 보여준다 |

**오류 코드**

| code | HTTP | 상황 |
|---|---:|---|
| `COURSE_INVALID_OPERATION` | 400 | 편집 operation 형식 오류, 또는 `itemId`가 가리키는 항목의 타입이 그 API·operation에 맞지 않음(5-3·5-4·5-5·5-6 공통) |
| `COURSE_CREATOR_ONLY` | 403 | 최초 코스를 생성자가 아닌 참여자가 만들려 함(생성자가 참여 중일 때) |
| `TRIP_NOT_FOUND` | 404 | 없거나 접근 권한이 없는 여행 |
| `COURSE_NOT_FOUND` | 404 | 여행은 있으나 코스를 아직 만들지 않았거나 3-7 `replaceCourse`로 비워짐 |
| `COURSE_ITEM_NOT_FOUND` | 404 | 없는 `itemId` |
| `ATTRACTION_NOT_FOUND` | 404 | 없는 `attractionId` |
| `TRIP_REGION_NOT_SELECTED` | 409 | 지역을 정하기 전 |
| `TRIP_VERSION_CONFLICT` | 409 | `version` 불일치 또는 동시 최초 생성(3장과 같은 코드). 최신 `version`은 5-2로 다시 받는다 |
| `TRIP_ENDED` | 409 | 종료일이 지난 여행의 코스를 바꾸거나 후보·식당을 조회 |
| `ATTRACTION_ALREADY_IN_COURSE` | 409 | 이미 일정에 있는 관광지를 추가·교체 |
| `COURSE_INSUFFICIENT_CANDIDATES` | 422 | 폴백까지 거쳐도 관광지를 하루 최소 1곳 배치하지 못하는 날이 있음. `details.days[]` |
| `ATTRACTION_NOT_RECOMMENDABLE` | 422 | 좌표·설명·검증 이미지가 없는 장소 |
| `ATTRACTION_REGION_MISMATCH` | 422 | 다른 지역의 장소 |
| `COURSE_SCHEDULE_INFEASIBLE` | 422 | 편집·재계산 결과가 가용 시간을 넘기거나 식사 시간을 침범함. `details.dayIndex`, `details.reason` |
| `COURSE_RESTAURANT_SELECTION_INVALID` | 422 | 식당 `selectionToken`의 형식·서명이 틀렸거나(위조), 만료(30분)됐거나, `tripId`·`itemId`가 이 슬롯과 다름 |
| `COURSE_KAKAO_LOCAL_UNAVAILABLE` | 502 | 카카오 Local 장애·timeout·잘못된 응답 |
| `COURSE_KAKAO_LOCAL_RATE_LIMITED` | 503 | 카카오 호출 한도. `Retry-After` 헤더 포함 |

---

### 5-1. 코스 생성·재생성

> `호출: 참여자` · `⬜ 미구현`

```
POST /api/courses/{tripId}/generate
```

지역이 정해진 여행의 코스를 **요청자의 취향**으로 계산해 저장한다. 이미 코스가 있으면 새 결과로 **교체**한다(재생성).

- **코스가 없을 때(최초 생성)는 여행을 만든 사람(생성자)만** 한다. 생성자가 이미 탈퇴했으면 남은 참여자 누구나 할 수 있다. 코스는 삭제되지 않으므로 최초 생성은 여행마다 한 번이다.
- 코스가 있으면(재생성, 3-7로 비운 코스 포함) 참여자 누구나 자기 취향으로 한다.
- 재생성은 다른 참여자의 편집과 식당 선택을 모두 덮어쓴다. 프론트는 먼저 "다른 참여자의 편집과 식당 선택도 사라집니다"로 확인을 받는다. 되돌리기는 후속이다.

**Request Body**

```json
{
  "scheduleDensity": "RELAXED",
  "version": 2
}
```

| 필드 | 타입 | 필수 | 제약·기본값 |
|---|---|---|---|
| `scheduleDensity` | `string` | 아니오 | `RELAXED`(하루 최대 4곳) \| `PACKED`(최대 6곳). 기본은 여행의 현재 `scheduleDensity`(없으면 요청자의 최신 온보딩 값). 보내면 여행의 밀도(`trip_plans.schedule_density`, `TripContext.scheduleDensity`)도 이 값으로 바뀐다 |
| `version` | `number` | 예 | 직전에 받은 여행 `version`(`TripContext`·`Course` 공통). 다르면 409. 코스가 없는데 두 사람이 같은 `version`으로 동시에 만들면 먼저 끝난 하나만 성공하고 나머지는 409다 |

**생성 규칙**

- 시각은 사용자가 정하지 않는다(2026-09-25). 날짜별 가용 시간은 `TripContext.dayWindows`(첫날 12:00, 마지막 날 17:00, 당일치기 12:00~20:00), 식사는 점심 12:00·저녁 18:00 시작, 60분으로 고정해 계산한다. 사용자는 5-3 수동 편집으로만 코스를 조정한다.

- 후보: 같은 지역의 추천 가능 관광지(좌표·설명·검증 이미지 보유). **요청자**의 제외 조건 중 판정할 수 있는 것만 적용한다.
  - `물놀이`: 물놀이 성격의 관광지 분류(해수욕장·계곡·수상 레포츠 등)를 후보에서 뺀다.
  - `야간 이동`: 모든 날의 가용 시간이 20:00 전에 끝나므로 기본 규칙으로 지켜진다.
  - `계단·경사 많은 곳`, `오래 걷기`: 판정할 데이터가 없어 MVP에서는 저장만 하고 코스에 쓰지 않는다.
- 취향: 요청자의 최신 온보딩 취향 벡터. 다른 참여자 취향은 쓰지 않는다.
- 식사 슬롯이 가용 시간에 완전히 들어갈 때만 식사를 넣는다.
- 중복·식사 침범·밀도 상한은 어길 수 없다. 운영시간·휴무는 원천이 자유 문장이라 MVP에서는 배치에 쓰지 않는다(관광지 상세 7-4에 원문만 보여 준다). 채우지 못하면 적게 배치하고 `DENSITY_TARGET_NOT_MET`을 붙인다.
- 바꾼 밀도에 지역의 후보가 모자라도(7-1 기준 추첨 불가여도) 거부하지 않는다. 들어가는 만큼 배치하고 `DENSITY_TARGET_NOT_MET`을 붙인다. `422 COURSE_INSUFFICIENT_CANDIDATES`는 폴백까지 거쳐도 **하루 최소 1곳**을 채우지 못하는 날이 있을 때만이다.
- 폴백: 취향 벡터 → `PERSONALIZED`, 없으면 같은 지역 TourAPI 공식 코스 → `TOUR_OFFICIAL`, 부족하면 규칙 코스 → `RULE_BASED`. 모두 실패하면 422.
- 제목은 장소·순서가 정해진 뒤 만든다. MVP는 규칙 제목(`RULE`)이다. LLM 제목은 선택 기능으로 서버 설정(예: `course.title.llm-enabled`, 기본 `false`)으로 켜며, 실패·timeout이면 규칙 제목으로 대신하고 코스 생성은 성공한다. MVP에 LLM 제공자 계약은 필요 없다.

**Response `201 Created`** — `Course` (`version`은 요청의 `version` + 1)

| 오류 | HTTP | code |
|---|---:|---|
| 여행 종료일이 지남 | 409 | `TRIP_ENDED` |
| 코스가 없는데 생성자가 아닌 참여자가 호출(생성자가 참여 중) | 403 | `COURSE_CREATOR_ONLY` |
| 없거나 참여자가 아님 | 404 | `TRIP_NOT_FOUND` |
| 지역을 정하기 전 | 409 | `TRIP_REGION_NOT_SELECTED` |
| 버전 불일치 | 409 | `TRIP_VERSION_CONFLICT` |
| 관광지를 1곳도 배치하지 못하는 날이 있음 | 422 | `COURSE_INSUFFICIENT_CANDIDATES` |

`COURSE_INSUFFICIENT_CANDIDATES`의 `details` — 하루 최소 1곳을 채우지 못한 날:

```json
{"days": [{"dayIndex": 1, "required": 1, "available": 0}]}
```

**Side effects** — 기존 항목을 지우고 `trip_stops`·`meal_stops`에 새 항목, 가정값, 추천 모드, 요청자 취향 스냅샷, 제목을 저장한다. `scheduleDensity`를 보냈으면 `trip_plans.schedule_density`도 바꾼다. 임베딩 서버를 요청마다 재시도하지 않는다.

---

### 5-2. 코스 조회

> `호출: 참여자` · `⬜ 미구현`

```
GET /api/courses/{tripId}
```

**Response `200 OK`** — `Course` (`myRole: PARTICIPANT`). 공유 링크 소지자는 [4-13](#4-13-공유-코스-조회)을 쓴다. 날짜·순서가 안정적으로 정렬된다. token 해시, 취향 벡터, 참여자의 설문 답변은 포함하지 않는다.

| 오류 | HTTP | code |
|---|---:|---|
| 없거나 접근 권한 없음 | 404 | `TRIP_NOT_FOUND` |
| 코스를 아직 만들지 않음 | 404 | `COURSE_NOT_FOUND` |

---

### 5-3. 일정 편집

> `호출: 참여자` · `⬜ 미구현`

```
PATCH /api/courses/{tripId}/schedule
```

여러 operation을 한 번에 보내면 **전부 적용되거나 전부 거부**된다.

**Request Body**

```json
{
  "version": 3,
  "operations": [
    {"op": "REPLACE", "itemId": "a-101", "attractionId": 5020},
    {"op": "ADD", "dayIndex": 1, "position": 2, "attractionId": 5033},
    {"op": "MOVE", "itemId": "a-104", "dayIndex": 0, "position": 1},
    {"op": "REMOVE", "itemId": "a-105"},
    {"op": "SET_RESTAURANT", "itemId": "m-31", "selectionToken": "rs_eyJ..."},
    {"op": "CLEAR_RESTAURANT", "itemId": "m-32"}
  ]
}
```

| op | 필드 | 뜻 |
|---|---|---|
| `ADD` | `dayIndex`, `position?`, `attractionId` | 관광지 추가. `position`은 그날 관광지 순서(0부터), 생략하면 맨 뒤 |
| `REPLACE` | `itemId`, `attractionId` | 관광지 교체 |
| `REMOVE` | `itemId` | 관광지 삭제. 식사 슬롯은 삭제하지 않는다 |
| `MOVE` | `itemId`, `dayIndex`, `position` | 관광지를 다른 순서·날짜로 이동 |
| `SET_RESTAURANT` | `itemId`(식사), `selectionToken` | 5-5·5-6 결과의 식당을 식사 슬롯에 지정·교체. 토큰은 받은 그대로 body에 넣는다(1~2KB, query string 금지). 서버는 서명·만료·`tripId`·`itemId`를 검증하고 토큰 안의 스냅샷을 저장한다 |
| `CLEAR_RESTAURANT` | `itemId`(식사) | 식당 선택 해제 |

- `operations`는 1~20개, 앞에서부터 차례로 적용한 결과를 검증한다.
- `REMOVE`·`REPLACE`·`MOVE`는 관광지 항목에만, `SET_RESTAURANT`·`CLEAR_RESTAURANT`는 식사 항목에만 쓴다. 다른 타입이나 범위 밖 `dayIndex`·`position`은 `400 COURSE_INVALID_OPERATION`이다.
- `REPLACE`는 `itemId`를 유지하고 장소만 바꾼다. `ADD`는 새 `itemId`를 만든다.
- 추가·교체 대상은 같은 지역의 추천 가능 관광지여야 하고 이미 일정에 없어야 한다.
- 적용 뒤 서버가 모든 날의 시작 시각·이동시간·식사 위치를 다시 계산한다. 가용 시간을 넘기면 422로 거부하고 아무것도 바꾸지 않는다.
- 밀도 상한은 검사하지 않는다. `ADD`로 상한보다 많이 넣어도 그날 가용 시간 안이면 된다(밀도는 자동 생성의 목표일 뿐이다).
- 관광지가 0곳인 날도 허용한다. 식당을 고르지 않은 식사 슬롯은 화면에서 "미정"으로 보인다. 식사를 건너뛰는 operation은 없다.
- 일정에 이미 있는 추천 불가 장소(`ATTRACTION_NO_LONGER_RECOMMENDABLE`)는 다른 편집을 막지 않는다. 추천 가능 여부는 `ADD`·`REPLACE`로 새로 넣는 장소에만 검사한다.
- 날짜·지역·밀도·시각(도착·출발·식사)은 이 API로 바꾸지 않는다. 밀도는 재생성(5-1), 지역은 3-7(`replaceCourse`)이다. 시각은 고정 기본값이며 사용자가 바꾸지 않는다.

**Response `200 OK`** — 갱신된 `Course`, `version` +1, `updatedBy`는 요청자.

| 오류 | HTTP | code |
|---|---:|---|
| 여행 종료일이 지남 | 409 | `TRIP_ENDED` |
| operation 형식 오류 | 400 | `COURSE_INVALID_OPERATION` |
| 없거나 참여자가 아닌 여행 | 404 | `TRIP_NOT_FOUND` |
| 코스 없음 | 404 | `COURSE_NOT_FOUND` |
| 없는 항목·관광지 | 404 | `COURSE_ITEM_NOT_FOUND`, `ATTRACTION_NOT_FOUND` |
| 버전 불일치 | 409 | `TRIP_VERSION_CONFLICT` |
| 중복 장소 | 409 | `ATTRACTION_ALREADY_IN_COURSE` |
| 추천 불가·타지역 장소 | 422 | `ATTRACTION_NOT_RECOMMENDABLE`, `ATTRACTION_REGION_MISMATCH` |
| 가용 시간 초과·식사 침범 | 422 | `COURSE_SCHEDULE_INFEASIBLE` |
| 식당 `selectionToken` 위조·만료·다른 슬롯 | 422 | `COURSE_RESTAURANT_SELECTION_INVALID` |

`COURSE_SCHEDULE_INFEASIBLE`의 `details`는 `{"dayIndex": 1, "reason": "…"}`이며 `reason`은 `WINDOW_EXCEEDED`(가용 시간 초과), `MEAL_OVERLAP`(식사 시간 침범) 중 하나다.

**동시성** — 두 사람이 같은 `version`으로 보내면 먼저 도착한 하나만 성공한다. 나머지는 `409 TRIP_VERSION_CONFLICT`를 받고, 5-2로 최신본을 다시 받아 적용한다. 이 `version`은 3-5·3-7과 같은 여행 버전이므로 그 사이 다른 참여자가 이동수단·지역을 바꿔도 409다.

---

### 5-4. 대체 후보

> `호출: 참여자` · `⬜ 미구현`

```
GET /api/courses/{tripId}/alternatives?itemId=a-101&category=NATURE&limit=10
```

| Query | 필수 | 설명 |
|---|---|---|
| `itemId` | 아니오 | 교체할 관광지. 주면 그 자리의 앞뒤 이동시간을 반영해 정렬한다. 생략하면 추가용 후보 |
| `category` | 아니오 | `NATURE` \| `HISTORY_CULTURE` \| `ACTIVITY` \| `WALK_REST` \| `ETC`. 생략하면 전 유형 |
| `limit` | 아니오 | 유형별 개수. 기본 10, 최대 30 |

`category`가 목록 밖이거나 `limit`가 1~30 밖이면 `400 COMMON_INVALID_REQUEST`다. `itemId`가 식사 항목이면 `400 COURSE_INVALID_OPERATION`이다.

**Response `200 OK`**

```json
{
  "itemId": "a-101",
  "groups": [
    {
      "category": "NATURE",
      "label": "자연",
      "items": [
        {"attractionId": 5020, "name": "경주 동궁과 월지", "category": "NATURE", "thumbnailUrl": "https://…",
         "lat": 35.834, "lng": 129.226, "estimatedDurationMinutes": 90,
         "travelFromPreviousMinutes": 10, "reason": "자연·산책 선호와 맞아요"}
      ]
    }
  ]
}
```

후보는 같은 지역, 추천 가능, 현재 코스에 없음을 모두 만족한다. 정렬은 **요청자** 취향 점수와 이동 부담을 함께 반영한다.

| 오류 | HTTP | code |
|---|---:|---|
| 여행 종료일이 지남 | 409 | `TRIP_ENDED` |
| `category`·`limit` 오류 | 400 | `COMMON_INVALID_REQUEST` |
| 식사 항목을 지정 | 400 | `COURSE_INVALID_OPERATION` |
| 없거나 참여자가 아닌 여행 | 404 | `TRIP_NOT_FOUND` |
| 없는 항목·코스 | 404 | `COURSE_ITEM_NOT_FOUND`, `COURSE_NOT_FOUND` |

---

### 5-5. 식당 추천

> `호출: 참여자` · `⬜ 미구현`

```
GET /api/courses/{tripId}/restaurants/recommendations?itemId=m-31&radius=5000
```

| Query | 필수 | 설명 |
|---|---|---|
| `itemId` | 예 | 식사 슬롯 |
| `radius` | 아니오 | 미터. 기본 5000, 최대 20000 |

검색 기준점은 그 식사 **바로 앞 관광지**다. 그날 첫 항목이면 지역 중심 좌표를 쓴다.

**후보 조건**

- 여행 지역(`regionSigCd`)에 속한 식당만. 다른 시군구 식당은 가까워도 넣지 않는다.
- 기준점에서 `radius` 이내(직선거리).
- 섹션(원천)별로 기준점에서 가까운 순, 최대 20개. 같은 식당이 여러 원천에 있으면(병합된 식당) 우선순위가 가장 높은 섹션에만 둔다.

**Response `200 OK`**

```json
{
  "itemId": "m-31",
  "origin": {"type": "PREVIOUS_ATTRACTION", "attractionId": 5012, "lat": 35.838, "lng": 129.211},
  "regionFoodThemes": [{"name": "경주 찰보리빵", "sources": [{"name": "경주시청", "url": "https://…"}]}],
  "sections": [
    {"source": "TOUR_API", "label": "한국관광공사 등록 음식점", "items": [
      {"selectionToken": "rs_eyJ...", "distanceMeters": 420, "...": "RestaurantSnapshot 필드"}
    ]},
    {"source": "FARM_RESTAURANT", "label": "농촌진흥청 농가맛집", "items": []},
    {"source": "MODEL_RESTAURANT", "label": "지자체 모범·향토음식점", "items": []},
    {"source": "GOOD_PRICE", "label": "착한가격업소", "items": []}
  ]
}
```

- `origin.type`: `PREVIOUS_ATTRACTION` \| `REGION_CENTER`
- `regionFoodThemes`는 사람이 승인하고 출처가 있는 지역 음식·특산물만. MVP에는 이 데이터를 만드는 작업이 없으므로 빈 배열 `[]`일 수 있다(프론트는 빈 배열이면 영역을 숨긴다).
- 섹션 순서가 우선순위다. TourAPI 음식점이 1순위, 공공 지정 식당이 보완이다. 각 섹션은 거리순, 최대 20개. 결과가 없는 섹션도 빈 배열로 준다.
- **MVP 범위**: MVP에서 채우는 섹션은 `TOUR_API`(TourAPI 음식점)뿐이다. 공공 지정 식당 섹션(`FARM_RESTAURANT`, `MODEL_RESTAURANT`, `GOOD_PRICE`)은 추가 기능이며 MVP에서는 항상 빈 배열이다(응답 모양은 같다).
- 모든 섹션이 비어 있으면 프론트는 같은 `itemId`로 5-6(카카오 검색)을 자동으로 호출해 보여준다.
- `selectionToken`(`rs_…`)은 **식당 스냅샷 전체**를 담아 서버 비밀키로 서명한 값이다. 서버에 저장하지 않는다.
  - 형식: `rs_` + JWS 비슷한 compact 형식(`base64url(header).base64url(payload).base64url(HMAC-SHA256 서명)`).
  - payload: `tripId`, `itemId`, `source`(provider), `externalId`, `name`, `category`, `address`, `roadAddress`, `phone`, `lat`, `lng`, `placeUrl`, `imageUrl`, `representativeMenu`, `evidenceLabels`, `sources`, `iat`(발급), `exp`(발급 + 30분).
  - 길이는 약 1~2KB다. 클라이언트는 해석하지 않고 5-3 `SET_RESTAURANT`의 body에 그대로 돌려보낸다(query string에 넣지 않는다).
  - 서버는 서명·`exp`·`tripId`·`itemId`를 검증한 뒤 payload의 스냅샷을 그대로 저장한다. 클라이언트가 식당 필드를 따로 보내지 않는다. 검증 실패는 `422 COURSE_RESTAURANT_SELECTION_INVALID`다.
  - 그 슬롯에서만, 발급 후 30분 동안 쓸 수 있다. 코스를 재생성해 `itemId`가 바뀌면 무효다.

| 오류 | HTTP | code |
|---|---:|---|
| 여행 종료일이 지남 | 409 | `TRIP_ENDED` |
| `radius` 범위 밖 | 400 | `COMMON_INVALID_REQUEST` |
| 식사 슬롯이 아닌 항목 | 400 | `COURSE_INVALID_OPERATION` |
| 없거나 참여자가 아닌 여행 | 404 | `TRIP_NOT_FOUND` |
| 코스 없음 | 404 | `COURSE_NOT_FOUND` |
| 없는 항목 | 404 | `COURSE_ITEM_NOT_FOUND` |

---

### 5-6. 식당 검색

> `호출: 참여자` · `⬜ 미구현`

```
GET /api/courses/{tripId}/restaurants/search?itemId=m-31&query=칼국수&radius=5000&page=1
```

5-5에 원하는 식당이 없을 때 쓰는 카카오 Local 음식점(`FD6`) 거리순 검색이다.

| Query | 필수 | 설명 |
|---|---|---|
| `itemId` | 예 | 식사 슬롯. 기준점은 5-5와 같다 |
| `query` | 아니오 | 검색어 1~50자. 생략하면 주변 음식점 전체 |
| `radius` | 아니오 | 미터. 기본 5000, 최대 20000 |
| `page` | 아니오 | 1부터. 기본 1, 최대 45 |

**Response `200 OK`**

```json
{
  "itemId": "m-31",
  "origin": {"type": "PREVIOUS_ATTRACTION", "attractionId": 5012, "lat": 35.838, "lng": 129.211},
  "page": 1,
  "isEnd": false,
  "items": [
    {"selectionToken": "rs_eyJ...", "provider": "KAKAO", "externalId": "12345678", "name": "○○칼국수",
     "category": "음식점 > 한식 > 국수", "address": "…", "roadAddress": "…", "lat": 35.84, "lng": 129.21,
     "distanceMeters": 380, "phone": "054-…", "placeUrl": "https://place.map.kakao.com/12345678",
     "imageUrl": null, "representativeMenu": null, "evidenceLabels": ["카카오맵 검색 결과"], "sources": []}
  ]
}
```

카카오 결과에는 평점·맛·인기·대표 메뉴를 넣지 않는다. 카카오 장소를 서비스 식당 목록에 승격하지 않는다. `selectionToken`은 5-5와 같은 형식·검증 규칙이다.

| 오류 | HTTP | code |
|---|---:|---|
| 여행 종료일이 지남 | 409 | `TRIP_ENDED` |
| `radius`·`page`·`query` 범위 밖 | 400 | `COMMON_INVALID_REQUEST` |
| 식사 슬롯이 아닌 항목 | 400 | `COURSE_INVALID_OPERATION` |
| 없거나 참여자가 아닌 여행 | 404 | `TRIP_NOT_FOUND` |
| 코스 없음 | 404 | `COURSE_NOT_FOUND` |
| 없는 항목 | 404 | `COURSE_ITEM_NOT_FOUND` |
| 카카오 장애 | 502 | `COURSE_KAKAO_LOCAL_UNAVAILABLE` |
| 카카오 한도 | 503 | `COURSE_KAKAO_LOCAL_RATE_LIMITED` |

---

# 6. 여행기·사진 지도

> **추가 기능(MVP 이후)** — MVP 범위가 아니다. 계약은 미리 적어 두지만 MVP 구현 대상이 아니다.

- 계약 상태: draft
- 정책 소스: [결정](../product.md#여행기사진-지도-추가-기능)
- 정책: **여행 종료일이 지난** 여행에 참여자마다 여행기 하나. 여행기는 쓴 사람의 것이며 다른 참여자와 공유되지 않는다. 사진 1~30장. 내 지도에서 핀을 눌러 다시 본다. 친구 공개는 상호 수락 친구만, 링크 공유는 그 여행기만 읽기 전용이다. 전체 지도 공개·피드·댓글·좋아요는 없다.

**`Diary`**

```json
{
  "diaryId": 31,
  "tripId": 42,
  "status": "DRAFT",
  "title": "비 오는 날의 경주",
  "body": "…",
  "courseTitle": "신라의 시간을 걷는 2일",
  "regionSigCd": "47130",
  "visitedFrom": "2026-10-10",
  "visitedTo": "2026-10-11",
  "visibility": "PRIVATE",
  "locationPrecision": "CITY",
  "satisfaction": 5,
  "experienceTags": ["역사", "산책"],
  "includeInTasteProfile": true,
  "coverPhotoId": 301,
  "photos": [{"photoId": 301, "url": "https://…", "thumbnailUrl": "https://…", "takenAt": "2026-10-10T14:03:00", "lat": 35.83, "lng": 129.21, "order": 0}],
  "publishedAt": null,
  "updatedAt": "2026-10-13T20:00:00"
}
```

| 필드 | 설명 |
|---|---|
| `status` | `DRAFT` \| `PUBLISHED` |
| `visibility` | `PRIVATE`(나만) \| `FRIENDS`(수락된 친구) \| `LINK`(공유 링크 소지자). 기본 `PRIVATE` |
| `locationPrecision` | 남에게 보일 사진 위치 정밀도. `EXACT` \| `CITY`(지역 중심으로 대체) \| `HIDDEN`(위치 없음). 기본 `CITY`. 작성자 본인에게는 항상 원래 위치 |
| `satisfaction` | 1~5, 선택 |
| `experienceTags` | 온보딩 경험 태그 사전의 부분집합, 최대 5개 |
| `includeInTasteProfile` | 발행 시 지역·장소 유형·만족 태그/점수를 낮은 가중치의 취향 신호로 쓸지. 기본 `true`. 본문·사진·EXIF·열람 수는 쓰지 않는다 |
| `photos[].lat/lng` | 호출자가 작성자가 아니면 `locationPrecision`을 적용한 값. `HIDDEN`이면 `null` |

사진 응답에는 EXIF 원본과 원본 파일명을 넣지 않는다.

**오류 코드**

| code | HTTP | 상황 |
|---|---:|---|
| `DIARY_PHOTO_INVALID` | 400 | 허용하지 않는 형식·크기이거나 검사 실패 |
| `DIARY_PHOTO_LIMIT_EXCEEDED` | 400 | 사진이 30장을 넘음 |
| `SHARE_SESSION_INVALID` | 401 | 여행기 공유 세션 cookie 없음·만료 |
| `FRIEND_REQUIRED` | 403 | 수락된 친구가 아님 |
| `DIARY_NOT_FOUND` | 404 | 없거나 볼 권한이 없음(구분하지 않음) |
| `DIARY_PHOTO_NOT_FOUND` | 404 | 이 여행기의 사진이 아님 |
| `DIARY_SHARE_LINK_NOT_FOUND` | 404 | 없는 여행기 공유 링크·token |
| `TRIP_NOT_ENDED` | 409 | 여행 종료일이 아직 지나지 않음 |
| `DIARY_ALREADY_EXISTS_FOR_TRIP` | 409 | 이 여행에 내 여행기가 이미 있음. `details.diaryId` |
| `DIARY_NOT_PUBLISHABLE` | 422 | 발행 조건 미충족. `details.missing` |
| `DIARY_SHARE_LINK_EXPIRED`, `DIARY_SHARE_LINK_REVOKED` | 410 | 여행기 공유 링크 만료·폐기 |

---

### 6-1. 여행기 만들기

> `호출: 참여자` · `⬜ 미구현`

```
POST /api/courses/{tripId}/diary
```

**Request Body** — 없거나 `{"title": "비 오는 날의 경주"}`

**Response `201 Created`** — 내 `Diary` (`status: DRAFT`)

- 제목 기본값은 코스 제목, 코스가 없으면 `"{시작일} 여행"`이다.
- `courseTitle`, `regionSigCd`, `visitedFrom`, `visitedTo`는 만들 때의 값을 복사해 둔다. 이후 여행이 바뀌거나 삭제돼도 여행기는 그대로다.
- "여행이 끝났다"는 `endDate < 오늘`(Asia/Seoul)이다.

| 오류 | HTTP | code |
|---|---:|---|
| 없거나 참여자가 아님 | 404 | `TRIP_NOT_FOUND` |
| 여행이 아직 끝나지 않음 | 409 | `TRIP_NOT_ENDED` |
| 내 여행기가 이미 있음 | 409 | `DIARY_ALREADY_EXISTS_FOR_TRIP` |

여행에서 탈퇴해도(3-9) 이미 쓴 여행기는 남는다. 여행이 삭제되면 `tripId`는 남되 코스 링크는 열리지 않는다.

---

### 6-2. 사진 올리기

> `호출: 작성자` · `⬜ 미구현`

```
POST /api/diaries/{diaryId}/photos
Content-Type: multipart/form-data
```

| Part | 필수 | 제약 |
|---|---|---|
| `files` | 예 | 1개 이상. 여행기 전체 합계 30장 이하. JPEG·PNG·WebP, 장당 10MB 이하 |

서버는 형식·크기·악성 파일을 검사하고, EXIF에서 촬영 시각·위치만 추출한 뒤 공개용 파일에서 EXIF를 제거한다.

**Response `201 Created`**

```json
{"photos": [{"photoId": 302, "url": "https://…", "thumbnailUrl": "https://…", "takenAt": null, "lat": null, "lng": null, "order": 1}]}
```

| 오류 | HTTP | code |
|---|---:|---|
| 파일 오류 | 400 | `DIARY_PHOTO_INVALID` (`details.fileIndex`) |
| 30장 초과 | 400 | `DIARY_PHOTO_LIMIT_EXCEEDED` |
| 없거나 작성자가 아님 | 404 | `DIARY_NOT_FOUND` |

한 파일이라도 실패하면 전체를 저장하지 않는다.

---

### 6-3. 사진 삭제

> `호출: 작성자` · `⬜ 미구현`

```
DELETE /api/diaries/{diaryId}/photos/{photoId}
```

| 오류 | HTTP | code |
|---|---:|---|
| 없거나 작성자가 아님 | 404 | `DIARY_NOT_FOUND`, `DIARY_PHOTO_NOT_FOUND` |

**Response `204 No Content`** — 대표 사진을 지우면 `coverPhotoId`는 남은 첫 사진, 없으면 `null`이 된다. 발행된 여행기의 마지막 사진은 지울 수 없다(`422 DIARY_NOT_PUBLISHABLE`).

---

### 6-4. 여행기 수정

> `호출: 작성자` · `⬜ 미구현`

```
PATCH /api/diaries/{diaryId}
```

**Request Body** — 보낸 필드만 바꾼다.

```json
{"title": "비 오는 날의 경주", "body": "…", "visibility": "FRIENDS", "locationPrecision": "CITY",
 "satisfaction": 5, "experienceTags": ["역사", "산책"], "includeInTasteProfile": true, "coverPhotoId": 301}
```

| 필드 | 제약 |
|---|---|
| `title` | trim 후 1~60자 |
| `body` | 최대 5000자 |
| `visibility`, `locationPrecision` | 위 값 목록 |
| `satisfaction` | 1~5 또는 `null` |
| `experienceTags` | 최대 5개, 태그 사전의 부분집합 |
| `coverPhotoId` | 이 여행기의 사진 |
| `photoOrder` | 이 여행기 사진 ID 전체를 원하는 순서로 나열한 배열. 누락·중복이 있으면 400 |

| 오류 | HTTP | code |
|---|---:|---|
| 값 오류 | 400 | `COMMON_INVALID_REQUEST` |
| 없거나 작성자가 아님 | 404 | `DIARY_NOT_FOUND` |
| 다른 여행기의 사진 ID | 404 | `DIARY_PHOTO_NOT_FOUND` |

**Response `200 OK`** — `Diary`. 발행 후에도 수정할 수 있다. `visibility`를 `LINK`에서 바꾸면 기존 공유 링크는 더 이상 열리지 않는다.

---

### 6-5. 여행기 발행

> `호출: 작성자` · `⬜ 미구현`

```
POST /api/diaries/{diaryId}/publish
```

**Response `200 OK`** — `Diary` (`status: PUBLISHED`, `publishedAt`). 이미 발행됐으면 그대로 200.

| 오류 | HTTP | code |
|---|---:|---|
| 제목 없음·사진 0장 | 422 | `DIARY_NOT_PUBLISHABLE` (`details.missing: ["TITLE", "PHOTO"]`) |

**Side effects** — `includeInTasteProfile`이 `true`면 취향 신호를 저장한다.

---

### 6-6. 여행기 조회

> `호출: 작성자 · 친구(FRIENDS·발행됨)` · `⬜ 미구현`

```
GET /api/diaries/{diaryId}
```

**Response `200 OK`** — `Diary`. 작성자가 아니면 `includeInTasteProfile`을 빼고 위치 정밀도를 적용한다.

| 오류 | HTTP | code |
|---|---:|---|
| 볼 권한 없음·없음 | 404 | `DIARY_NOT_FOUND` |

링크 소지자는 6-11로 본다.

---

### 6-7. 내 여행 지도

> `호출: 회원` · `⬜ 미구현`

```
GET /api/me/travel-map?from=2026-01-01&to=2026-12-31
```

| Query | 필수 | 설명 |
|---|---|---|
| `from`, `to` | 아니오 | 방문 날짜 범위. 생략하면 전체 |

**Response `200 OK`** — 내 여행기 핀(초안 포함)

```json
[
  {"diaryId": 31, "title": "비 오는 날의 경주", "courseTitle": "신라의 시간을 걷는 2일", "coverPhotoUrl": "https://…",
   "visitedAt": "2026-10-10", "lat": 35.856, "lng": 129.225, "locationPrecision": "CITY", "visibility": "FRIENDS", "status": "PUBLISHED"}
]
```

- 핀 위치는 대표 사진 위치, 없으면 지역 중심이다.
- 대표 사진이 없으면 `coverPhotoUrl`은 서비스 기본 이미지다. 다른 사진 URL이나 EXIF는 넣지 않는다.

---

### 6-8. 친구 여행 지도

> `호출: 수락된 친구` · `⬜ 미구현`

```
GET /api/friends/{userId}/travel-map?from=&to=
```

**Response `200 OK`** — 6-7과 같은 형식. 친구의 **발행된 `FRIENDS` 공개** 여행기만, 위치 정밀도를 적용한다. `HIDDEN`은 지역 중심에 표시하고 `lat/lng`를 지역 중심으로 준다.

| 오류 | HTTP | code |
|---|---:|---|
| 친구가 아님 | 403 | `FRIEND_REQUIRED` |

---

### 6-9. 여행기 공유 링크 발급·목록

> `호출: 작성자` · `⬜ 미구현`

```
POST /api/diaries/{diaryId}/share-links
GET  /api/diaries/{diaryId}/share-links
```

`GET`은 발급한 링크 목록(`{id, expiresAt, revoked, createdAt}[]`, 최신순, token 원문 없음)이다.

**Request Body**

```json
{"expiresInDays": 7}
```

**Response `201 Created`**

```json
{"id": 3, "token": "dl_…", "expiresAt": "2026-10-20T12:00:00", "createdAt": "2026-10-13T12:00:00"}
```

- 링크는 읽기 전용이며 그 여행기만 보여준다. 지도 전체 탐색 권한을 주지 않는다.
- 발행됐고 `visibility`가 `LINK`일 때만 발급·열람된다.

| 오류 | HTTP | code |
|---|---:|---|
| 기간 오류 | 400 | `INVALID_EXPIRES_IN_DAYS` |
| 없거나 작성자가 아님 | 404 | `DIARY_NOT_FOUND` |
| 발행 전이거나 `LINK`가 아님 | 422 | `DIARY_NOT_PUBLISHABLE` (`details.missing: ["PUBLISHED" \| "VISIBILITY_LINK"]`) |

---

### 6-10. 여행기 공유 링크 폐기

> `호출: 작성자` · `⬜ 미구현`

```
DELETE /api/diaries/{diaryId}/share-links/{linkId}
```

**Response `204 No Content`** — 이미 열린 세션도 즉시 무효다.

| 오류 | HTTP | code |
|---|---:|---|
| 없거나 이 여행기의 링크가 아님 | 404 | `DIARY_SHARE_LINK_NOT_FOUND` |

---

### 6-11. 여행기 공유 링크 열기

> `호출: 공개` · `⬜ 미구현`

```
GET /api/shared/diaries/{token}
```

**Response `303 See Other`**

```text
Location: /api/shared/diaries
Set-Cookie: diary_share_session=…; HttpOnly; SameSite=Lax; Path=/api/shared/diaries; Max-Age=7200
```

| 오류 | HTTP | code |
|---|---:|---|
| 다른 용도의 token | 400 | `TOKEN_AUDIENCE_MISMATCH` |
| 없는 token | 404 | `DIARY_SHARE_LINK_NOT_FOUND` |
| 만료·폐기 | 410 | `DIARY_SHARE_LINK_EXPIRED`, `DIARY_SHARE_LINK_REVOKED` |

---

### 6-12. 공유 여행기 조회

> `호출: 여행기 공유 링크 소지자` · `⬜ 미구현`

```
GET /api/shared/diaries
```

**Response `200 OK`** — `Diary` (작성자가 아닌 사람에게 보이는 형태)

| 오류 | HTTP | code |
|---|---:|---|
| cookie 없음·만료 | 401 | `SHARE_SESSION_INVALID` |
| 링크 만료·폐기, 또는 `LINK` 공개가 해제됨 | 410 | `DIARY_SHARE_LINK_EXPIRED`, `DIARY_SHARE_LINK_REVOKED` |

---

## 결정 필요

| ID | 항목 | 현재 초안 |
|---|---|---|
| T-1 | 여행기 사진 저장소 | 업로드 방식(서버 경유 multipart / S3 presigned URL), 파일 크기 상한 10MB, 썸네일 생성 주체, 그리고 `PRIVATE`·`FRIENDS` 여행기 사진 URL을 서명 URL(만료 있음)로 줄지. 공개 URL이면 공개 범위가 무력해진다 |
| T-6 | 여행기 삭제 | 여행기 삭제 API를 둘지. 초안: 없음 |
| T-7 | `LINK` 공개 여행기를 친구도 보나 | `visibility`가 값 하나라 `FRIENDS`와 `LINK`를 함께 쓸 수 없다. 초안: `LINK`는 친구에게도 보인다(더 넓은 공개) |
