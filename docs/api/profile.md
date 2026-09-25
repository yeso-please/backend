# 2. 온보딩·친구

- 모듈: `profile`
- 질문·채점 기준: [부록](#부록-설문-문항과-채점-demo-mbti-v1)

## 온보딩

- 계약 상태: agreed
- 여행에 초대받은 사람도 회원으로 가입해 이 API로 설문을 마친 뒤 초대를 수락한다([4-5](trip.md#4-5-초대-링크-수락)).

**오류 코드** — 모두 400이다. 지역 코드가 없는 것도 요청 값 문제로 보고 404가 아니라 400으로 응답한다.

| code | 상황 |
|---|---|
| `ONBOARDING_INVALID_QUESTION_VERSION` | `questionVersion`이 현재 서버 값(`demo-mbti-v1`)과 다름 |
| `ONBOARDING_MISSING_QUESTION_ANSWER` | 1~12번 중 답이 없는 문항이 있음 |
| `ONBOARDING_DUPLICATE_QUESTION_ANSWER` | 같은 문항에 답이 두 번 이상 옴 |
| `ONBOARDING_INVALID_CHOICE` | `choice`가 1·2가 아님 |
| `ONBOARDING_INVALID_SCHEDULE_DENSITY` | `scheduleDensity`가 `RELAXED`·`PACKED`가 아님 |
| `ONBOARDING_TOO_MANY_EXPERIENCE_TAGS` | 경험 태그 5개 초과 |
| `ONBOARDING_UNKNOWN_TAG` | 태그 사전에 없는 값 |
| `ONBOARDING_DUPLICATE_LIKED_REGION` | 같은 `sigCd`를 두 번 담음 |
| `ONBOARDING_TOO_MANY_LIKED_REGIONS` | 좋았던 여행지 30개 초과 |
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
  "questionVersion": "demo-mbti-v1",
  "questions": [
    {
      "number": 1,
      "axis": "JP",
      "prompt": "여행을 떠날 때 계획은",
      "choice1": {"choice": 1, "text": "내가 걷는 길이 곧 여행코스", "letter": "P"},
      "choice2": {"choice": 2, "text": "계획은 필수", "letter": "J"}
    }
  ],
  "experienceTags": ["자연", "바다", "산", "산책", "골목", "역사", "시장", "로컬 음식", "카페", "휴식", "실내", "체험"],
  "maxExperienceTags": 5,
  "excludeTags": ["계단·경사 많은 곳", "물놀이", "야간 이동", "오래 걷기"],
  "scheduleDensityOptions": ["RELAXED", "PACKED"]
}
```

- `questions`는 12개다. 문구나 글자 매핑이 바뀌면 `questionVersion`이 바뀐다.
- 클라이언트는 받은 `questionVersion`을 그대로 제출에 담는다.
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
  "questionVersion": "demo-mbti-v1",
  "answers": [{"questionNumber": 1, "choice": 1}, {"questionNumber": 2, "choice": 2}],
  "scheduleDensity": "RELAXED",
  "experienceTags": ["바다", "카페"],
  "excludeTags": ["오래 걷기"],
  "likedTrips": [{"sigCd": "11110", "note": "경복궁이 좋았어요", "tags": ["역사"]}]
}
```

| 필드 | 타입 | 필수 | 제약 |
|---|---|---|---|
| `questionVersion` | `string` | 예 | 현재 서버 값과 일치 |
| `answers` | `array` | 예 | 정확히 12개, 1~12번 각각 한 번씩 |
| `answers[].questionNumber` | `number` | 예 | 1~12 |
| `answers[].choice` | `number` | 예 | 1 또는 2 |
| `scheduleDensity` | `string` | 예 | `RELAXED` \| `PACKED` |
| `experienceTags` | `string[]` | 아니오 | 최대 5개, 경험 태그 사전의 부분집합. 기본 `[]` |
| `excludeTags` | `string[]` | 아니오 | 제외 태그 사전의 부분집합. 기본 `[]` |
| `likedTrips` | `array` | 아니오 | 최대 30개, `sigCd` 중복 불가. 기본 `[]` |
| `likedTrips[].sigCd` | `string` | 예 | 존재하는 지역 |
| `likedTrips[].note` | `string` | 아니오 | trim 후 최대 500자. 로그에 남기지 않음 |
| `likedTrips[].tags` | `string[]` | 아니오 | 경험 태그 사전의 부분집합 |

**Response `201 Created`** — `OnboardingSubmission`

```json
{
  "submissionId": "6c9f4b1e-2f7a-4e0a-9c34-5f9d2c1a0000",
  "questionVersion": "demo-mbti-v1",
  "mbtiCode": "ISFP",
  "scheduleDensity": "RELAXED",
  "profileText": "MBTI: ISFP\n일정 밀도: RELAXED\n선호 경험: 바다, 카페\n제외 조건: 오래 걷기\n좋았던 여행지: 11110(역사):경복궁이 좋았어요",
  "tasteStatus": "READY",
  "onboardingCompleted": true,
  "createdAt": "2026-09-21T20:00:00"
}
```

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

- `onboarding_submissions` 1건, `onboarding_answers` 12건, `liked_trips` N건 생성. 이전 제출은 지우지 않는다.
- `users.latest_onboarding_submission_id`를 새 제출로 바꾼다. 추천은 최신 제출만 쓴다.
- `embedding_jobs` 1건 생성, 커밋 후 임베딩 호출. 성공하면 `user_taste_vectors` 생성·갱신.

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

> `호출: 회원` · `⬜ 미구현`

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

> `호출: 회원` · `⬜ 미구현`

```
GET /api/friend-links
```

**Response `200 OK`** — 최신순

```json
[{"id": 4, "expiresAt": "2026-10-27T12:00:00", "revoked": false, "acceptedCount": 2, "createdAt": "2026-10-20T12:00:00"}]
```

---

### 2-6. 친구 초대 링크 폐기

> `호출: 회원(링크 주인)` · `⬜ 미구현`

```
DELETE /api/friend-links/{id}
```

**Response `204 No Content`** — 이후 수락을 막는다. 이미 맺은 친구 관계는 그대로다.

| 오류 | HTTP | code |
|---|---:|---|
| 없거나 내 링크가 아님 | 404 | `FRIEND_LINK_NOT_FOUND` |

---

### 2-7. 친구 초대 링크 미리보기

> `호출: 공개` · `⬜ 미구현`

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

> `호출: 회원` · `⬜ 미구현`

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

> `호출: 회원` · `⬜ 미구현`

```
GET /api/friends
```

**Response `200 OK`** — 친구가 된 순서 최신순

```json
[{"userId": 12, "nickname": "여행친구", "since": "2026-10-20T12:05:00"}]
```

---

### 2-10. 친구 끊기

> `호출: 회원` · `⬜ 미구현`

```
DELETE /api/friends/{userId}
```

**Response `204 No Content`** — 양쪽 모두 즉시 서로의 `FRIENDS` 공개 여행기·지도를 볼 수 없다. 다시 친구가 되려면 새 링크를 수락한다.

| 오류 | HTTP | code |
|---|---:|---|
| 친구가 아님 | 404 | `FRIEND_NOT_FOUND` |

---

---

## 부록. 설문 문항과 채점 (`demo-mbti-v1`)

이 부록이 질문·선택지·채점의 단일 기준이다. 질문 문구나 글자 매핑을 바꾸면 새 version을 만들고 기존 제출을 재해석하지 않는다.

### 여행 MBTI 12문항

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

### 일정 밀도 선호

MBTI 12문항과 별도의 필수 선택이다. MBTI 글자 계산에는 포함하지 않는다.

| 값 | 사용자 문구 | 서버 의미 |
|---|---|---|
| `RELAXED` | 여유롭게 둘러볼래요 | 하루 관광지 최대 4곳 |
| `PACKED` | 가능한 많이 둘러볼래요 | 하루 관광지 최대 6곳 |

이는 기본 선호이며 지역 정하기(3-7)·코스 재생성(5-1)에서 여행별로 바꿀 수 있다. 최대 개수는 보장이 아니다. 이동·식사·체류시간상 불가능하면 더 적은 장소와 `DENSITY_TARGET_NOT_MET` 경고를 반환한다.

### 경험·제외 조건

경험 태그는 최대 5개다.

```text
자연, 바다, 산, 산책, 골목, 역사, 시장, 로컬 음식, 카페, 휴식, 실내, 체험
```

제외 조건은 복수 선택 가능하다.

```text
계단·경사 많은 곳, 물놀이, 야간 이동, 오래 걷기
```

### 좋았던 여행지

- 0~30개, 같은 `SIG_CD`는 한 제출에 한 번만 허용한다.
- 각 항목은 `sigCd`, 선택적 `note`, 위 경험 태그의 부분집합을 가진다.
- 알려지지 않은 지역 코드는 400이다. 미입력은 중립이며 싫어한다는 뜻이 아니다.
- 메모는 임베딩 입력에 쓰지만 원문을 로그에 남기지 않는다.

### 제출과 재검사

- 제출은 회원(`user_id`)에게 귀속한다.
- 모든 제출은 `scheduleDensity`로 `RELAXED|PACKED` 중 하나를 가진다.
- 제출은 immutable이다. 프로필 재검사는 새 submission을 만들고 최신 완료 ID만 바꾼다.
- 질문 version이 다르면 400 `ONBOARDING_INVALID_QUESTION_VERSION`이다. 클라이언트는 2-1로 현재 질문을 다시 받는다.
