# 프론트 담당자 가이드 — 확정 디자인 화면별 API

- 기준: Figma `IvlST03u2P3Un9OTjUSyBk`(전체 프레임 `489:4621`), 2026-10-16 확정 결정(D0~D20).
- 엔드포인트·필드·오류 코드의 최종 기준은 [`docs/api/`](api/README.md)다. 이 문서는 **화면에서 무엇을 부르고 무엇을 그리는지**만 정리한다. 경로는 모두 `/api` 아래다.
- 공통: 오류는 `code`로 분기한다. 인증은 `Authorization: Bearer {accessToken}`, 쿠키를 쓰므로 `credentials: 'include'`.

## 디자인 토큰

| 토큰 | 값 | | 토큰 | 값 |
|---|---|---|---|---|
| `--color-green` | `#19b86a` | | `--color-muted` | `#768078` |
| `--color-darkgreen` | `#0b6c40` | | `--color-surface` | `#f5f8f5` |
| `--color-mint` | `#ddf5e8` | | `--color-border` | `#e8ede9` |
| `--color-blue` | `#246beb` | | 폰트 | Noto Sans KR |
| `--color-charcoal` | `#202520` | | 로고 | Keania One |

## A. 홈

| 화면 | API | 그리는 법 |
|---|---|---|
| A1 홈·여행 준비 중 | `GET /trips?period=UPCOMING`(3-6) | 여행 카드 제목은 **시군구명 + " 여행"**(`regionName`의 마지막 단어, 예: "강릉시 여행"). 지역 전이면 3-6 `title`(대체 제목). 썸네일은 지역 카드 `heroImage.url`(`GET /regions/{sigCd}/card`). 준비 단계는 아래 규칙 |
| A2 홈·새 여행 | — | "새 여행" → B1 |
| 알림 벨 | `GET /me/trip-invites`(4-7) | **배열 길이 = 숫자**(D15). 받은 여행 초대만 |

**준비 단계 계산**(A1 진행 표시) — 여행 하나에 대해 앞에서부터 처음 안 된 단계가 "지금 할 일"이다.

1. 지역: `regionSigCd != null`(3-4 `TripContext`)
2. 날짜: 여행이 있으면 항상 완료
3. 초대: 참여자 2명 이상(3-8) **또는** 보낸 친구 초대가 있음(4-14)
4. 코스: `hasCourse`(3-4·3-6)

응답 대기 표시는 **친구 초대로 보낸 사람만**이다(D10): `GET /trips/{id}/friend-invites`(4-14)에서 `status == "PENDING"`. 초대 링크로 보낸 사람은 대기 표시가 없다.

## B. 지역 고르기 (지역 먼저, D1)

| 화면 | API | 그리는 법 |
|---|---|---|
| B1 기본 지도 | `GET /regions`(7-1) | 250개 지역 중심 좌표에 핀. 지역 경계 데이터는 없다(중심 좌표만) |
| B2 핀 모드 | `POST /regions/draw`(7-5) `{"mode":"RANDOM"}` | 저장하지 않는다. 결과 `sigCd`로 지도 이동 |
| B2 조건 필터(숨김 레이어 `Region conditions`) | `POST /regions/draw` `{"mode":"CONDITIONAL","conditions":["DISTANCE","MY_TASTE"],"originLat","originLng"}` | ⚠️ 디자인의 "바다·도시·자연" 분위기 칩은 아직 서버 조건이 없다([남은 질문 Q9](#남은-질문)). 지금은 거리·내 취향 조건만 |
| B3 강릉 발견 | `GET /regions/{sigCd}/card`(7-2) | 대표 사진 `heroImage`(출처 `sourceName`·`license` 표기, `attractionId`가 있으면 "대표 관광지 사진"), `tagline`, `tags`(`#` 붙여 표시) |
| B4 강릉 추천 | 7-2 + `GET /regions/{sigCd}/match`(7-6) | "만나볼 순간" 3개 = `landmarks[]`(이름·사진·`oneLine`, D13-2). 개인화 문장 = 7-6 `text`, 앞에 "{닉네임}님은" 을 붙인다. `aiGenerated`면 "AI가 작성" 표시. `text`가 `null`이면(MBTI 없음) 영역을 숨긴다. 저장 아이콘은 숨김(D14) |
| 다시 뽑기 | 같은 7-5를 다시 | 이전 지역을 빼지 않는다 |

빈 상태: `tagline`·`tags`·`landmarks`가 비어 있을 수 있다(승인 전). `heroImage`가 `null`이면 기본 일러스트.

## C. 설문·날짜

| 화면 | API | 그리는 법 |
|---|---|---|
| C1~C4 성향 4문항 | `GET /onboarding/questions`(2-1) `travelStyles` | 1~7 척도. 4는 중립 |
| C5 여행 동기 | 2-1 `travelMotives` | 최대 3개. **코드 5의 표시 문구는 "사진 남기기"**(D18, API 라벨은 그대로 두고 화면에서만 바꾼다) |
| (추가) MBTI 12문항 | 2-1 `mbtiQuestions` | 디자이너가 화면 추가(D3-1). 선택지 순서대로 1·2 |
| 제출 | `POST /onboarding/submissions`(2-2) | `questionVersion: "aihub-traveler-v2"`. **`scheduleDensity`는 보내지 않는다** — 서버가 MBTI J → `PACKED`, P → `RELAXED`로 정한다(D3-2) |
| C6 나의 여행 성향 | `GET /onboarding/me`(2-3) `mbtiCode` | 성향 콘텐츠는 **프론트 상수**: [`travel-personas.json`](../src/main/resources/personas/travel-personas.json)을 복사해 `mbtiCode`로 찾는다(이름·한 줄·설명·태그·여행 속도·선호 장소·활동·동행). 캐릭터는 지금 모든 유형이 INFP 임시 캐릭터(`characterKey: "infp"`, Q5). 팀 검수 전 초안이다 |
| C7 날짜 선택 | `GET /trips/unavailable-dates`(3-1), `POST /trips/context/check`(3-2) `{"startDate","nights","sigCd"}` | 겹치는 날짜 비활성. `regionEligible == false`면 "이 기간엔 코스가 부족해요, 다시 뽑을까요?". 7-5 `maxNights`보다 긴 기간을 미리 막아도 된다 |
| 여행 만들기 | `POST /trips`(3-3) `{"startDate","nights","regionSigCd","regionSelection"}` | `transport`는 보내지 않는다(서버 기본 `CAR`, D2). `422 DRAW_REGION_NOT_ELIGIBLE`이면 다시 뽑기 안내 |

## D. 초대

| 화면 | API | 그리는 법 |
|---|---|---|
| D1 친구 초대 | `GET /friends`(2-9), `POST /trips/{id}/friend-invites`(4-6), 링크 `POST /trips/{id}/invites`(4-1) | 링크는 프론트 라우트 `/invite/{token}`로 공유(카카오톡 공유·복사) |
| D1a 전송 완료 | 4-14 | 대기 표시는 `PENDING`만 |
| D2a 회원 초대 수신 | `GET /me/trip-invites`(4-7) | `trip.heroImageUrl`, `trip.participants[].nickname`(D8), `dateConflict`면 수락 비활성 |
| D2b 비회원 초대 수신 | `GET /invites/{token}`(4-4) | 참여자 닉네임·대표 사진. `hasCourse`면 "코스 둘러보기" → `GET /invites/{token}/course`(4-16, 로그인 불필요, 읽기 전용, 참여자 이름 보임) |
| D2c 참여 완료 | `POST /invites/{token}/accept`(4-5) 또는 `POST /me/trip-invites/{id}/accept`(4-8) | 비로그인이면 로그인·설문 뒤 수락 |

## E. 함께 만드는 코스

| 화면 | API | 그리는 법 |
|---|---|---|
| E0 첫 코스 | `POST /courses/{id}/generate`(5-1) `{"version"}` | 최초는 여행을 만든 사람만(`403 COURSE_CREATOR_ONLY`). "생성자 취향 또는 완전 랜덤"은 `tasteMode: "TASTE"|"RANDOM"`. 응답에 AI 제목·소개·이유가 붙는다(수 초 걸린다, 로딩 표시). `aiGenerated`면 "AI가 작성" |
| E1 함께 만드는 코스 | `GET /courses/{id}`(5-2) | 일차 탭 = `days[]`(D19, 전체 표시). 카드: 이름·사진·`oneLine`(없으면 `reason`)·**이동 시간 세 수단** `travelFromPrevious`(D5, 아래). 카드 라벨 = `change.origin`: `BASE` "기본 추천", `ADDED` "{by.nickname} 추가", `REPLACED` "{by.nickname} 교체" + `replacedFrom.name → name` |
| 식사 카드 | 5-5·5-6 → 5-3 `SET_RESTAURANT` | 디자인에 추가(D6). `restaurant == null`이면 "미정" |
| "보고 있어요" | `POST /trips/{id}/presence` 20초마다, `GET /trips/{id}/presence` 같은 주기 | 나를 뺀 `participants[].nickname`으로 "민지가 보고 있어요"(D12). 화면을 떠나면 신호를 멈춘다 |
| E2 친구 변경 표시 | 5-2 `changes` | 탭 "변경 표시": `changes.days[]`(추가·교체·제외 수), `changes.removed[]`(제외된 장소, "다시 넣기" → 5-3 `RESTORE`), `changes.latest`로 "민지가 경포호 산책을 노을 감상으로 바꿨어요" |
| 장소 편집(숨김 레이어 `Place edit options`) | 5-4 후보, 5-3 `REPLACE`·`REMOVE`(`note` 선택 100자) | "장소를 함께 다듬어요 / 친구 변경 보기" |
| E3 참여자 관리 | `GET /trips/{id}/participants`(3-8) | `isManager`인 사람에게만 "내보내기" 노출 → 확인(숨김 레이어 `Participant confirmation`) → `DELETE /trips/{id}/participants/{userId}`(3-10). 나 자신은 3-9 탈퇴 |
| E4 코스 둘러보기 | 5-2, 지도 `GET /courses/{id}/routes?dayIndex=&transport=`(5-7) | 지도 선은 5-7 `legs[].path`. 수단 탭(도보·자동차·대중교통)으로 `transport`를 바꿔 부른다 |
| E5~E8 카드 드래그·재정렬 | 5-3 `MOVE` | 하루 안·다른 날로 이동. 식사는 같은 날 안에서만 |

**이동 시간 표시(D5)** — 카드의 "09:00" 자리. 시각 필드는 없다.

```json
"travelFromPrevious": {"meters": 1800, "walkMinutes": 31, "carMinutes": 6, "transitMinutes": 14, "carSource": "ROAD",
                       "walkSource": "ROUTE", "transitSource": "ROUTE", "transitTransfers": 0, "transitFare": 1500, "estimated": false}
```

- 세 수단 시간을 모두 보여 주고, 여행 `transport`(3-4)를 기본 강조로 쓴다.
- 수단별 `*Source`가 `STRAIGHT`면 직선 추정이라 "약"을 붙인다. 지도(5-7)를 그 수단으로 한 번 열면 실제 경로 시간이 된다: 자동차 `ROAD`, 도보·대중교통 `ROUTE`(카카오맵). 대중교통은 환승 수·요금도 보여 줄 수 있다.
- 지도에서 수단을 바꿔 보려면 `GET /courses/{id}/routes?dayIndex=0&transport=PUBLIC_TRANSIT`. 구간 `source`가 `TRANSIT`이면 `transfers`·`fare`가 있다.
- 그날 첫 장소는 `null`이라 표시하지 않는다.

**동시 편집** — 모든 편집은 받은 `version`을 그대로 보낸다. `409 TRIP_VERSION_CONFLICT`면 5-2로 다시 받고 안내한다. 5-1 응답의 `version`으로 바로 편집할 수 있다(AI 문장을 붙여도 버전이 그대로다).

## 공유 (비회원)

`/shared/{token}` → `GET /shared/courses/{token}`(303) → `GET /shared/courses`(4-13). 사람 이름(`tasteBasis`, `updatedBy`, 변경 기록의 `by`)은 `null`이다.

## 남은 질문

- **Q9 지역 분위기 조건(바다·도시·자연)** — Figma `Region conditions`(489:1478)에 분위기 칩이 있다. 서버 조건 추첨에는 거리·내 취향만 있다. 지역 관광지 분류 비율(자연 `NA`, 바다 `NA02`, 도시 `VE`·`SH`)로 점수를 만들어 조건으로 넣을지 결정이 필요하다.

## 이 문서 갱신

백엔드 계약이 바뀌면 이 표를 함께 고친다. 화면 이름은 Figma 프레임 이름과 같게 둔다.
