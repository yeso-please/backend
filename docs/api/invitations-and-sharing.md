# 초대·공유 API

- 상태: implemented
- 갱신일: 2026-09-23
- 관련 기능 명세: [비회원 초대와 VIEW·EDIT 공유](../features/trip-invitation.md)

오류는 [공통 오류 응답](common-errors.md) 형식을 따르며, 이 문서의 도메인 코드는 아래와 같다.

| code | HTTP | 의미 |
|---|---:|---|
| `INVITE_NOT_FOUND` | 404 | 존재하지 않는 초대이거나 경로의 여행에 속하지 않는 초대 |
| `INVITE_EXPIRED` | 410 | 초대 링크 만료 |
| `INVITE_REVOKED` | 410 | 소유자가 폐기한 초대 링크 |
| `INVALID_EXPIRES_IN_DAYS` | 400 | `expiresInDays`가 1~30 범위 밖 |
| `INVALID_DISPLAY_NAME` | 400 | 표시 이름이 trim 후 1~30자가 아님 |
| `GUEST_SESSION_INVALID` | 401 | guest session이 없거나 만료·폐기됐거나 다른 참여자의 것 |
| `SHARE_LINK_NOT_FOUND` | 404 | 존재하지 않는 공유 링크이거나 경로의 여행에 속하지 않는 링크 |
| `SHARE_LINK_EXPIRED` | 410 | 공유 링크 만료 |
| `SHARE_LINK_REVOKED` | 410 | 소유자가 폐기한 공유 링크 |
| `SHARE_SESSION_INVALID` | 401 | share session cookie가 없거나 만료됨 |
| `INVALID_SHARE_PERMISSION` | 400 | `permission`이 VIEW/EDIT이 아님 |
| `INSUFFICIENT_SHARE_PERMISSION` | 403 | VIEW 세션으로 편집을 시도함 |
| `TOKEN_AUDIENCE_MISMATCH` | 400 | 다른 용도의 token을 제시함(예: 초대 token으로 공유 링크 열기) |
| `TRIP_NOT_FOUND` | 404 | 존재하지 않거나 본인 소유가 아닌 여행 |

## 토큰 체계

초대(참여·온보딩)와 공유(확정 일정 권한)는 **별도 token**이다. 네 종류 모두 256bit URL-safe
CSPRNG 본문에 용도 접두사를 붙인 opaque token이고, DB에는 SHA-256 해시만 저장한다. 원문은 발급
응답에서 한 번만 반환하며 이후 조회 응답에는 싣지 않는다.

| 용도 | 접두사 | 전달 방법 | 기본 수명 |
|---|---|---|---|
| 초대 링크 | `iv_` | URL path | 7일(1~30일) |
| guest session | `gs_` | `Authorization: Bearer` | 30일 |
| 공유 링크 | `sl_` | URL path | 7일(1~30일) |
| share session | `ss_` | HttpOnly cookie `share_session` | 2시간 |

용도가 다른 token을 쓰면 조회 전에 `TOKEN_AUDIENCE_MISMATCH`로 걸러진다. 접두사는 식별용이며
비밀이 아니다 — 추측 방지는 뒤따르는 CSPRNG 본문이 담당한다.

---

## POST /api/trips/{tripId}/invites

- 목적: 초대 링크를 발급한다. 프론트가 카카오톡 공유 UI나 링크 복사로 전달한다(서버는 메시지를 보내지 않는다).
- 인증: Bearer JWT required
- 권한: 여행 소유자

### Request

```json
{"permission": "VIEW", "expiresInDays": 7}
```

`permission`은 이 링크로 들어온 손님이 확정 일정에 대해 갖는 기본 권한이다. 생략하면 `VIEW`,
`expiresInDays`를 생략하면 7이다.

### Responses

##### 201 Created

```json
{
  "id": 1,
  "token": "iv_3Qk9...",
  "permission": "VIEW",
  "expiresAt": "2026-09-30T12:00:00",
  "createdAt": "2026-09-23T12:00:00"
}
```

`token` 원문은 이 응답에서만 반환한다.

##### 400 Bad Request

`INVALID_EXPIRES_IN_DAYS`, `INVALID_SHARE_PERMISSION`

##### 404 Not Found

`TRIP_NOT_FOUND` — 소유자가 아니면 존재 자체를 숨긴다.

---

## GET /api/trips/{tripId}/invites

- 목적: 여행의 초대 링크 목록을 최신순으로 조회한다.
- 인증: Bearer JWT required
- 권한: 여행 소유자

### Responses

##### 200 OK

```json
[
  {
    "id": 1,
    "permission": "VIEW",
    "expiresAt": "2026-09-30T12:00:00",
    "revoked": false,
    "createdAt": "2026-09-23T12:00:00"
  }
]
```

---

## GET /api/trips/{tripId}/invites/{inviteId}

- 목적: 초대 링크 하나를 조회한다.
- 인증: Bearer JWT required
- 권한: 여행 소유자

### Responses

##### 200 OK

`GET /api/trips/{tripId}/invites`의 배열 원소와 같은 형식이다.

##### 404 Not Found

`INVITE_NOT_FOUND` — 다른 여행의 초대 ID를 조합해도 같다.

---

## PATCH /api/trips/{tripId}/invites/{inviteId}

- 목적: 초대 링크의 권한을 바꾸거나 링크를 폐기한다.
- 인증: Bearer JWT required
- 권한: 여행 소유자

### Request

```json
{"permission": "EDIT", "revoked": true}
```

null인 필드는 변경하지 않는다. `revoked`는 `true`로만 보낼 수 있고 되돌릴 수 없다.

### Responses

##### 200 OK

`GET /api/trips/{tripId}/invites/{inviteId}`와 같은 형식이다.

---

## GET /api/invites/{token}

- 목적: 초대받은 사람이 참여 전에 보는 최소 요약.
- 인증: 없음(public)

### Responses

##### 200 OK

```json
{"valid": true, "startDate": "2026-10-03", "endDate": "2026-10-05", "ownerNickname": "주최자"}
```

여행 내부 정보(참여자 목록, 코스, 다른 초대)는 노출하지 않는다.

##### 410 Gone

`INVITE_EXPIRED`, `INVITE_REVOKED`

##### 400 Bad Request

`TOKEN_AUDIENCE_MISMATCH` — 공유 링크 token을 넣은 경우.

---

## POST /api/invites/{token}/participants

- 목적: 가입 없이 표시 이름만으로 여행에 참여한다.
- 인증: 없음(public)

### Request

```json
{"displayName": "동행이"}
```

trim 후 1~30자여야 한다. 링크 하나로 여러 손님이 각자 참여할 수 있다.

### Responses

##### 201 Created

```json
{
  "participantId": 7,
  "guestSessionToken": "gs_8Fp2...",
  "displayName": "동행이",
  "status": "INVITED"
}
```

`guestSessionToken` 원문은 이 응답에서만 반환한다. 이후 요청은
`Authorization: Bearer {guestSessionToken}`으로 보낸다.

##### 400 Bad Request

`INVALID_DISPLAY_NAME`

##### 410 Gone

`INVITE_EXPIRED`, `INVITE_REVOKED`

---

## POST /api/invite-participants/{id}/onboarding

- 목적: 초대 손님이 온보딩을 제출해 추천 성향에 참여한다. 회원 온보딩(WORK-02)과 같은 질문·채점을 쓴다.
- 인증: `Authorization: Bearer {guestSessionToken}`
- 권한: 해당 participant 본인만

### Request

[온보딩 API](onboarding.md)의 `POST /api/onboarding/submissions`와 같은 body다.

### Responses

##### 201 Created

회원 온보딩과 같은 submission 응답이다. 제출이 끝나면 participant는 `READY`가 된다 —
임베딩이 아직 `PENDING`이어도 마찬가지이며, vector 준비 여부는 점수 계산에서 따로 본다.

##### 401 Unauthorized

`GUEST_SESSION_INVALID` — 세션이 없거나 경로의 participant가 세션 주인이 아닌 경우.

##### 400 Bad Request

`TOKEN_AUDIENCE_MISMATCH` — 초대 token을 guest session 대신 보낸 경우.

---

## POST /api/courses/{tripId}/share-links

- 목적: 확정 일정 공유 링크를 발급한다.
- 인증: Bearer JWT required
- 권한: 여행 소유자

경로의 `{tripId}`는 여행 ID다. 별도의 course 리소스는 WORK-06~08에서 생긴다.

### Request

```json
{"permission": "VIEW", "expiresInDays": 7}
```

`permission`은 필수다.

### Responses

##### 201 Created

```json
{
  "id": 1,
  "token": "sl_7Hs4...",
  "permission": "VIEW",
  "expiresAt": "2026-09-30T12:00:00",
  "createdAt": "2026-09-23T12:00:00"
}
```

##### 400 Bad Request

`INVALID_SHARE_PERMISSION`, `INVALID_EXPIRES_IN_DAYS`

---

## GET /api/courses/{tripId}/share-links

- 목적: 공유 링크 목록을 최신순으로 조회한다.
- 인증: Bearer JWT required
- 권한: 여행 소유자

### Responses

##### 200 OK

```json
[
  {
    "id": 1,
    "permission": "VIEW",
    "expiresAt": "2026-09-30T12:00:00",
    "revoked": false,
    "createdAt": "2026-09-23T12:00:00"
  }
]
```

---

## PATCH /api/courses/{tripId}/share-links/{linkId}

- 목적: 링크별 권한을 `VIEW|EDIT`로 바꾸거나 링크를 폐기한다.
- 인증: Bearer JWT required
- 권한: 여행 소유자

### Request

```json
{"permission": "EDIT", "revoked": true}
```

### Responses

##### 200 OK

목록 응답의 원소와 같은 형식이다. 폐기하면 **이미 발급된 share session도 즉시 무효**가 된다.

---

## GET /api/shared/courses/{token}

- 목적: 공유 링크를 연다. URL의 원문 token을 HttpOnly cookie로 교환하고 token 없는 URL로 redirect한다.
- 인증: 없음(public)

### Responses

##### 303 See Other

```text
Location: /api/shared/courses
Set-Cookie: share_session=ss_...; HttpOnly; SameSite=Lax; Path=/api/shared/courses; Max-Age=7200
```

브라우저 히스토리·referrer·서버 접근 로그에 원문 token이 남지 않게 하려는 교환이다.

##### 410 Gone

`SHARE_LINK_EXPIRED`, `SHARE_LINK_REVOKED`

##### 400 Bad Request

`TOKEN_AUDIENCE_MISMATCH`

---

## GET /api/shared/courses

- 목적: 교환한 세션으로 공유된 일정을 조회한다.
- 인증: `share_session` HttpOnly cookie

### Responses

##### 200 OK

```json
{
  "tripId": 1,
  "permission": "VIEW",
  "status": "DRAFT",
  "startDate": "2026-10-03",
  "endDate": "2026-10-05",
  "stops": []
}
```

`stops`는 코스 생성·편집·확정(WORK-06/07/08)이 들어오기 전까지 항상 빈 배열이다.

##### 401 Unauthorized

`SHARE_SESSION_INVALID`

##### 410 Gone

`SHARE_LINK_EXPIRED`, `SHARE_LINK_REVOKED` — 세션이 살아 있어도 뒤의 링크가 끊기면 볼 수 없다.

---

## 편집 권한 (WORK-07/08 연결점)

장소·순서·식당을 바꾸는 endpoint는 WORK-07/08에서 추가된다. 그 endpoint들은
`ShareLinkService.requireEditableSession(shareSessionToken)`을 호출해 권한을 확인한다 —
VIEW 세션이면 `INSUFFICIENT_SHARE_PERMISSION`(403)이다. 날짜·소유자·참여자·공유 권한 변경은
EDIT 세션으로도 불가능하며 소유자 전용 API로만 다룬다.
