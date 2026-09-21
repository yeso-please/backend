# MVP REST API 계약

상태: 제품 결정 반영, 구현 전. base path는 `/api`. 날짜는 `YYYY-MM-DD`, 시간은 지역 현지 `HH:mm`, 서버 저장 시 시간대는 `Asia/Seoul`이다. ID는 양의 정수, 지역은 5자리 `SIG_CD` 문자열이다.

인증 API를 제외한 소유자 API는 `Authorization: Bearer {accessToken}`이 필요하다. refresh token은 HttpOnly cookie다. 공유 API는 `X-Share-Token` 헤더를 사용하고 URL query에 토큰을 반복 노출하지 않는다. 공통 오류는 `{code,message,fieldErrors?,details?}`다.

## 인증

| Method | Path | Request | Success | Failure |
|---|---|---|---|---|
| POST | `/auth/signup` | `{email,password,nickname}` | 201 `AuthResponse` + refresh cookie | 400, 409 |
| POST | `/auth/login` | `{email,password}` | 200 `AuthResponse` + refresh cookie | 401 |
| POST | `/auth/refresh` | cookie | 200 새 access + 회전 cookie | 401 |
| POST | `/auth/logout` | cookie | 204, token 폐기 | 반복 호출도 204 |
| GET | `/users/me` | - | 200 사용자·온보딩 상태 | 401 |

`AuthResponse`:

```json
{"user":{"id":1,"email":"user@example.com","nickname":"여행자"},"accessToken":"<jwt>","expiresInSeconds":1800,"onboardingCompleted":false}
```

## 온보딩

| Method | Path | Auth | Request/Response |
|---|---|---|---|
| GET | `/onboarding/questions` | public | `{version,questions,tags,excludes}` |
| GET | `/onboarding/me` | user | 최신 제출과 결과 |
| POST | `/onboarding/submissions` | user | `OnboardingSubmission` → 201 |
| POST | `/invite-participants/{participantId}/onboarding` | invite token | 같은 제출 → 200 READY |

```json
{
  "questionVersion": "demo-mbti-v1",
  "answers": [{"questionNo":1,"choice":2}],
  "scheduleDensity": "RELAXED",
  "experienceTags": ["자연","산책"],
  "excludeTags": ["오래 걷기"],
  "likedTrips": [{"sigCd":"47130","note":"유적과 야경이 좋았어요","tags":["역사"]}]
}
```

12문항이 모두 한 번씩 있어야 하고 `scheduleDensity`는 `RELAXED|PACKED`다. 성공 응답은 `{submissionId,mbtiCode,scheduleDensity,profileText,tasteStatus:"PENDING|READY|FAILED",onboardingCompleted:true}`다.

## 여행 맥락·날짜 중복 차단·초대

| Method | Path | Auth | 핵심 계약 |
|---|---|---|---|
| GET | `/trips/unavailable-dates?from=&to=` | user | 확정 여행 때문에 선택 불가능한 날짜 구간 |
| POST | `/trips/context/check` | user | `{startDate,nights}` → 계산 날짜·`available`·충돌 목록 |
| POST | `/trips` | user | 중복 없는 context로 DRAFT 생성; 중복은 409 |
| POST | `/trips/{tripId}/invites` | owner | `{permission:"VIEW|EDIT",expiresInDays:7}` → 201 초대 URL/token 1회 반환 |
| PATCH | `/trips/{tripId}/invites/{inviteId}` | owner | 권한·만료 변경 또는 폐기 |
| GET | `/invites/{token}` | public | 여행 요약·참여 상태·만료 확인 |
| POST | `/invites/{token}/participants` | public | `{displayName}` → participant와 제한된 onboarding token |

중복 확인 응답 예시:

```json
{"startDate":"2026-10-10","endDate":"2026-10-12","nights":2,"days":3,"available":false,"conflicts":[{"code":"TRIP_DATE_OVERLAP","tripId":8,"title":"제주 여행","startDate":"2026-10-11","endDate":"2026-10-13"}]}
```

## 지역·추첨·카드

| Method | Path | Auth | 핵심 계약 |
|---|---|---|---|
| GET | `/regions?days=3&scheduleDensity=RELAXED` | user | 250개 지역과 `drawEligible`, 제외 사유 |
| POST | `/discovery/draw` | user | 아래 요청 → 추첨 결과·경고 |
| GET | `/regions/{sigCd}/card?days=3` | user/share | 지역 소개·대표 이미지·랜드마크·출처 |

```json
{
  "tripId":42,
  "mode":"CONDITIONAL",
  "conditions":["DISTANCE","MY_TASTE","COMPANION_TASTE"],
  "scheduleDensity":"RELAXED"
}
```

날짜·출발 위치·이동수단·참여자는 `tripId`의 서버 상태에서 읽는다. 응답은 `{tripId,regionId,province,city,startDate,endDate,scheduleDensity,modeApplied,appliedConditions,ignoredConditions,warnings}`다. `FULL_RANDOM`에서는 `conditions`를 생략한다. 리롤 전용 API나 제외 지역 필드는 없다.

지역 카드에는 `introduction`, `introductionStatus:"APPROVED"`, `heroImage`, `characteristics`, `historyHighlights`, `landmarks`, `sources`, `updatedAt`을 포함한다. 준비되지 않은 지역은 422 `REGION_CONTENT_NOT_READY`다.

## 코스 생성·대체 후보

| Method | Path | Auth | 핵심 계약 |
|---|---|---|---|
| POST | `/courses/draft` | owner user | tripId·밀도 override·도착/출발·식사시간 → `CourseDraft` |
| POST | `/courses/draft/rebalance` | user/share EDIT | 수정한 초안 검증·재배치 |
| POST | `/courses/alternatives` | user/share EDIT | 현재 코스 제외, 유형별 개인화 후보 |
| GET | `/regions/{sigCd}/attractions?bbox=&category=&cursor=&limit=` | user/share | 지도 핀용 관광지 목록 |
| GET | `/attractions/{id}` | user/share | 상세·이미지·이용정보·예상 체류시간·출처 |

```json
{
  "tripId":42,
  "scheduleDensity":"RELAXED",
  "regionArrivalTime":null,"regionDepartureTime":null,
  "mealPreferences":{"lunchStart":"12:00","dinnerStart":"18:00","durationMinutes":60}
}
```

`CourseDraft`는 `title`, `titleSource:"LLM|RULE"`, `assumptions`, `days[]`, `warnings`, `recommendationMode:"PERSONALIZED|TOUR_OFFICIAL|RULE_BASED"`를 포함한다. 각 날짜의 item은 다음 중 하나다.

```json
[
  {"type":"ATTRACTION","attractionId":101,"category":"역사·문화","startTime":"13:00","durationMinutes":90,"travelFromPreviousMinutes":25,"estimated":true,"reason":"역사 선호와 맞아요"},
  {"type":"MEAL","meal":"DINNER","startTime":"18:00","durationMinutes":60,"restaurant":null}
]
```

대체 후보 요청은 `{draft,currentAttractionId,category?,limit:10}`이며 같은 지역·현재 코스에 없는 추천 가능 관광지만 반환한다. 재배치 operation은 `ADD|REPLACE|REMOVE|MOVE`이며 ADD도 전체 시간·식사·이동·밀도 상한을 다시 검사한다.

지도 목록 item은 `{attractionId,name,thumbnailUrl,category,lat,lng,recommendable}`이다. 상세 응답에 `description,images,useTime,restDate,estimatedDurationMinutes,sources`를 포함한다. 추천 불가 장소는 지도 탐색에 표시할 수 있지만 `코스에 추가`는 비활성화하며 서버도 거부한다.

## 식당 검색·선택

| Method | Path | Auth | 핵심 계약 |
|---|---|---|---|
| GET | `/places/restaurants/recommendations?tripId=&dayIndex=&meal=&radius=5000` | user/share | TourAPI 39 우선, 공공 지정 식당 보완 |
| GET | `/places/restaurants/search?tripId=&dayIndex=&meal=&query=&radius=5000&page=1` | user/share EDIT | 카카오 Local FD6 거리순 fallback |
| POST | `/courses/draft/restaurant` | user/share EDIT | meal slot에 선택 결과 추가/교체 |
| POST | `/courses/draft/restaurant/remove` | user/share EDIT | 선택 제거 |

추천 응답은 `{regionFoodThemes,sections:[{source,label,items}]}`다. item은 `provider,externalId,name,category,address,roadAddress,lat,lng,distanceMeters,phone,placeUrl,imageUrl?,representativeMenu?,evidenceLabels,sources`를 가진다. `representativeMenu`는 해당 원천이 제공한 경우에만 채운다. 카카오 검색 응답은 메뉴·평점·인기를 포함하지 않는다.

## 확정·조회·수정·공유

| Method | Path | Auth | Success/Failure |
|---|---|---|---|
| POST | `/courses` | user + `Idempotency-Key` | 201 확정; 날짜 중복은 409로 차단 |
| GET | `/courses/{id}` | owner/share | 권한별 상세 |
| PATCH | `/courses/{id}/schedule` | owner/share EDIT | 장소·순서·식당 변경 |
| POST | `/courses/{id}/share-links` | owner | `{permission,expiresInDays}` → token 1회 반환 |
| PATCH | `/courses/{id}/share-links/{linkId}` | owner | 권한 변경·폐기 |
| GET | `/shared/courses/{token}` | public | VIEW 또는 EDIT 권한 포함 상세 |

확정 요청은 `{tripId,draft}`다. 중복이 생겼으면 409 `TRIP_DATE_OVERLAP`과 conflicts를 반환하고 우회를 허용하지 않는다. 서버는 모든 장소·날짜·품질·중복·권한을 재검증한다.

## 대표 오류 코드

| HTTP | Code | 의미 |
|---|---|---|
| 400 | `INVALID_REQUEST`, `INVALID_QUESTION_VERSION`, `INVALID_SCHEDULE_DENSITY`, `NO_CONDITION_SELECTED`, `MAP_BOUNDS_INVALID` | 형식/규칙 위반 |
| 401 | `AUTHENTICATION_REQUIRED`, `TOKEN_EXPIRED` | 인증 실패 |
| 403 | `INSUFFICIENT_SHARE_PERMISSION` | VIEW 토큰 수정 시도 |
| 404 | `REGION_NOT_FOUND`, `COURSE_NOT_FOUND` | 리소스 없음 |
| 409 | `EMAIL_ALREADY_EXISTS`, `TRIP_DATE_OVERLAP`, `IDEMPOTENCY_CONFLICT` | 충돌/날짜 차단 |
| 410 | `INVITE_EXPIRED`, `SHARE_LINK_REVOKED` | 링크 만료/폐기 |
| 422 | `NO_ELIGIBLE_REGION`, `REGION_CONTENT_NOT_READY`, `INSUFFICIENT_COURSE_CANDIDATES` | 데이터 품질 부족 |
| 502 | `KAKAO_LOCAL_UNAVAILABLE`, `EMBEDDING_UNAVAILABLE`, `TITLE_GENERATION_UNAVAILABLE` | 외부 장애. 코스는 가능한 폴백 적용 |

## 보안·멱등성

- 초대/공유 token은 256비트 이상 난수이며 원문은 생성 응답에서 한 번만 반환한다. DB에는 SHA-256 해시·권한·만료·폐기 시각만 둔다.
- 브라우저가 token URL을 처음 열면 서버가 짧은 수명의 HttpOnly share-session cookie로 교환하고 token 없는 상세 URL로 redirect한다. 이후 API는 해당 cookie 또는 `X-Share-Token`을 사용해 referrer·history 노출을 줄인다.
- refresh/logout과 share-session 교환은 정확한 CORS allowlist와 `Origin` 검사를 적용한다. wildcard credential CORS를 허용하지 않는다.
- 외부 API 키, 비밀번호, token 원문, 온보딩 자유서술을 로그에 남기지 않는다.
- 같은 사용자·`Idempotency-Key`·동일 body는 같은 확정 결과를 반환한다. 같은 키에 다른 body는 409다.
