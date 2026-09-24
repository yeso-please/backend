# TriPin — API 정의서

> 프론트·백엔드 공통 HTTP 계약. 이 폴더가 **엔드포인트·필드·상태 코드·오류 코드의 최종 기준**이다.
> 제품 정책(왜 그렇게 동작하는가)은 [MVP 설계 기준](../mvp/README.md)이 소스다. 이 폴더는 그 정책을 호출 가능한 계약으로 옮긴다.
> 구현 여부와 관계없이 MVP 전체의 목표 계약을 담는다. 구현 진척은 맨 아래 [구현 체크리스트](#구현-체크리스트)로 본다.

## 개요

| 항목 | 내용 |
|---|---|
| Base URL | `/api` |
| Content-Type | `application/json` (사진 업로드만 `multipart/form-data`) |
| 성공 응답 | 래퍼 없이 데이터 직접 반환. 생성 `201`, 본문 없는 성공 `204` |
| 오류 응답 | `{timestamp,status,code,message,path,fieldErrors?,details?}` ([공통 오류](#공통-오류)) |
| 날짜·시각 | 날짜 `YYYY-MM-DD`, 일정 시각 `HH:mm`(지역 현지), 서버 시간대 `Asia/Seoul`. 발급·만료 시각은 `YYYY-MM-DDTHH:mm:ss` |
| ID | 양의 정수. 지역은 5자리 `SIG_CD` 문자열, 온보딩 제출은 UUID |
| 코스 식별자 | 코스는 여행의 일정이다. **course id = trip id** (`/courses/{tripId}`) |
| 페이지네이션 | 지도 핀만 cursor 방식. 나머지 목록은 전체 반환 |

---

## 문서 읽는 법

각 엔드포인트 제목 아래에 배지가 붙는다.

> `WORK-06` · `호출: 참여자` · `⬜ 미구현`

| 배지 | 뜻 |
|---|---|
| `WORK-nn` | 구현 작업 단위([구현 작업서](../mvp/implementation-workpack.md)) |
| `호출: …` | 누가 부를 수 있는가. 아래 [호출 주체](#호출-주체와-인증) 참고 |
| `✅ 구현` | main에 구현되어 이 계약과 일치 |
| `🔧 변경 필요` | 구현돼 있으나 이 계약과 다르다. 절 끝에 차이를 적는다 |
| `⬜ 미구현` | 계약만 있다 |

각 파일 절 머리의 `계약 상태`는 `agreed`(팀 리뷰 통과, 구현은 이 계약을 따른다) 또는 `draft`(작성 중, 구현 배정 전)다. 미결 항목은 파일 끝 **결정 필요**에 모은다.

## 도메인 그룹

파일은 HTTP를 노출하는 모듈(BC) 단위다. endpoint는 Controller가 속한 모듈의 파일에 둔다([모듈·의존성](../conventions/모듈-의존성.md)).

| § | 그룹 | 파일 | 모듈 패키지 |
|---|---|---|---|
| 1 | 인증 | [auth.md](auth.md) | `auth` |
| 2 | 온보딩·친구 | [profile.md](profile.md) | `profile` |
| 3 | 여행 context·지역 | [trip.md](trip.md#3-여행-context지역) | `trip/context` |
| 4 | 초대·공유 | [trip.md](trip.md#4-초대공유) | `trip/invite` |
| 5 | 코스 | [trip.md](trip.md#5-코스) | `trip/course` |
| 6 | 여행기·사진 지도 | [trip.md](trip.md#6-여행기사진-지도) | `trip/diary` |
| 7 | 지역·관광지 | [attraction.md](attraction.md) | `attraction/region` |

## 흐름 개요

| 흐름 | 경로 | WORK |
|---|---|---|
| A. 가입·온보딩 | `POST /auth/signup` → `GET /onboarding/questions` → `POST /onboarding/submissions` | 01·02 |
| B. 여행 만들기 | `GET /trips/unavailable-dates` → `POST /trips/context/check` → `POST /trips` | 03 |
| C. 함께 가기 | `POST /trips/{id}/invites` → (친구, 로그인·설문 후) `GET /invites/{token}` → `POST /invites/{token}/accept` · 또는 `POST /trips/{id}/friend-invites` → `GET /me/trip-invites` → `POST /me/trip-invites/{id}/accept` | 04 |
| D. 지역 정하기 | `GET /regions` → `POST /trips/{id}/region`(랜덤·조건·직접 선택) → `GET /regions/{sigCd}/card` | 05 |
| E. 코스 만들기 | `POST /courses/{id}/generate` → 지도 `GET /regions/{sigCd}/attractions` · `GET /attractions/{id}` → `GET /courses/{id}/alternatives` → `PATCH /courses/{id}/schedule` | 06·07 |
| F. 식당 | `GET /courses/{id}/restaurants/recommendations` · `…/search` → `PATCH /courses/{id}/schedule`(SET_RESTAURANT) | 07 |
| G. 보여주기·탈퇴 | `POST /courses/{id}/share-links` → (제3자) `GET /shared/courses/{token}` → `GET /shared/courses` · `DELETE /trips/{id}/participants/me` | 04 |
| H. 여행기(여행 종료 후) | `POST /courses/{id}/diary` → `POST /diaries/{id}/photos` → `PATCH /diaries/{id}` → `POST /diaries/{id}/publish` → `GET /me/travel-map` | 10 |
| I. 친구 | `POST /friend-links` → (친구) `GET /friend-links/by-token/{token}` → `POST /friend-links/by-token/{token}/accept` → `GET /friends/{userId}/travel-map` | 10 |

## 계약 변경 절차

1. 계약을 바꾸는 PR은 이 폴더의 문서를 **코드보다 먼저 또는 같은 PR에서** 고친다.
2. 응답 필드 삭제·이름 변경, 필수 필드 추가, 상태 코드 변경은 호환성 변경이다. PR 본문에 영향받는 화면·클라이언트를 한 줄로 적는다.
3. 제품 정책이 바뀌면 `docs/mvp/decisions.md`를 먼저 고치고 이 폴더를 따라 고친다.

---

## 공통 오류

Controller, validation, Security 계층이 모두 같은 모양을 쓴다. 구현: `shared.exception`, `shared.security`.

```json
{
  "timestamp": "2026-09-20T10:00:00Z",
  "status": 400,
  "code": "COMMON_INVALID_REQUEST",
  "message": "요청 값이 올바르지 않습니다.",
  "path": "/api/example",
  "fieldErrors": [{"field": "startDate", "message": "필수 값입니다."}]
}
```

- `fieldErrors`는 validation 실패에만, `details`는 도메인 예외가 구조화된 정보(예: `TRIP_DATE_OVERLAP`의 `conflicts`)를 실을 때만 붙는다. 값이 없으면 생략한다.
- 500은 내부 메시지·stack trace를 노출하지 않는다.
- 프론트는 `status`가 아니라 `code`로 분기한다.

| HTTP | code | 사용 시점 |
|---:|---|---|
| 400 | `COMMON_INVALID_REQUEST` | validation, JSON 파싱, 파라미터 변환 |
| 401 | `AUTH_UNAUTHENTICATED` | 인증 없음·무효 |
| 403 | `AUTH_ACCESS_DENIED` | 인증됐지만 권한 없음 |
| 405 | `COMMON_METHOD_NOT_ALLOWED` | 지원하지 않는 HTTP 메서드 |
| 500 | `COMMON_INTERNAL_ERROR` | 예상하지 못한 서버 오류 |

도메인 오류 코드는 각 절 머리의 표에 정의한다.

## 호출 주체와 인증

| 주체 | 자격 증명 | 범위 | 발급 |
|---|---|---|---|
| **회원** | `Authorization: Bearer {accessToken}` (JWT, 30분) | 본인 리소스와 참여한 여행 | [1-1 가입](auth.md#1-1-회원가입)·[1-2 로그인](auth.md#1-2-로그인) |
| **refresh** | `refresh_token` HttpOnly cookie (14일, `Path=/api/auth`) | access 재발급만 | 가입·로그인·refresh 응답 |
| **공유 링크 소지자** | `share_session` HttpOnly cookie (`ss_…`, 2시간, `Path=/api`) | 링크가 가리킨 여행 1개를 **읽기 전용**으로 | [4-12 공유 링크 열기](trip.md#4-12-공유-링크-열기) |

여행은 회원만 만들고 참여한다. 비회원 참여(guest session)는 2026-09-24 정책으로 없어졌다.

배지의 `호출:` 표기는 다음 뜻이다.

| 표기 | 허용 주체 |
|---|---|
| `공개` | 인증 없음 |
| `회원` | 로그인한 회원 누구나 |
| `참여자` | 그 여행을 만들었거나 초대를 수락한 회원. **모두 동등한 권한**이다 |
| `공유 링크 소지자` | 그 여행의 공유 링크로 세션을 받은 사람. 조회만 한다 |
| `작성자` | 그 여행기를 쓴 회원 |
| `인증된 주체` | 회원 또는 공유 링크 소지자. 여행 권한은 보지 않는다 |

- 여행에 접근 권한이 없으면 존재 여부를 숨기고 `404 TRIP_NOT_FOUND`로 응답한다. 공유 링크 소지자가 수정을 시도하면 `403 SHARE_VIEW_ONLY`다.
- 비밀 token(`iv_`, `sl_`, `ss_`, `fl_`, `dl_`)은 256bit CSPRNG이며 DB에는 SHA-256 해시만 둔다. 원문은 발급 응답에서 한 번만 반환하고 로그에 남기지 않는다.
- 브라우저 호출 허용 Origin·헤더는 [CORS](../conventions/CORS.md), 자동 생성 문서는 [OpenAPI·Swagger](../conventions/OpenAPI.md)를 따른다. 쿠키를 쓰므로 프론트는 `credentials: 'include'`(axios `withCredentials: true`)가 필요하다.

## 후속으로 미룬 결정

MVP 이후 또는 별도 논의로 미뤘다(2026-09-24). 정해지면 해당 절에 계약을 추가한다.

- 닉네임·프로필 수정 API
- 회원 탈퇴 시 참여 여행·친구·여행기 처리
- 여행 제목 수정
- 지역 소개·음식 테마 승인 도구(관리자 API 또는 운영 스크립트). 없으면 지역 카드가 `REGION_CONTENT_NOT_READY`다

---

## 구현 체크리스트

✅ 구현·계약 일치 · 🔧 구현됐으나 계약과 다름(변경 필요) · ⬜ 미구현

| | § | Method | Path | 호출 | 설명 | WORK |
|---|---|---|---|---|---|---|
| ✅ | [1-1](auth.md#1-1-회원가입) | POST | `/auth/signup` | 공개 | 회원가입 + refresh cookie | 01 |
| ✅ | [1-2](auth.md#1-2-로그인) | POST | `/auth/login` | 공개 | 로그인 + refresh cookie | 01 |
| ✅ | [1-3](auth.md#1-3-토큰-갱신) | POST | `/auth/refresh` | refresh | access 재발급·회전 | 01 |
| ✅ | [1-4](auth.md#1-4-로그아웃) | POST | `/auth/logout` | 공개 | refresh 폐기 | 01 |
| ✅ | [1-5](auth.md#1-5-내-정보) | GET | `/users/me` | 회원 | 내 정보·온보딩 여부 | 01 |
| ✅ | [2-1](profile.md#2-1-온보딩-질문) | GET | `/onboarding/questions` | 공개 | 질문·태그 사전 | 02 |
| ✅ | [2-2](profile.md#2-2-온보딩-제출) | POST | `/onboarding/submissions` | 회원 | 제출·재검사 | 02 |
| ✅ | [2-3](profile.md#2-3-내-온보딩-결과) | GET | `/onboarding/me` | 회원 | 최신 제출 | 02 |
| ⬜ | [2-4](profile.md#2-4-친구-초대-링크-발급) | POST | `/friend-links` | 회원 | 친구 초대 링크 발급 | 10 |
| ⬜ | [2-5](profile.md#2-5-내-친구-초대-링크-목록) | GET | `/friend-links` | 회원 | 내 친구 초대 링크 목록 | 10 |
| ⬜ | [2-6](profile.md#2-6-친구-초대-링크-폐기) | DELETE | `/friend-links/{id}` | 회원 | 링크 폐기 | 10 |
| ⬜ | [2-7](profile.md#2-7-친구-초대-링크-미리보기) | GET | `/friend-links/by-token/{token}` | 공개 | 보낸 사람 미리보기 | 10 |
| ⬜ | [2-8](profile.md#2-8-친구-초대-수락) | POST | `/friend-links/by-token/{token}/accept` | 회원 | 수락 → 바로 친구 | 10 |
| ⬜ | [2-9](profile.md#2-9-친구-목록) | GET | `/friends` | 회원 | 친구 목록 | 10 |
| ⬜ | [2-10](profile.md#2-10-친구-끊기) | DELETE | `/friends/{userId}` | 회원 | 친구 끊기 | 10 |
| ✅ | [3-1](trip.md#3-1-선택-불가-날짜) | GET | `/trips/unavailable-dates` | 회원 | 내 여행·참여 여행 날짜 | 03 |
| ✅ | [3-2](trip.md#3-2-날짜-중복-미리-확인) | POST | `/trips/context/check` | 회원 | 중복 미리 확인 | 03 |
| ✅ | [3-3](trip.md#3-3-여행-만들기) | POST | `/trips` | 회원 | 여행 생성 | 03 |
| ✅ | [3-4](trip.md#3-4-여행-context-조회) | GET | `/trips/{tripId}/context` | 참여자 | context 조회 | 03·05 |
| ✅ | [3-5](trip.md#3-5-이동수단출발지-수정) | PATCH | `/trips/{tripId}/context` | 참여자 | 이동수단·출발지만 | 03 |
| ✅ | [3-6](trip.md#3-6-내-여행-목록) | GET | `/trips` | 회원 | 내 여행 목록·캘린더 | 03 |
| ⬜ | [3-7](trip.md#3-7-지역-정하기) | POST | `/trips/{tripId}/region` | 참여자 | 랜덤·조건 추첨, 직접 선택 | 05 |
| ✅ | [3-8](trip.md#3-8-참여자-목록) | GET | `/trips/{tripId}/participants` | 참여자 | 참여자 목록 | 04 |
| ✅ | [3-9](trip.md#3-9-여행-탈퇴) | DELETE | `/trips/{tripId}/participants/me` | 참여자 | 탈퇴(마지막이면 여행 삭제) | 04 |
| ✅ | [4-1](trip.md#4-1-초대-링크-발급) | POST | `/trips/{tripId}/invites` | 참여자 | 초대 링크 발급 | 04 |
| ✅ | [4-2](trip.md#4-2-초대-링크-목록) | GET | `/trips/{tripId}/invites` | 참여자 | 초대 링크 목록 | 04 |
| ✅ | [4-3](trip.md#4-3-초대-링크-폐기) | DELETE | `/trips/{tripId}/invites/{inviteId}` | 참여자 | 초대 링크 폐기 | 04 |
| ✅ | [4-4](trip.md#4-4-초대-링크-미리보기) | GET | `/invites/{token}` | 공개 | 초대 미리보기 | 04 |
| ✅ | [4-5](trip.md#4-5-초대-링크-수락) | POST | `/invites/{token}/accept` | 회원 | 초대 링크 수락 | 04 |
| ⬜ | [4-6](trip.md#4-6-친구-초대) | POST | `/trips/{tripId}/friend-invites` | 참여자 | 친구 직접 초대 | 04 |
| ⬜ | [4-7](trip.md#4-7-받은-초대-목록) | GET | `/me/trip-invites` | 회원 | 받은 초대 | 04 |
| ⬜ | [4-8](trip.md#4-8-받은-초대-수락거절) | POST | `/me/trip-invites/{id}/accept`·`/decline` | 회원 | 받은 초대 수락·거절 | 04 |
| ✅ | [4-9](trip.md#4-9-공유-링크-발급) | POST | `/courses/{tripId}/share-links` | 참여자 | 읽기 전용 공유 링크 | 04 |
| ✅ | [4-10](trip.md#4-10-공유-링크-목록) | GET | `/courses/{tripId}/share-links` | 참여자 | 공유 링크 목록 | 04 |
| ✅ | [4-11](trip.md#4-11-공유-링크-폐기) | DELETE | `/courses/{tripId}/share-links/{linkId}` | 참여자 | 공유 링크 폐기 | 04 |
| ✅ | [4-12](trip.md#4-12-공유-링크-열기) | GET | `/shared/courses/{token}` | 공개 | cookie 교환·303 | 04 |
| 🔧 | [4-13](trip.md#4-13-공유-코스-조회) | GET | `/shared/courses` | 공유 링크 소지자 | 공유 코스 (Course 본문) | 04·06 |
| ⬜ | [5-1](trip.md#5-1-코스-생성재생성) | POST | `/courses/{tripId}/generate` | 참여자 | 코스 생성·재생성(요청자 취향) | 06 |
| ⬜ | [5-2](trip.md#5-2-코스-조회) | GET | `/courses/{tripId}` | 참여자·공유 | 코스 조회 | 06 |
| ⬜ | [5-3](trip.md#5-3-일정-편집) | PATCH | `/courses/{tripId}/schedule` | 참여자 | 추가·교체·삭제·이동·식당 | 07 |
| ⬜ | [5-4](trip.md#5-4-대체-후보) | GET | `/courses/{tripId}/alternatives` | 참여자 | 유형별 대체 관광지 | 07 |
| ⬜ | [5-5](trip.md#5-5-식당-추천) | GET | `/courses/{tripId}/restaurants/recommendations` | 참여자 | TourAPI·공공 지정 식당 | 07 |
| ⬜ | [5-6](trip.md#5-6-식당-검색) | GET | `/courses/{tripId}/restaurants/search` | 참여자 | 카카오 Local 검색 | 07 |
| ⬜ | [6-1](trip.md#6-1-여행기-만들기) | POST | `/courses/{tripId}/diary` | 참여자 | 내 여행기 초안(여행 종료 후) | 10 |
| ⬜ | [6-2](trip.md#6-2-사진-올리기) | POST | `/diaries/{diaryId}/photos` | 작성자 | 사진 업로드 | 10 |
| ⬜ | [6-3](trip.md#6-3-사진-삭제) | DELETE | `/diaries/{diaryId}/photos/{photoId}` | 작성자 | 사진 삭제 | 10 |
| ⬜ | [6-4](trip.md#6-4-여행기-수정) | PATCH | `/diaries/{diaryId}` | 작성자 | 본문·공개 범위 수정 | 10 |
| ⬜ | [6-5](trip.md#6-5-여행기-발행) | POST | `/diaries/{diaryId}/publish` | 작성자 | 발행 | 10 |
| ⬜ | [6-6](trip.md#6-6-여행기-조회) | GET | `/diaries/{diaryId}` | 작성자·친구 | 상세 | 10 |
| ⬜ | [6-7](trip.md#6-7-내-여행-지도) | GET | `/me/travel-map` | 회원 | 내 핀 목록 | 10 |
| ⬜ | [6-8](trip.md#6-8-친구-여행-지도) | GET | `/friends/{userId}/travel-map` | 수락된 친구 | 친구 핀 목록 | 10 |
| ⬜ | [6-9](trip.md#6-9-여행기-공유-링크-발급) | POST | `/diaries/{diaryId}/share-links` | 작성자 | 읽기 전용 링크 | 10 |
| ⬜ | [6-10](trip.md#6-10-여행기-공유-링크-폐기) | DELETE | `/diaries/{diaryId}/share-links/{linkId}` | 작성자 | 링크 폐기 | 10 |
| ⬜ | [6-11](trip.md#6-11-여행기-공유-링크-열기) | GET | `/shared/diaries/{token}` | 공개 | cookie 교환·303 | 10 |
| ⬜ | [6-12](trip.md#6-12-공유-여행기-조회) | GET | `/shared/diaries` | 공유 소지자 | 공유 여행기 | 10 |
| ⬜ | [7-1](attraction.md#7-1-지역-목록) | GET | `/regions` | 회원 | 250개 지역·추첨 가능 여부 | 05 |
| ⬜ | [7-2](attraction.md#7-2-지역-카드) | GET | `/regions/{sigCd}/card` | 인증된 주체 | 지역 소개 카드 | 05 |
| ⬜ | [7-3](attraction.md#7-3-지도-관광지-핀) | GET | `/regions/{sigCd}/attractions` | 인증된 주체 | 지도 핀 | 07 |
| ⬜ | [7-4](attraction.md#7-4-관광지-상세) | GET | `/attractions/{attractionId}` | 인증된 주체 | 관광지 상세 | 07 |

`🔧` 항목의 구체적 차이는 각 절의 "구현과의 차이"에 적는다.
