# 회원 초대와 읽기 전용 공유

> **2026-09-24 재작업** — [공동 일정 정책](../mvp-decisions-2026-09-20.md)에 따라 비회원 참여(guest session·손님 온보딩)와 VIEW/EDIT 권한을 제거했다. 초대는 로그인·설문을 마친 회원이 수락하고(`POST /api/invites/{token}/accept`), 참여자는 모두 동등하며, 공유 링크는 읽기 전용이다. 폐기는 `DELETE`다. 현재 계약은 [여행 API 4장](../../api/trip.md#4-초대공유)이 기준이며 아래 본문의 비회원·권한 설명은 이력이다. 친구 직접 초대(4-6~4-8)는 친구 기능 이후다.


- 상태: done
- 담당 범위: trip
- 작성일: 2026-09-23
- 갱신일: 2026-09-24
- 관련 이슈: #14 (WORK-04)
- 관련 API: [초대·공유 API](../../api/trip.md#4-초대공유)

## 목표

여행 소유자가 초대 링크를 만들어 전달하면, 초대받은 사람이 **가입 없이** 표시 이름과 온보딩만으로
추천 성향에 참여할 수 있다. 완성된 일정은 링크별 `VIEW|EDIT` 권한으로 공유한다.

## 범위

### 포함

- 초대 링크 발급·조회·권한 변경·폐기 (소유자 전용)
- 초대 링크 공개 요약과 비회원 참여(guest session 발급)
- guest session 기반 온보딩 제출 — WORK-02 온보딩 서비스 재사용
- guest 임베딩 job 처리와 `guest_taste_vectors` 적재
- 공유 링크 발급·조회·권한 변경·폐기와 HttpOnly session 교환
- 공유된 일정 조회, 편집 권한 판정 계약

### 제외

- 서버가 카카오톡 메시지나 앱 알림을 직접 발송하는 것 — 프론트 카카오 Share JS만 쓴다.
- 회원 친구 병합, 실시간 공동 편집
- 실제 장소·순서·식당 편집 — WORK-07/08이 맡는다. 이번 작업은 권한 판정 계약만 제공한다.

## 사용자 흐름

1. 소유자가 `POST /api/trips/{tripId}/invites`로 초대 링크를 만든다. 원문 token은 이 응답에만 실린다.
2. 프론트가 카카오톡 공유 UI나 링크 복사로 전달한다.
3. 초대받은 사람이 `GET /api/invites/{token}`으로 최소 요약(날짜, 주최자 닉네임)을 본다.
4. 표시 이름을 넣어 `POST /api/invites/{token}/participants`로 참여한다 — `GUEST`/`INVITED`
   participant와 guest session이 생긴다.
5. `POST /api/invite-participants/{id}/onboarding`으로 온보딩을 제출하면 `READY`가 된다.
6. 소유자가 `POST /api/courses/{tripId}/share-links`로 공유 링크를 만든다.
7. 링크를 열면 원문 token이 HttpOnly cookie로 교환되고 token 없는 URL로 303 redirect된다.

## 설계 결정

### 초대 token과 공유 token을 분리한다

초대는 "이 여행에 참여해 성향을 보태라"이고, 공유는 "완성된 일정을 이 권한으로 보라"다. 수명과
수신자가 다르므로 한 token으로 겸하면 초대 링크가 그대로 일정 열람권이 된다. 네 종류 모두 256bit
URL-safe CSPRNG 본문에 용도 접두사(`iv_`/`gs_`/`sl_`/`ss_`)를 붙이고, DB에는 SHA-256 해시만 둔다.

접두사를 붙인 이유는 오류를 구분하기 위해서다. 접두사가 없으면 "존재하지 않는 초대"와 "공유 token을
초대에 잘못 썼다"가 똑같이 404가 되어 클라이언트가 원인을 알 수 없다. 접두사는 식별용이며 비밀이
아니다 — 추측 방지는 뒤의 CSPRNG 본문이 담당한다.

### 공유 링크 open은 token을 세션으로 교환한다

URL에 원문 token이 있으면 브라우저 히스토리, referrer 헤더, 서버 접근 로그에 그대로 남는다.
`GET /api/shared/courses/{token}`은 짧은(2시간) HttpOnly cookie를 내려주고 token 없는
`/api/shared/courses`로 303 redirect한다. 이후 조회는 cookie로만 한다.

세션은 자기 만료뿐 아니라 **뒤에 있는 링크의 폐기·만료도 매 요청 확인**한다. 그렇지 않으면 링크를
끊어도 이미 열어둔 탭이 2시간 동안 계속 읽을 수 있다.

### 온보딩 제출은 WORK-02 서비스를 재사용한다

`OnboardingService.submitForGuest(guestParticipantId, request)`는 user 대신 participant에
submission을 귀속시킨다. participant 상태 전이(`ONBOARDING→READY`)와 latest pointer 갱신은
onboarding이 아니라 호출자(`trip.application.invite`)가 한다 — onboarding 패키지가 trip 패키지를
참조하지 않게 하기 위함이다.

임베딩이 `PENDING`이어도 온보딩 자체를 마쳤으면 `READY`다. vector 준비 여부는 점수 계산(WORK-05/06)에서
따로 본다.

### 초대는 링크 하나에 손님 여러 명이다

baseline의 `trip_invitations.participant_id`(단일 FK)는 이 모델과 맞지 않아 제거하고,
`trip_participants.trip_invitation_id`로 역참조한다. 동시에 여러 명이 같은 링크로 참여해도 각자
별도 participant와 guest session을 받는다.

## 완료 기준

- [x] 초대 발급이 201과 함께 원문 token을 **한 번만** 반환하고, 목록·조회 응답에는 싣지 않는다.
- [x] DB에는 원문이 아니라 SHA-256 해시만 저장한다(원문으로 조회하면 찾히지 않는다).
- [x] `expiresInDays`가 1·30은 허용되고 0·31은 400 `INVALID_EXPIRES_IN_DAYS`다.
- [x] 소유자가 아니면 초대·공유 링크 API가 404 `TRIP_NOT_FOUND`로 존재를 숨긴다.
- [x] 다른 여행의 초대 ID를 조합하면 404 `INVITE_NOT_FOUND`다.
- [x] 만료·폐기된 초대는 410이고 권한 부족은 403이다.
- [x] 표시 이름은 trim 후 1~30자이며 공백뿐이거나 31자면 400이다.
- [x] 같은 링크로 동시에 참여해도 각자 별도 participant와 session을 받는다.
- [x] 다른 participant의 온보딩은 제출할 수 없다(401 `GUEST_SESSION_INVALID`).
- [x] 용도가 다른 token을 쓰면 400 `TOKEN_AUDIENCE_MISMATCH`다.
- [x] 공유 링크 open이 303 redirect하며 Location과 본문에 원문 token이 없다.
- [x] 링크를 폐기·만료시키면 이미 발급된 세션도 410이 된다.
- [x] 권한을 EDIT로 바꾸면 세션 조회 결과도 EDIT다.
- [x] 성공·실패 통합 테스트가 있다(`InviteSharingIntegrationTest`, 24개).
- [x] API 문서가 구현과 일치한다.

## 구현 메모

- Entity: `trip/domain/TripInvitation`(permission 추가), `CourseShareLink`, `GuestSession`,
  `ShareSession`, `TripParticipant`(guest 생명주기), `profile/domain/GuestTasteVector`
- Enum: `SharePermission`(VIEW/EDIT + 파싱), `TokenAudience`(용도 접두사)
- Repository: `TripInvitationRepository`, `CourseShareLinkRepository`, `GuestSessionRepository`,
  `ShareSessionRepository`, `GuestTasteVectorRepository`
- Service: `trip/application/invite/InviteService`, `ShareLinkService`;
  `TripService.requireOwnedTrip` 공개, `OnboardingService.submitForGuest` 추가
- Controller: `InviteController`, `InviteParticipantController`, `ShareLinkController`,
  `SharedCourseController`
- 인증: `GuestSessionArgumentResolver`(`@CurrentGuestParticipant`), `ShareSessionCookieFactory`
- 예외: `INVITE_*`, `SHARE_LINK_*`, `GUEST_SESSION_INVALID`, `SHARE_SESSION_INVALID`,
  `INVALID_SHARE_PERMISSION`, `INSUFFICIENT_SHARE_PERMISSION`, `TOKEN_AUDIENCE_MISMATCH`
- 마이그레이션: `V5__invitations_and_sharing.sql`

## 결정과 미해결 사항

- `GET /api/shared/courses`의 `stops`는 코스 리소스가 생기기 전까지 빈 배열이다. WORK-06/07/08이
  실제 stop을 채운다.
- 편집 endpoint는 WORK-07/08 소관이다. 이번 작업은
  `ShareLinkService.requireEditableSession(...)` 계약만 제공하며, 호출부가 생길 때
  `INSUFFICIENT_SHARE_PERMISSION` 경로를 API 레벨에서도 테스트한다.
- guest session TTL 30일은 "회원가입을 강제하지 않고 이후에도 유효한 링크로 접근한다"는 결정에
  맞춘 값이다. 여행 종료 후 정리 정책은 아직 없다.

## 변경 이력

| 날짜 | 변경 | 이유 |
|---|---|---|
| 2026-09-23 | 초안 작성과 구현 완료 | WORK-04 착수 |
