# MVP REST API 계약 초안

상태: **proposed, 미구현**. JSON 요청/응답은 프론트 협의용이다. 오류는 기존 [응답 규칙](../conventions/API-응답-형식.md)의 `{ "status": 400, "message": "..." }`을 따른다. 로그인 후 API는 `Authorization: Bearer {accessToken}` 필수. 날짜는 `YYYY-MM-DD`, 지역 ID는 5자리 `SIG_CD` 문자열, 관광지/여행 ID는 양의 정수다. 응답에 임베딩 원본·유사도 점수·API 키를 노출하지 않는다.

## 인증·온보딩

| 메서드·경로 | 입력 | 성공 | 주요 실패·부작용 |
|---|---|---|---|
| `POST /api/auth/refresh` | `{refreshToken}` | 200 새 access/refresh | 401 만료·폐기; refresh 회전 |
| `POST /api/auth/logout` | `{refreshToken}` | 204 | refresh 폐기; 반복 요청 처리 정책 리뷰 필요 |
| `POST /api/auth/social/kakao` | `{code,redirectUri}` | 200 `{user,accessToken,refreshToken,onboardingCompleted}` | 400/401/502; 카카오 토큰 교환·사용자 조회 후 계정 연결/생성 |
| `GET /api/users/me` | 인증 | 200 `{id,email,nickname,onboardingCompleted,defaultDrawMode}` | 401 |
| `GET /api/onboarding/questions` | 없음 | 200 `{version,questions:[...]}` | 질문 공개 가능 |
| `POST /api/onboarding/responses` | `{questionVersion,answers:[{questionKey,value}]}` | 200 `{onboardingCompleted:true,tasteStatus:"PENDING|READY"}` | 400 잘못된 키/크기, 401; 제출 묶음 저장·임베딩 작업 예약 |

질문과 답변 형식은 고정된 `questionKey`와 버전으로 배포한다. “좋았던 여행지” 답은 가능하면 `SIG_CD`/관광지 ID를 사용하고, 자유서술은 별도 텍스트 필드로 둔다. 중복 제출 방지를 위해 제출 API의 멱등성 키 채택 여부를 리뷰한다.

## 날짜·지도·지역

| 메서드·경로 | 입력 | 성공 | 주요 실패·부작용 |
|---|---|---|---|
| `POST /api/trips/dates/check` | `{startDate,endDate}` | 200 `{available:true,dayCount}` | 400 역순/과도 기간, 409 확정 여행과 겹침; DB 변경 없음 |
| `GET /api/regions` | 없음 | 200 `{regions:[{sigCd,province,city,drawEligible}]}` | 401; 250개 목록 |
| `POST /api/discovery/draw` | 아래 예시 | 200 `{regionId,province,city,modeApplied,relaxedExclusions}` | 400 날짜/모드, 409 겹침, 422 후보 없음; DB 변경 없음 |
| `GET /api/regions/{sigCd}/card` | 경로 ID | 200 `{regionId,introduction,introductionSource,heroImage,landmarks:[...]}` | 404 미존재, 422 카드 준비 안 됨 |

추첨 요청 예시:

```json
{
  "startDate": "2026-10-10",
  "endDate": "2026-10-12",
  "mode": "PREFERENCE_WEIGHTED",
  "origin": {"lat": 37.5665, "lng": 126.9780},
  "transport": "CAR",
  "party": {"count": 2, "relationship": "FRIENDS"},
  "excludeRegionIds": ["11110"]
}
```

`origin`은 위치 미동의 시 `null` 또는 생략 가능하다. `modeApplied`는 취향 벡터 부재 등으로 실제 적용 방식이 달라졌을 때 프론트에 알려준다. 오류 `409`의 메시지는 겹친 날짜만 안내하고 다른 여행의 민감 정보를 포함하지 않는다.

## 코스 초안·확정

| 메서드·경로 | 입력 | 성공 | 주요 실패·부작용 |
|---|---|---|---|
| `POST /api/courses/draft` | `{regionId,startDate,endDate,origin,transport,party,regionArrivalTime?,regionDepartureTime?,pace?}` | 200 `CourseDraft` | 400/404/409/422; DB 저장 없음 |
| `POST /api/courses/draft/reroll` | 위 맥락 + `days` 현재 슬롯 + `seenAttractionIds` | 200 `CourseDraft` | 잠긴 슬롯 불변; 후보 부족 시 `exhausted` |
| `POST /api/courses/draft/rebalance` | 위 맥락 + 수정한 `days` | 200 `CourseDraft` | 순서/개수·이동/시간 재검사; DB 저장 없음 |
| `POST /api/courses` | 현재 `CourseDraft`의 맥락·일자별 관광지 ID + 멱등성 키 | 201 `{id,status:"CONFIRMED"}` | 400/401/409/422; 검증 후 `trip_plans`·`trip_stops` 생성 |
| `GET /api/courses/{id}` | 경로 ID | 200 저장 코스 | 401/403/404; 본인만 |
| `PATCH /api/courses/{id}/stops` | `{days:[{date,attractionIds}]}` | 200 갱신 코스 | 400/403/404/422; 날짜 범위 변경 금지 |

`CourseDraft` 응답 예시:

```json
{
  "regionId": "47170",
  "startDate": "2026-10-10",
  "endDate": "2026-10-11",
  "days": [
    {"date": "2026-10-10", "assumedStartTime": "10:00", "items": [
      {"type": "ATTRACTION", "attractionId": 101, "name": "관광지명", "imageUrl": "https://example.invalid/image.jpg", "locked": false, "reason": "역사 산책 선호와 맞아요", "startTime": "10:00", "stayMinutes": 90, "travelFromPreviousMinutes": 25, "timeEstimate": "APPROXIMATE", "openingHoursStatus": "VERIFY"},
      {"type": "MEAL", "meal": "LUNCH", "startTime": "12:00", "durationMinutes": 75, "restaurantId": null}
    ]},
    {"date": "2026-10-11", "items": []}
  ],
  "exhausted": false,
  "warnings": ["일부 장소의 운영시간은 방문 전 확인이 필요합니다."]
}
```

위 URL·ID는 **형식 예시**이며 실제 관광지 데이터가 아니다. `MEAL` 항목은 시간이 확보된다는 뜻이며 `restaurantId:null`은 특정 식당 추천이 없음을 뜻한다. `regionArrivalTime`/`regionDepartureTime`은 지역 선택 **후** 받으며 모르면 생략할 수 있다. 관광지·식사·이동 항목의 최종 DTO 형태는 프론트와 승인한다. 추천 점수는 반환하지 않는다. 리롤은 `seenAttractionIds`와 현재 전체 슬롯을 제외하되, 잠긴 슬롯은 유지한다. 모든 초안 API는 클라이언트 상태를 신뢰하지 않고 관광지의 지역·추천 가능 여부를 재검사한다.

## 확정 API의 트랜잭션 규칙

1. 인증된 사용자를 서버에서 식별한다. 요청의 `userId`는 받지 않는다.
2. 날짜 겹침을 사용자 단위 동시성 제어 아래 다시 검사한다.
3. 모든 관광지 ID가 존재하고 같은 지역에 속하며 중복이 없고 이미지·좌표가 유효한지 검사한다.
4. 각 일자의 날짜가 여행 범위에 속하고 순서가 중복되지 않도록 서버가 인덱스를 다시 부여한다.
5. 외부 길찾기 실패 시 저장을 막을지 근사 동선으로 저장할지 팀 리뷰로 확정한다. 성공 여부와 추정 정확도는 응답에 표시한다.
6. 재시도 중복 생성을 막기 위해 `Idempotency-Key` 헤더(동일 사용자·동일 키·동일 요청에 같은 결과)를 제안한다.

## 리뷰 결정

- 액세스/리프레시 토큰 전달·보관 방식, 카카오 콜백/`redirectUri` 허용 목록.
- `POST /api/courses/draft/rebalance`로 개수·순서 변경을 합칠지 기존 `PATCH /api/courses/draft/stops`를 유지할지.
- 최대 여행 일수·하루 방문 수·동행 인원 제한, 도착/출발 시각을 모를 때의 기본 가정, 지역 음식 선택지의 검수 범위.
- 확정 시 길찾기 장애 처리와 멱등성 키 보관 기간.
