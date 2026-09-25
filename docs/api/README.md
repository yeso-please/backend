# TriPin — API 정의서

> 프론트·백엔드 공통 HTTP 계약. 이 폴더가 **엔드포인트·필드·상태 코드·오류 코드의 최종 기준**이다.
> 제품 정책(왜 그렇게 동작하는가)은 [제품 정책](../product.md)이 소스다. 이 폴더는 그 정책을 호출 가능한 계약으로 옮긴다.
> 구현 여부와 관계없이 MVP 전체의 목표 계약을 담는다. 구현 진척은 맨 아래 [구현 체크리스트](#구현-체크리스트)로 본다.

## 개요

| 항목 | 내용 |
|---|---|
| Base URL | `/api` |
| Content-Type | `application/json` (사진 업로드만 `multipart/form-data`) |
| 성공 응답 | 래퍼 없이 데이터 직접 반환. 생성 `201`, 본문 없는 성공 `204` |
| 오류 응답 | `{timestamp,status,code,message,path,fieldErrors?,details?}` ([공통 오류](#공통-오류)) |
| 날짜·시각 | 날짜 `YYYY-MM-DD`, 발급·만료·수정 시각 `YYYY-MM-DDTHH:mm:ss`. 모두 `Asia/Seoul` 기준이며 zone을 붙이지 않는다. 오류 응답의 `timestamp`만 UTC(`…Z`)다 |
| ID | 숫자 ID는 양의 정수. 문자열 ID: 지역 5자리 `SIG_CD`, 온보딩 제출 UUID, 코스 항목 `itemId`(`a-{n}`·`m-{n}`) |
| 코스 식별자 | 코스는 여행의 일정이다. **course id = trip id** (`/courses/{tripId}`) |
| 페이지네이션 | 지도 핀(7-3)은 cursor, 카카오 식당 검색(5-6)은 page. 나머지 목록은 전체 반환 |
| 링크 URL | 초대·공유 링크는 **프론트 라우트**로 보낸다(예: `https://{프론트}/invite/{token}`, `/shared/{token}`). 프론트가 해당 API를 `credentials: 'include'`로 호출한다. share session cookie(`Path=/api/shared`)가 전송되려면 프론트와 API가 같은 site(등록 도메인)여야 한다 |

---

## 문서 읽는 법

각 엔드포인트 제목 아래에 배지가 붙는다.

> `호출: 참여자` · `⬜ 미구현`

| 배지 | 뜻 |
|---|---|
| `호출: …` | 누가 부를 수 있는가. 아래 [호출 주체](#호출-주체와-인증) 참고 |
| `✅ 구현` | main에 구현되어 이 계약과 일치 |
| `🔧 변경 필요` | 구현돼 있으나 이 계약과 다르다. 절 끝에 차이를 적는다 |
| `⬜ 미구현` | 계약만 있다 |

각 파일 절 머리의 `계약 상태`는 `agreed`(팀 리뷰 통과, 구현은 이 계약을 따른다) 또는 `draft`(작성 중, 구현 배정 전)다. 미결 항목은 파일 끝 **결정 필요**에 모은다.

## 범위

| 구분 | 범위 | 백엔드 구현 목표 |
|---|---|---|
| **MVP** | §1 인증, §2 온보딩·친구, §3 여행·지역, §4 초대·공유(친구 직접 초대 포함), §5 코스·식당, §7 지역·관광지 | 2026-09-27 |
| **추가 기능** | §6 여행기·사진 지도(지도에 여행 기록) | 미정. 항목이 더 늘어날 수 있다 |

GitHub 마일스톤 `MVP`, `추가 기능`과 같은 구분이다. 아래 체크리스트의 `범위` 열도 이를 따른다.

## 도메인 그룹

파일은 HTTP를 노출하는 모듈(BC) 단위다. endpoint는 Controller가 속한 모듈의 파일에 둔다([모듈·의존성](../conventions/모듈-의존성.md)).

| § | 그룹 | 파일 | 모듈 패키지 |
|---|---|---|---|
| 1 | 인증 | [auth.md](auth.md) | `auth` |
| 2 | 온보딩·친구 | [profile.md](profile.md) | `profile` |
| 3 | 여행 context·지역 | [trip.md](trip.md#3-여행-context지역) | `trip/context` |
| 4 | 초대·공유 | [trip.md](trip.md#4-초대공유) | `trip/invite` |
| 5 | 코스 | [trip.md](trip.md#5-코스) | `trip/course` |
| 6 | 여행기·사진 지도 (추가 기능) | [trip.md](trip.md#6-여행기사진-지도) | `trip/diary` |
| 7 | 지역·관광지 | [attraction.md](attraction.md) | `attraction/region` |

## 흐름 개요

| 흐름 | 경로 |
|---|---|
| A. 가입·온보딩 | `POST /auth/signup` → `GET /onboarding/questions` → `POST /onboarding/submissions` |
| B. 여행 만들기 | `GET /trips/unavailable-dates` → `POST /trips/context/check` → `POST /trips` |
| C. 함께 가기 | `POST /trips/{id}/invites` → (친구) `GET /invites/{token}` 미리보기 → 로그인·설문 → `POST /invites/{token}/accept` · 또는 `POST /trips/{id}/friend-invites` → `GET /me/trip-invites` → `POST /me/trip-invites/{id}/accept` |
| D. 지역 정하기 | `GET /regions` → `POST /trips/{id}/region`(랜덤·조건·직접 선택) → `GET /regions/{sigCd}/card` |
| E. 코스 만들기 | `POST /courses/{id}/generate` → 지도 `GET /regions/{sigCd}/attractions` · `GET /attractions/{id}` → `GET /courses/{id}/alternatives` → `PATCH /courses/{id}/schedule` |
| F. 식당 | `GET /courses/{id}/restaurants/recommendations` · `…/search` → `PATCH /courses/{id}/schedule`(SET_RESTAURANT) |
| G. 보여주기·탈퇴 | `POST /courses/{id}/share-links` → (제3자) `GET /shared/courses/{token}` → `GET /shared/courses` · `DELETE /trips/{id}/participants/me` |
| H. 여행기(추가 기능, 여행 종료 후) | `POST /courses/{id}/diary` → `POST /diaries/{id}/photos` → `PATCH /diaries/{id}` → `POST /diaries/{id}/publish` → `GET /me/travel-map` |
| I. 친구 | `POST /friend-links` → (친구) `GET /friend-links/by-token/{token}` → `POST /friend-links/by-token/{token}/accept` → `GET /friends/{userId}/travel-map` |

## 계약 변경 절차

1. 계약을 바꾸는 PR은 이 폴더의 문서를 **코드보다 먼저 또는 같은 PR에서** 고친다.
2. 응답 필드 삭제·이름 변경, 필수 필드 추가, 상태 코드 변경은 호환성 변경이다. PR 본문에 영향받는 화면·클라이언트를 한 줄로 적는다.
3. 제품 정책이 바뀌면 `docs/product.md`를 먼저 고치고 이 폴더를 따라 고친다.

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
| 400 | `COMMON_INVALID_REQUEST` | validation(메서드 파라미터 검증 포함), JSON 파싱, 파라미터 변환, 필수 파라미터·cookie 누락 |
| 401 | `AUTH_UNAUTHENTICATED` | 인증 없음·무효 |
| 403 | `AUTH_ACCESS_DENIED` | 인증됐지만 권한 없음 |
| 404 | `COMMON_NOT_FOUND` | 존재하지 않는 API 경로 |
| 405 | `COMMON_METHOD_NOT_ALLOWED` | 지원하지 않는 HTTP 메서드 |
| 500 | `COMMON_INTERNAL_ERROR` | 예상하지 못한 서버 오류 |

도메인 오류 코드는 각 절 머리의 표에 정의한다.

### 오류 판정 순서

한 요청이 여러 오류에 해당하면 아래 순서에서 먼저 걸린 것 하나만 응답한다. 구현자마다 결과가 달라지지 않게 서비스도 이 순서로 검사한다.

1. `401` 인증 — `AUTH_UNAUTHENTICATED`, `SHARE_SESSION_INVALID`
2. `400` 요청 형식·값 검증 — `COMMON_INVALID_REQUEST`, 각 절의 `*_INVALID_*` 등 DB 상태를 보지 않고 판정되는 것
3. `404` 존재·참여 여부 — `TRIP_NOT_FOUND`(비참여자에게는 존재를 숨긴다) → 하위 리소스(`COURSE_NOT_FOUND`, `COURSE_ITEM_NOT_FOUND`, `ATTRACTION_NOT_FOUND` 등)
4. `409 TRIP_ENDED` — 종료된 여행의 변경
5. `409 TRIP_VERSION_CONFLICT` — 버전 불일치
6. 그 밖의 도메인 규칙 — `403`·`409`·`410`·`422` 등(예: `COURSE_CREATOR_ONLY`, `TRIP_CONTEXT_LOCKED`, `TRIP_REGION_NOT_SELECTED`, `COURSE_INSUFFICIENT_CANDIDATES`)

각 절의 오류 표는 이 순서로 정렬돼 있지 않을 수 있다. 표는 가능한 오류의 목록이고, 우선순위는 이 규칙을 따른다.

## 호출 주체와 인증

| 주체 | 자격 증명 | 범위 | 발급 |
|---|---|---|---|
| **회원** | `Authorization: Bearer {accessToken}` (JWT, 30분) | 본인 리소스와 참여한 여행 | [1-1 가입](auth.md#1-1-회원가입)·[1-2 로그인](auth.md#1-2-로그인) |
| **refresh** | `refresh_token` HttpOnly cookie (14일, `Path=/api/auth`) | access 재발급만 | 가입·로그인·refresh 응답 |
| **공유 링크 소지자** | `share_session` HttpOnly cookie (`ss_…`, 2시간, `Path=/api/shared`) | 링크가 가리킨 여행 1개의 코스를 **조회만**(4-13) | [4-12 공유 링크 열기](trip.md#4-12-공유-링크-열기) |
| **여행기 공유 링크 소지자** | `diary_share_session` HttpOnly cookie (2시간, `Path=/api/shared/diaries`) | 링크가 가리킨 여행기 1개를 읽기 전용으로 | [6-11](trip.md#6-11-여행기-공유-링크-열기) |

여행을 만들고 수정하는 참여자는 회원이다. 비회원은 공유 링크로 **이미 만들어진 코스를 조회만** 하는 뷰어다(2026-09-24·2026-09-25 정책). share session은 `/api/shared/**`에서만 인정하며, 지역 카드·지도·관광지 API(7장)와 코스 변경 API는 부를 수 없다. 비회원이 설문·편집하던 guest session은 없어졌다.

배지의 `호출:` 표기는 다음 뜻이다.

| 표기 | 허용 주체 |
|---|---|
| `공개` | 인증 없음 |
| `회원` | 로그인한 회원 누구나 |
| `참여자` | 그 여행을 만들었거나 초대를 수락한 회원. **모두 동등한 권한**이다 |
| `공유 링크 소지자` | 그 여행의 공유 링크로 세션을 받은 사람. `/api/shared/**`의 조회만 한다 |
| `작성자` | 그 여행기를 쓴 회원 |

요청에 JWT와 share session이 함께 있으면 경로가 정한 주체를 쓴다. `/api/shared/**`는 share session, 그 밖은 JWT다. share session만 가진 비회원이 그 밖의 API(코스 변경 포함)를 부르면 일반 JWT 규칙대로 `401 AUTH_UNAUTHENTICATED`다.

- 여행에 접근 권한이 없으면 존재 여부를 숨기고 `404 TRIP_NOT_FOUND`로 응답한다.
- 비밀 token(`iv_` 초대, `sl_` 공유, `ss_` 공유 세션, `fl_` 친구, `dl_` 여행기 공유)은 256bit CSPRNG이며 DB에는 SHA-256 해시만 둔다. 원문은 발급 응답에서 한 번만 반환하고 로그에 남기지 않는다. 없는 token은 404, 만료·폐기는 410이다.
- 식당 `selectionToken`(`rs_`)은 비밀이 아니라 식당 스냅샷 전체를 서버 비밀키(HMAC-SHA256)로 서명한 값이며 DB에 저장하지 않는다([5-5](trip.md#5-5-식당-추천)).
- 브라우저 호출 허용 Origin·헤더는 [CORS](../conventions/설정.md), 자동 생성 문서는 [OpenAPI·Swagger](../conventions/설정.md)를 따른다. 쿠키를 쓰므로 프론트는 `credentials: 'include'`(axios `withCredentials: true`)가 필요하다.

## 후속으로 미룬 결정

MVP 이후 또는 별도 논의로 미뤘다(2026-09-24). 정해지면 해당 절에 계약을 추가한다.

- 닉네임·프로필 수정 API
- 회원 탈퇴 시 참여 여행·친구·여행기 처리
- 여행 제목 수정
- 코스 재생성 되돌리기(undo)
- 지역 소개·음식 테마 승인 도구(관리자 API 또는 운영 스크립트). 없으면 지역 카드가 `REGION_CONTENT_NOT_READY`다

---

## 구현 체크리스트

✅ 구현·계약 일치 · 🔧 구현됐으나 계약과 다름(변경 필요) · ⬜ 미구현

| | § | Method | Path | 호출 | 설명 | 범위 |
|---|---|---|---|---|---|---|
| ✅ | [1-1](auth.md#1-1-회원가입) | POST | `/auth/signup` | 공개 | 회원가입 + refresh cookie | MVP |
| ✅ | [1-2](auth.md#1-2-로그인) | POST | `/auth/login` | 공개 | 로그인 + refresh cookie | MVP |
| ✅ | [1-3](auth.md#1-3-토큰-갱신) | POST | `/auth/refresh` | refresh | access 재발급·회전 | MVP |
| ✅ | [1-4](auth.md#1-4-로그아웃) | POST | `/auth/logout` | 공개 | refresh 폐기 | MVP |
| ✅ | [1-5](auth.md#1-5-내-정보) | GET | `/users/me` | 회원 | 내 정보·온보딩 여부 | MVP |
| ✅ | [2-1](profile.md#2-1-온보딩-질문) | GET | `/onboarding/questions` | 공개 | 질문·태그 사전 | MVP |
| ✅ | [2-2](profile.md#2-2-온보딩-제출) | POST | `/onboarding/submissions` | 회원 | 제출·재검사 | MVP |
| ✅ | [2-3](profile.md#2-3-내-온보딩-결과) | GET | `/onboarding/me` | 회원 | 최신 제출 | MVP |
| ✅ | [2-4](profile.md#2-4-친구-초대-링크-발급) | POST | `/friend-links` | 회원 | 친구 초대 링크 발급 | MVP |
| ✅ | [2-5](profile.md#2-5-내-친구-초대-링크-목록) | GET | `/friend-links` | 회원 | 내 친구 초대 링크 목록 | MVP |
| ✅ | [2-6](profile.md#2-6-친구-초대-링크-폐기) | DELETE | `/friend-links/{id}` | 회원 | 링크 폐기 | MVP |
| ✅ | [2-7](profile.md#2-7-친구-초대-링크-미리보기) | GET | `/friend-links/by-token/{token}` | 공개 | 보낸 사람 미리보기 | MVP |
| ✅ | [2-8](profile.md#2-8-친구-초대-수락) | POST | `/friend-links/by-token/{token}/accept` | 회원 | 수락 → 바로 친구 | MVP |
| ✅ | [2-9](profile.md#2-9-친구-목록) | GET | `/friends` | 회원 | 친구 목록 | MVP |
| ✅ | [2-10](profile.md#2-10-친구-끊기) | DELETE | `/friends/{userId}` | 회원 | 친구 끊기 | MVP |
| ✅ | [3-1](trip.md#3-1-선택-불가-날짜) | GET | `/trips/unavailable-dates` | 회원 | 내 여행·참여 여행 날짜 | MVP |
| 🔧 | [3-2](trip.md#3-2-날짜-중복-미리-확인) | POST | `/trips/context/check` | 회원 | 중복 미리 확인·추첨 가능 지역 수 | MVP |
| ✅ | [3-3](trip.md#3-3-여행-만들기) | POST | `/trips` | 회원 | 여행 생성 | MVP |
| ✅ | [3-4](trip.md#3-4-여행-context-조회) | GET | `/trips/{tripId}/context` | 참여자 | context 조회 | MVP |
| 🔧 | [3-5](trip.md#3-5-이동수단출발지-수정) | PATCH | `/trips/{tripId}/context` | 참여자 | 이동수단·출발지(코스 있어도, 이동시간 재계산) | MVP |
| ✅ | [3-6](trip.md#3-6-내-여행-목록) | GET | `/trips` | 회원 | 내 여행 목록·캘린더 | MVP |
| ⬜ | [3-7](trip.md#3-7-지역-정하기) | POST | `/trips/{tripId}/region` | 참여자 | 랜덤·조건 추첨, 직접 선택 | MVP |
| ✅ | [3-8](trip.md#3-8-참여자-목록) | GET | `/trips/{tripId}/participants` | 참여자 | 참여자 목록 | MVP |
| ✅ | [3-9](trip.md#3-9-여행-탈퇴) | DELETE | `/trips/{tripId}/participants/me` | 참여자 | 탈퇴(마지막이면 여행 삭제) | MVP |
| ✅ | [4-1](trip.md#4-1-초대-링크-발급) | POST | `/trips/{tripId}/invites` | 참여자 | 초대 링크 발급 | MVP |
| ✅ | [4-2](trip.md#4-2-초대-링크-목록) | GET | `/trips/{tripId}/invites` | 참여자 | 초대 링크 목록 | MVP |
| ✅ | [4-3](trip.md#4-3-초대-링크-폐기) | DELETE | `/trips/{tripId}/invites/{inviteId}` | 참여자 | 초대 링크 폐기 | MVP |
| ✅ | [4-4](trip.md#4-4-초대-링크-미리보기) | GET | `/invites/{token}` | 공개 | 초대 미리보기 | MVP |
| ✅ | [4-5](trip.md#4-5-초대-링크-수락) | POST | `/invites/{token}/accept` | 회원 | 초대 링크 수락 | MVP |
| ✅ | [4-6](trip.md#4-6-친구-초대) | POST | `/trips/{tripId}/friend-invites` | 참여자 | 친구 직접 초대 | MVP |
| ✅ | [4-7](trip.md#4-7-받은-초대-목록) | GET | `/me/trip-invites` | 회원 | 받은 초대 | MVP |
| ✅ | [4-8](trip.md#4-8-받은-초대-수락거절) | POST | `/me/trip-invites/{id}/accept`·`/decline` | 회원 | 받은 초대 수락·거절 | MVP |
| ✅ | [4-9](trip.md#4-9-공유-링크-발급) | POST | `/courses/{tripId}/share-links` | 참여자 | 읽기 전용 공유 링크 | MVP |
| ✅ | [4-10](trip.md#4-10-공유-링크-목록) | GET | `/courses/{tripId}/share-links` | 참여자 | 공유 링크 목록 | MVP |
| ✅ | [4-11](trip.md#4-11-공유-링크-폐기) | DELETE | `/courses/{tripId}/share-links/{linkId}` | 참여자 | 공유 링크 폐기 | MVP |
| ✅ | [4-12](trip.md#4-12-공유-링크-열기) | GET | `/shared/courses/{token}` | 공개 | cookie 교환·303 | MVP |
| 🔧 | [4-13](trip.md#4-13-공유-코스-조회) | GET | `/shared/courses` | 공유 링크 소지자 | 공유 코스 (Course 본문) | MVP |
| ✅ | [4-14](trip.md#4-14-보낸-친구-초대-목록) | GET | `/trips/{tripId}/friend-invites` | 참여자 | 보낸 친구 초대 목록 | MVP |
| ✅ | [4-15](trip.md#4-15-친구-초대-취소) | DELETE | `/trips/{tripId}/friend-invites/{inviteId}` | 참여자 | 친구 초대 취소 | MVP |
| ⬜ | [5-1](trip.md#5-1-코스-생성재생성) | POST | `/courses/{tripId}/generate` | 참여자 | 코스 생성·재생성(요청자 취향) | MVP |
| ⬜ | [5-2](trip.md#5-2-코스-조회) | GET | `/courses/{tripId}` | 참여자 | 코스 조회 | MVP |
| ⬜ | [5-3](trip.md#5-3-일정-편집) | PATCH | `/courses/{tripId}/schedule` | 참여자 | 추가·교체·삭제·이동·식당 | MVP |
| ⬜ | [5-4](trip.md#5-4-대체-후보) | GET | `/courses/{tripId}/alternatives` | 참여자 | 유형별 대체 관광지 | MVP |
| ⬜ | [5-5](trip.md#5-5-식당-추천) | GET | `/courses/{tripId}/restaurants/recommendations` | 참여자 | TourAPI·공공 지정 식당 | MVP |
| ✅ | [5-6](trip.md#5-6-식당-검색) | GET | `/courses/{tripId}/restaurants/search` | 참여자 | 카카오 Local 검색 | MVP |
| ⬜ | [6-1](trip.md#6-1-여행기-만들기) | POST | `/courses/{tripId}/diary` | 참여자 | 내 여행기 초안(여행 종료 후) | 추가 |
| ⬜ | [6-2](trip.md#6-2-사진-올리기) | POST | `/diaries/{diaryId}/photos` | 작성자 | 사진 업로드 | 추가 |
| ⬜ | [6-3](trip.md#6-3-사진-삭제) | DELETE | `/diaries/{diaryId}/photos/{photoId}` | 작성자 | 사진 삭제 | 추가 |
| ⬜ | [6-4](trip.md#6-4-여행기-수정) | PATCH | `/diaries/{diaryId}` | 작성자 | 본문·공개 범위 수정 | 추가 |
| ⬜ | [6-5](trip.md#6-5-여행기-발행) | POST | `/diaries/{diaryId}/publish` | 작성자 | 발행 | 추가 |
| ⬜ | [6-6](trip.md#6-6-여행기-조회) | GET | `/diaries/{diaryId}` | 작성자·친구 | 상세 | 추가 |
| ⬜ | [6-7](trip.md#6-7-내-여행-지도) | GET | `/me/travel-map` | 회원 | 내 핀 목록 | 추가 |
| ⬜ | [6-8](trip.md#6-8-친구-여행-지도) | GET | `/friends/{userId}/travel-map` | 수락된 친구 | 친구 핀 목록 | 추가 |
| ⬜ | [6-9](trip.md#6-9-여행기-공유-링크-발급목록) | POST·GET | `/diaries/{diaryId}/share-links` | 작성자 | 읽기 전용 링크 발급·목록 | 추가 |
| ⬜ | [6-10](trip.md#6-10-여행기-공유-링크-폐기) | DELETE | `/diaries/{diaryId}/share-links/{linkId}` | 작성자 | 링크 폐기 | 추가 |
| ⬜ | [6-11](trip.md#6-11-여행기-공유-링크-열기) | GET | `/shared/diaries/{token}` | 공개 | cookie 교환·303 | 추가 |
| ⬜ | [6-12](trip.md#6-12-공유-여행기-조회) | GET | `/shared/diaries` | 공유 소지자 | 공유 여행기 | 추가 |
| ⬜ | [7-1](attraction.md#7-1-지역-목록) | GET | `/regions` | 회원 | 250개 지역·추첨 가능 여부 | MVP |
| ⬜ | [7-2](attraction.md#7-2-지역-카드) | GET | `/regions/{sigCd}/card` | 회원 | 지역 소개 카드 | MVP |
| ⬜ | [7-3](attraction.md#7-3-지도-관광지-핀) | GET | `/regions/{sigCd}/attractions` | 회원 | 지도 핀 | MVP |
| ⬜ | [7-4](attraction.md#7-4-관광지-상세) | GET | `/attractions/{attractionId}` | 회원 | 관광지 상세 | MVP |

`🔧` 항목의 구체적 차이는 각 절의 "구현과의 차이"에 적는다.
