# 온보딩 API

- 상태: implemented (회원 제출 및 WORK-04 비회원 참여자 제출)
- 갱신일: 2026-09-24
- 관련 기능 명세: [온보딩·재검사·임베딩 job](../features/onboarding.md)

온보딩 구현은 `profile` 모듈이 소유한다. 비회원 참여자 제출 경로와 guest session 계약은
[초대·공유 API](invitations-and-sharing.md)에 정의한다.

오류는 [공통 오류 응답](common-errors.md) 형식을 따른다. 이 문서에서 다루는 도메인 코드는 모두 HTTP 400이다
(`REGION_NOT_FOUND`도 지역 존재는 요청 유효성 문제로 취급해 400이며 404가 아니다).

| code | 의미 |
|---|---|
| `ONBOARDING_INVALID_QUESTION_VERSION` | questionVersion이 현재 서버 상수(`demo-mbti-v1`)와 다름 |
| `ONBOARDING_MISSING_QUESTION_ANSWER` | 1~12번 중 답이 없는 문항이 있음 |
| `ONBOARDING_DUPLICATE_QUESTION_ANSWER` | 같은 문항에 답이 두 번 이상 옴 |
| `ONBOARDING_INVALID_CHOICE` | choice가 1/2가 아님 |
| `ONBOARDING_INVALID_SCHEDULE_DENSITY` | scheduleDensity가 RELAXED/PACKED가 아님 |
| `ONBOARDING_TOO_MANY_EXPERIENCE_TAGS` | 경험 태그가 5개 초과 |
| `ONBOARDING_UNKNOWN_TAG` | 태그 사전에 없는 값 |
| `ONBOARDING_DUPLICATE_LIKED_REGION` | 같은 SIG_CD를 두 번 담음 |
| `ONBOARDING_TOO_MANY_LIKED_REGIONS` | 좋았던 여행지가 30개 초과 |
| `ONBOARDING_REGION_NOT_FOUND` | 존재하지 않는 SIG_CD |

## GET /api/onboarding/questions

- 목적: 여행 MBTI 12문항, 선택지·글자 매핑, 경험/제외 태그 사전, 일정 밀도 옵션을 반환한다.
- 인증: 불필요

### Responses

##### 200 OK

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

문구나 글자 매핑이 바뀌면 `questionVersion`도 함께 바뀐다 — 클라이언트는 제출 전 이 값을 그대로 제출에
동봉해야 한다.

---

## POST /api/onboarding/submissions

- 목적: 온보딩 답변을 제출해 MBTI 코드와 profileText를 계산하고 취향 임베딩 job을 등록한다.
- 인증: Bearer JWT required

### Request

```json
{
  "questionVersion": "demo-mbti-v1",
  "answers": [
    {"questionNumber": 1, "choice": 1},
    {"questionNumber": 2, "choice": 2}
  ],
  "scheduleDensity": "RELAXED",
  "experienceTags": ["바다", "카페"],
  "excludeTags": ["오래 걷기"],
  "likedTrips": [
    {"sigCd": "11110", "note": "경복궁이 좋았어요", "tags": ["역사"]}
  ]
}
```

| 필드 | 타입 | 필수 | 제약 |
|---|---|---|---|
| questionVersion | string | O | 현재 서버 상수와 일치해야 함 |
| answers | array | O | 정확히 12개, 1~12번 각각 한 번씩, choice는 1 또는 2 |
| scheduleDensity | string | O | `RELAXED` 또는 `PACKED` |
| experienceTags | string[] | X | 최대 5개, 경험 태그 사전의 부분집합 (기본 빈 배열) |
| excludeTags | string[] | X | 제외 태그 사전의 부분집합 (기본 빈 배열) |
| likedTrips | array | X | 최대 30개, sigCd 중복 불가 (기본 빈 배열) |
| likedTrips[].sigCd | string | O | `regions`에 존재해야 함 |
| likedTrips[].note | string | X | trim 후 최대 500자, 로그에는 남기지 않음 |
| likedTrips[].tags | string[] | X | 경험 태그 사전의 부분집합 |

### Responses

##### 201 Created

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

`tasteStatus`는 이 응답이 만들어지기 전에 임베딩 호출까지 이미 끝난 뒤의 **최종** 상태다.

- `READY`: 임베딩 성공, `user_taste_vectors`에 반영됨
- `PENDING`: 일시 장애(timeout/429/5xx)로 재시도 예약됨(`EmbeddingRetrySweeper`가 나중에 재시도)
- `FAILED`: 영구 실패(4xx, dimension 불일치, 재시도 소진) — 온보딩 자체는 여전히 성공

##### 400 Bad Request

형식 오류(공통 `COMMON_INVALID_REQUEST`) 또는 위 도메인 코드 표 중 하나.

#### Side effects

- `onboarding_submissions`/`onboarding_answers`/`liked_trips`에 각 1건/12건/N건을 생성한다.
- `users.latest_onboarding_submission_id`를 이 submission으로 바꾼다(재검사 시 이전 제출은 그대로 남음).
- `embedding_jobs`에 1건을 생성하고, 트랜잭션 커밋 후 임베딩을 호출한 결과로 상태를 갱신한다.
- 성공 시 `user_taste_vectors`를 생성/갱신한다.

#### 멱등성

멱등하지 않다 — 매 호출이 새 submission을 만든다(재검사).

---

## GET /api/onboarding/me

- 목적: 로그인 사용자의 최신 제출과 온보딩 완료 여부를 조회한다.
- 인증: Bearer JWT required

### Responses

##### 200 OK (제출 이력 있음)

```json
{
  "onboardingCompleted": true,
  "submission": {
    "submissionId": "6c9f4b1e-2f7a-4e0a-9c34-5f9d2c1a0000",
    "questionVersion": "demo-mbti-v1",
    "mbtiCode": "ISFP",
    "scheduleDensity": "RELAXED",
    "profileText": "...",
    "tasteStatus": "READY",
    "onboardingCompleted": true,
    "createdAt": "2026-09-21T20:00:00"
  }
}
```

##### 200 OK (제출 이력 없음)

```json
{
  "onboardingCompleted": false,
  "submission": null
}
```

##### 401 Unauthorized

인증 토큰이 없거나 유효하지 않다.

#### Related

- [작업 명세](../features/onboarding.md)
- [사용자 정보 주입](../conventions/유저-정보-주입.md)
