# 2. 온보딩·친구

- 모듈: `profile`
- 현재 설문·입력 근거: [AI Hub 여행자 설문](#2-1-온보딩-질문)

## 온보딩

- 계약 상태: agreed
- 여행에 초대받은 사람도 회원으로 가입해 이 API로 설문을 마친 뒤 초대를 수락한다([4-5](trip.md#4-5-초대-링크-수락)).

**오류 코드** — 모두 400이다. 지역 코드가 없는 것도 요청 값 문제로 보고 404가 아니라 400으로 응답한다.

| code | 상황 |
|---|---|
| `ONBOARDING_INVALID_QUESTION_VERSION` | 지원하지 않는 `questionVersion`. 현재 `aihub-traveler-v2`, 호환 `aihub-traveler-v1`만 받는다. 구형 `demo-mbti-v1`도 이 코드로 거부한다 |
| `ONBOARDING_INVALID_TRAVEL_STYLES` | 스타일 1·3·5·6 중 누락이 있거나 점수가 1~7 밖 |
| `ONBOARDING_INVALID_TRAVEL_MOTIVE` | 동기 코드가 1~9 밖, 중복 또는 3개 초과 |
| `ONBOARDING_MISSING_QUESTION_ANSWER` | v2에서 `mbtiAnswers`에 1~12번 중 빠진 문항이 있음 |
| `ONBOARDING_INVALID_CHOICE` | v2에서 `mbtiAnswers`의 문항 번호가 1~12 밖이거나 답이 1·2가 아님 |
| `ONBOARDING_INVALID_SCHEDULE_DENSITY` | `scheduleDensity`가 `RELAXED`·`PACKED`가 아님 |
| `ONBOARDING_UNKNOWN_TAG` | 태그 사전에 없는 값 |
| `ONBOARDING_DUPLICATE_LIKED_REGION` | 같은 `sigCd`를 두 번 담음 |
| `ONBOARDING_TOO_MANY_LIKED_REGIONS` | 선호 지역 3개 초과 |
| `ONBOARDING_REGION_NOT_FOUND` | 존재하지 않는 `sigCd` |

---

### 2-1. 온보딩 질문

> `호출: 공개` · `✅ 구현`

```
GET /api/onboarding/questions
```

**Response `200 OK`**

```json
{
  "questionVersion": "aihub-traveler-v2",
  "travelStyles": [
    {"number": 1, "leftPole": "자연", "rightPole": "도시", "minValue": 1, "maxValue": 7, "neutralValue": 4, "evidence": "OFFICIAL"},
    {"number": 3, "leftPole": "새로운 지역", "rightPole": "익숙한 지역", "minValue": 1, "maxValue": 7, "neutralValue": 4, "evidence": "INFERRED"},
    {"number": 5, "leftPole": "휴양과 휴식", "rightPole": "체험 활동", "minValue": 1, "maxValue": 7, "neutralValue": 4, "evidence": "INFERRED"},
    {"number": 6, "leftPole": "잘 알려지지 않은 곳", "rightPole": "잘 알려진 명소", "minValue": 1, "maxValue": 7, "neutralValue": 4, "evidence": "INFERRED"}
  ],
  "travelMotives": [
    {"code": 1, "label": "일상에서 벗어나기"}, {"code": 2, "label": "휴식과 재충전"},
    {"code": 3, "label": "동반자와 추억 만들기"}, {"code": 4, "label": "나를 돌아보기"},
    {"code": 5, "label": "SNS에 올릴 사진"}, {"code": 6, "label": "운동과 건강"},
    {"code": 7, "label": "새로운 경험"}, {"code": 8, "label": "역사와 문화 탐방"},
    {"code": 9, "label": "특별한 날 기념"}
  ],
  "maxTravelMotives": 3,
  "maxLikedRegions": 3,
  "excludeTags": ["계단·경사 많은 곳", "물놀이", "야간 이동", "오래 걷기"],
  "scheduleDensityOptions": ["RELAXED", "PACKED"],
  "mbtiQuestions": [
    {"number": 1, "question": "여행을 떠날 때 계획은", "choices": [{"choice": 1, "label": "내가 걷는 길이 곧 여행코스"}, {"choice": 2, "label": "계획은 필수"}]},
    "… 12번까지"
  ]
}
```

- 현재 설문은 AI Hub 여행로그의 스타일·동기·선호 시군구 형식을 사용한다. 새 입력은 MBTI 문항이나 경험 태그 대신 구조화 신호를 쓴다.
- AI Hub 데이터 카드에서 원문 확인이 된 스타일 1만 `OFFICIAL`이다. 3·5·6의 좌우 의미는 행동 데이터에서 추정한 내부 안내 문구이며 AI Hub 원문을 인용한 것이 아니다(`INFERRED`). 문구가 공식 원문처럼 보이면 안 되며, 실제 원문 확인 후 새 `questionVersion`으로 바꾼다.
- 모델 관련성이 확인된 스타일 1·3·5·6만 현재 설문에 포함한다. 2(숙박/당일), 4(숙소 가격), 7(계획/즉흥)은 관광지 추천 관련성이 낮고 8(사진)은 방향이 확인되지 않아 수집하지 않는다.
- 동기는 AI Hub 코드 1~9를 표시한다. 코드 10(기타)은 현재 모델 템플릿에서 사용하지 않아 제외한다.
- `mbtiQuestions`는 여행 MBTI 12문항이다(부록). **선택지가 어느 글자(E/I 등)로 이어지는지는 내려주지 않는다.** 화면이 답을 유도하지 않게 하기 위해서다. MBTI는 **표시용 분류**이며 임베딩·추천·추천 이유에 쓰지 않는다.
- 클라이언트는 받은 `questionVersion`을 그대로 제출에 담는다. 구형 `demo-mbti-v1` 제출은 받지 않는다(기존 제출 행·벡터는 보존).
- `RELAXED`는 "여유롭게 둘러볼래요"(하루 관광지 최대 4곳), `PACKED`는 "가능한 많이 둘러볼래요"(최대 6곳)로 표시한다.

---

### 2-2. 온보딩 제출

> `호출: 회원` · `✅ 구현`

```
POST /api/onboarding/submissions
```

최초 온보딩과 프로필의 "여행 성향 다시 검사"가 같은 API다. 매 호출이 새 제출을 만든다.

**Request Body**

```json
{
  "questionVersion": "aihub-traveler-v2",
  "travelStyles": {"1": 1, "3": 4, "5": 6, "6": 7},
  "travelMotives": [2, 7],
  "likedRegions": ["11110", "41110"],
  "scheduleDensity": "RELAXED",
  "excludeTags": ["오래 걷기"],
  "mbtiAnswers": {"1": 1, "2": 2, "3": 1, "4": 2, "5": 1, "6": 1, "7": 2, "8": 2, "9": 1, "10": 1, "11": 2, "12": 1}
}
```

| 필드 | 타입 | 필수 | 제약 |
|---|---|---|---|
| `questionVersion` | `string` | 예 | `aihub-traveler-v2`(현재) 또는 `aihub-traveler-v1`(호환, MBTI 없음) |
| `travelStyles` | `object<number,number>` | 예 | 키는 1·3·5·6 모두, 각 점수 1~7. 4는 중립 |
| `travelMotives` | `number[]` | 아니오 | 코드 1~9 중 중복 없이 최대 3개. 기본 `[]` |
| `likedRegions` | `string[]` | 아니오 | 존재하는 `SIG_CD`, 중복 없이 최대 3개. 기본 `[]` |
| `scheduleDensity` | `string` | 예 | `RELAXED` \| `PACKED` |
| `excludeTags` | `string[]` | 아니오 | 제외 태그 사전의 부분집합. 기본 `[]` |
| `mbtiAnswers` | `object<number,number>` | v2면 예 | 키는 1~12 모두, 값은 선택지 1 또는 2. v1이면 무시한다 |

`scheduleDensity`와 `excludeTags`는 여행 일정·필터 규칙에 사용하고 임베딩 입력에는 포함하지 않는다. `mbtiAnswers`로 계산한 `mbtiCode`도 표시용이며 임베딩 입력이 아니다. AI에 보내는 프로필은 `travelStyles`, `travelMotives`, `likedRegions`의 구조화 객체이며 문장 생성은 AI 서비스가 소유한다.

**Response `201 Created`** — `OnboardingSubmission`

```json
{
  "submissionId": "6c9f4b1e-2f7a-4e0a-9c34-5f9d2c1a0000",
  "questionVersion": "aihub-traveler-v2",
  "mbtiCode": "ESTP",
  "scheduleDensity": "RELAXED",
  "profileText": "자연을 매우 선호, 체험 활동을 꽤 선호, 잘 알려진 명소를 매우 선호하는 여행자. 여행에서 원하는 것은 휴식과 재충전, 새로운 경험. 좋아하는 여행지는 서울특별시 종로구, 경기도 수원시.",
  "travelStyles": {"1": 1, "3": 4, "5": 6, "6": 7},
  "travelMotives": [2, 7],
  "likedRegions": ["11110", "41110"],
  "tasteStatus": "READY",
  "onboardingCompleted": true,
  "createdAt": "2026-09-21T20:00:00"
}
```

- `mbtiCode`: v2면 `mbtiAnswers`로 계산한 4글자(부록 규칙), v1이면 `null`.

`tasteStatus`는 임베딩 호출이 끝난 뒤의 최종 상태다. 임베딩이 실패해도 온보딩은 성공한다.

| 값 | 뜻 |
|---|---|
| `READY` | 임베딩 성공, 추천에 반영됨 |
| `PENDING` | 일시 장애(timeout·429·5xx)로 재시도 예약됨 |
| `FAILED` | 영구 실패(4xx·차원 불일치·재시도 소진). 추천은 폴백을 쓴다 |

| 오류 | HTTP | code |
|---|---:|---|
| 형식 오류 | 400 | `COMMON_INVALID_REQUEST` |
| 도메인 규칙 위반 | 400 | 위 오류 코드 표 |

**Side effects**

- `onboarding_submissions` 1건, `onboarding_answers` 스타일 4건, `liked_trips` 최대 3건 생성. 이전 제출은 지우지 않는다.
- `users.latest_onboarding_submission_id`를 새 제출로 바꾼다. 추천은 최신 제출만 쓴다.
- v2면 `onboarding_submissions.mbti_answers`에 12개 답을 저장한다(문항 번호가 스타일 1·3·5·6과 겹쳐 `onboarding_answers`에 넣지 않는다).
- `embedding_jobs` 1건 생성, 커밋 후 AI Hub 구조화 프로필(스타일·동기·선호 지역)을 template v2로 호출한다. MBTI는 보내지 않는다. 성공하면 `user_taste_vectors` 생성·갱신.
- template v1로 남아 있던 구형 job은 처리하지 않고 영구 실패(`UNSUPPORTED_TEMPLATE_VERSION`)로 끝낸다. 과거 제출 행과 벡터는 지우거나 재해석하지 않는다. 그 회원은 재검사하면 템플릿 v2 벡터가 생긴다.

**멱등성** — 없다. 호출마다 새 제출이다.

---

### 2-3. 내 온보딩 결과

> `호출: 회원` · `✅ 구현`

```
GET /api/onboarding/me
```

**Response `200 OK`**

```json
{"onboardingCompleted": true, "submission": { "...": "2-2의 OnboardingSubmission" }}
```

제출 이력이 없으면 `{"onboardingCompleted": false, "submission": null}`이다.

---

## 친구

- 계약 상태: agreed
- 목적: 친구 목록에서 여행에 바로 초대하기([4-6](trip.md#4-6-친구-초대)) 위한 상호 친구 관계. 추가 기능인 여행기의 `FRIENDS` 공개·친구 여행 지도([6장](trip.md#6-여행기사진-지도))도 이 관계를 쓴다. 전체 지도 공개·피드·댓글·좋아요는 없다.
- 방식: **친구 초대 링크**. 회원이 링크를 만들어 카카오톡 공유나 링크 복사로 보내고, 받은 회원이 수락하면 바로 친구가 된다. 링크를 만든 사람은 발급으로, 받은 사람은 수락으로 동의하므로 별도 승인 단계가 없다. 사용자 검색·ID 입력은 없다.
- 모델: 기존 `friendships`(`profile` 모듈)를 쓴다. 링크 수락은 `ACCEPTED`로 바로 저장하며 `PENDING`은 쓰지 않는다. FRIENDS 공개 조회마다 서버가 관계를 다시 확인한다.

**token** — `fl_` 접두사, 256bit CSPRNG, DB에는 SHA-256 해시만. 원문은 발급 응답에서 한 번만 준다. 기본 7일(1~30일), 폐기 전까지 여러 명이 수락할 수 있다.

**오류 코드**

| code | HTTP | 상황 |
|---|---:|---|
| `INVALID_EXPIRES_IN_DAYS` | 400 | `expiresInDays`가 1~30 밖 |
| `FRIEND_LINK_SELF` | 400 | 내가 만든 링크를 내가 수락 |
| `TOKEN_AUDIENCE_MISMATCH` | 400 | 다른 용도의 token |
| `FRIEND_LINK_NOT_FOUND` | 404 | 없는 링크이거나 내 링크가 아님 |
| `FRIEND_NOT_FOUND` | 404 | 친구가 아닌 사용자 |
| `FRIEND_LINK_EXPIRED`, `FRIEND_LINK_REVOKED` | 410 | 링크 만료·폐기 |

---

### 2-4. 친구 초대 링크 발급

> `호출: 회원` · `✅ 구현`

```
POST /api/friend-links
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
{"id": 4, "token": "fl_9aZ...", "expiresAt": "2026-10-27T12:00:00", "createdAt": "2026-10-20T12:00:00"}
```

프론트는 `token`으로 링크를 만들어 카카오톡 공유 UI나 복사로 보낸다. 서버는 메시지를 보내지 않는다.

---

### 2-5. 내 친구 초대 링크 목록

> `호출: 회원` · `✅ 구현`

```
GET /api/friend-links
```

**Response `200 OK`** — 최신순. 만료·폐기된 링크도 포함한다(`revoked`, `expiresAt`로 구분). `acceptedCount`는 이 링크로 새로 친구가 된 수이며, 이미 친구였던 사람의 재수락은 세지 않는다.

```json
[{"id": 4, "expiresAt": "2026-10-27T12:00:00", "revoked": false, "acceptedCount": 2, "createdAt": "2026-10-20T12:00:00"}]
```

---

### 2-6. 친구 초대 링크 폐기

> `호출: 회원(링크 주인)` · `✅ 구현`

```
DELETE /api/friend-links/{id}
```

**Response `204 No Content`** — 이후 수락을 막는다. 이미 맺은 친구 관계는 그대로다.

| 오류 | HTTP | code |
|---|---:|---|
| 없거나 내 링크가 아님 | 404 | `FRIEND_LINK_NOT_FOUND` |

---

### 2-7. 친구 초대 링크 미리보기

> `호출: 공개` · `✅ 구현`

```
GET /api/friend-links/by-token/{token}
```

로그인 전에도 "누가 보낸 링크인지"를 보여주기 위한 최소 정보.

**Response `200 OK`**

```json
{"valid": true, "inviterNickname": "여행친구"}
```

| 오류 | HTTP | code |
|---|---:|---|
| 다른 용도의 token | 400 | `TOKEN_AUDIENCE_MISMATCH` |
| 없는 token | 404 | `FRIEND_LINK_NOT_FOUND` |
| 만료·폐기 | 410 | `FRIEND_LINK_EXPIRED`, `FRIEND_LINK_REVOKED` |

---

### 2-8. 친구 초대 수락

> `호출: 회원` · `✅ 구현`

```
POST /api/friend-links/by-token/{token}/accept
```

요청 body 없음. 비회원이면 프론트가 로그인·가입 후 이 요청을 보낸다.

**Response `201 Created`** — 새 친구 관계. 이미 친구면 `200 OK`로 같은 응답(멱등).

```json
{"userId": 12, "nickname": "여행친구", "since": "2026-10-20T12:05:00"}
```

| 오류 | HTTP | code |
|---|---:|---|
| 내 링크 | 400 | `FRIEND_LINK_SELF` |
| 다른 용도의 token | 400 | `TOKEN_AUDIENCE_MISMATCH` |
| 없는 token | 404 | `FRIEND_LINK_NOT_FOUND` |
| 만료·폐기 | 410 | `FRIEND_LINK_EXPIRED`, `FRIEND_LINK_REVOKED` |

**Side effects** — `friendships`에 `ACCEPTED` 1건(두 사용자 쌍당 하나).

---

### 2-9. 친구 목록

> `호출: 회원` · `✅ 구현`

```
GET /api/friends
```

**Response `200 OK`** — 친구가 된 순서 최신순

```json
[{"userId": 12, "nickname": "여행친구", "since": "2026-10-20T12:05:00"}]
```

---

### 2-10. 친구 끊기

> `호출: 회원` · `✅ 구현`

```
DELETE /api/friends/{userId}
```

**Response `204 No Content`** — 양쪽 모두 즉시 서로의 `FRIENDS` 공개 여행기·지도를 볼 수 없다. 다시 친구가 되려면 새 링크를 수락한다.

| 오류 | HTTP | code |
|---|---:|---|
| 친구가 아님 | 404 | `FRIEND_NOT_FOUND` |

---

---

## 설정

- 계약 상태: agreed
- 목적: 코스를 만들 때 취향을 반영할지의 **기본값**을 프로필에 저장한다. 코스를 만들 때마다 고르지 않게 해 사용자의 고민을 줄인다(2026-09-27 팀 회의).
- `courseTasteMode`: `TASTE`(취향 반영 랜덤, 기본) \| `RANDOM`(완전 랜덤). 코스 생성(5-1)은 요청에 `tasteMode`가 없으면 **요청한 사람**의 이 설정을 쓴다.

### 2-11. 내 설정

> `호출: 회원` · `✅ 구현`

```
GET /api/me/preferences
```

**Response `200 OK`** — 설정을 바꾼 적이 없으면 기본값이다.

```json
{"courseTasteMode": "TASTE"}
```

---

### 2-12. 설정 바꾸기

> `호출: 회원` · `✅ 구현`

```
PATCH /api/me/preferences
```

**Request Body**

```json
{"courseTasteMode": "RANDOM"}
```

| 필드 | 타입 | 필수 | 제약 |
|---|---|---|---|
| `courseTasteMode` | `string` | 예 | `TASTE` \| `RANDOM` |

**Response `200 OK`** — 바뀐 설정(2-11과 같은 모양)

| 오류 | HTTP | code |
|---|---:|---|
| 값이 없거나 `TASTE`·`RANDOM`이 아님 | 400 | `COMMON_INVALID_REQUEST` (`fieldErrors`) |

---

## 부록. 여행 MBTI 12문항 (`aihub-traveler-v2`)

v2 온보딩의 MBTI 문항이다. 문구를 바꾸면 새 `questionVersion`을 만든다. MBTI는 **표시용 분류**이며 임베딩·추천·추천 이유에 쓰지 않는다(2026-10-10). 2-1 응답의 `mbtiQuestions`에는 질문과 선택지 문구만 내려가고, 아래의 "→ 글자"는 서버만 안다.

| No | 축 | 질문 | 선택 1 → 글자 | 선택 2 → 글자 |
|---:|---|---|---|---|
| 1 | JP | 여행을 떠날 때 계획은 | 내가 걷는 길이 곧 여행코스 → P | 계획은 필수 → J |
| 2 | JP | 여행 경비는 | 당장 국제거지만 안되면 되지! → P | 걸어다니는 계산기로 변신 → J |
| 3 | JP | 여행을 다녀온 후 | 홈스윗홈.. 침대로 점프! → P | 캐리어를 열고 물건을 정리한다 → J |
| 4 | JP | 여행지에서 식사할 때 | 유~명한 맛집을 작정하고 노리는 헌터 → J | 처음 본 순간 사랑에 빠진 길거리 가게 → P |
| 5 | SN | 여행지에서 길을 잃었을 때 | 왔던 길로 돌아가는 헨젤과 그레텔st. → S | 자꾸 걸어 나가면 길이 있겠지, 지구는 둥그니까 → N |
| 6 | SN | 화려한 건축물을 보며 드는 생각은 | "어떤 방법으로 지었을까?" 고민한다 → S | "와 멋있다..." 감탄한다 → N |
| 7 | TF | 아침에 늦잠 잔 친구에게 | "여행이 역시 피곤하지." → F | "내일은 시간 지키자." → T |
| 8 | TF | 친구에게 차 사고가 났다고 전화 왔을 때 나의 대답은 | "괜찮아? ㅠㅠ 다친 데는 없어?" → F | "보험 들었어?" → T |
| 9 | TF | 친구가 쓸데없는 기념품을 살 때 | "그래 니가 행복하다면..." → F | "그거 결국 쓰레기 된다" → T |
| 10 | EI | 나는 여행지를 선택할 때 주로 | 사람이 많은 도시로 → E | 나무가 많은 자연으로 → I |
| 11 | EI | 숙소를 구할 때 | 저녁에 바비큐 파티를 여는 곳 → E | 조용하고 아늑한 곳 → I |
| 12 | EI | 여행지에 대한 감상을 | 말로 내뱉어야 직성이 풀린다 → E | 내 마음 속에 저_장, 마음에 담고 느낀다 → I |

축별 선택 글자의 개수를 비교한다. 동점이면 뒤 글자 `I/N/F/P`를 선택한다. 최종 코드는 `EI + SN + TF + JP` 순서다.

`mbtiAnswers`가 1~12번을 모두 담고 각 값이 1 또는 2여야 계산한다. 빠지면 `ONBOARDING_MISSING_QUESTION_ANSWER`, 번호·값이 범위 밖이면 `ONBOARDING_INVALID_CHOICE`다.

### 일정 밀도

| 값 | 사용자 문구 | 서버 의미 |
|---|---|---|
| `RELAXED` | 여유롭게 둘러볼래요 | 하루 목표 관광지 4곳 |
| `PACKED` | 가능한 많이 둘러볼래요 | 하루 목표 관광지 6곳 |

기본 선호이며 지역 정하기(3-7)·코스 재생성(5-1)에서 여행별로 바꿀 수 있다. 목표 개수는 보장이 아니다.

### 경험 태그 사전

온보딩에서는 더 이상 받지 않지만 여행기(6장)의 `experienceTags` 사전으로 쓴다.

```text
자연, 바다, 산, 산책, 골목, 역사, 시장, 로컬 음식, 카페, 휴식, 실내, 체험
```

### 구형 설문 (`demo-mbti-v1`, 폐기)

구형 설문(MBTI 12문항 + 경험 태그 + 좋았던 여행지 메모, 임베딩 템플릿 1)은 2026-10-10에 폐기했다. 제출 API는 이 버전을 `ONBOARDING_INVALID_QUESTION_VERSION`으로 거부한다. 이미 저장된 제출 행·답·좋았던 여행지·벡터는 지우거나 재해석하지 않는다.
