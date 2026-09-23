# 여행 API

- 상태: implemented (context/overlap만 — 지역 추첨·초대·확정은 후속 작업)
- 갱신일: 2026-09-21
- 관련 기능 명세: [여행 context와 날짜 중복 차단](../features/trip-context.md)

오류는 [공통 오류 응답](common-errors.md) 형식을 따르며, 이 문서의 도메인 코드는 아래와 같다.

| code | HTTP | 의미 |
|---|---:|---|
| `TRIP_INVALID_START_DATE` | 400 | startDate가 오늘이거나 과거 |
| `TRIP_INVALID_NIGHTS` | 400 | nights가 0~6 범위 밖 |
| `TRIP_INVALID_TRANSPORT` | 400 | transport가 WALK/CAR/PUBLIC_TRANSIT이 아님 |
| `TRIP_INVALID_ORIGIN` | 400 | origin lat/lng 중 하나만 있거나 범위를 벗어남 |
| `TRIP_DATE_OVERLAP` | 409 | 같은 owner의 확정 여행과 날짜가 겹침(`details.conflicts` 포함) |
| `TRIP_NOT_FOUND` | 404 | 존재하지 않거나 본인 소유가 아닌 여행 |
| `TRIP_CONTEXT_LOCKED` | 409 | 확정/취소된 여행의 context를 수정하려 함 |
| `TRIP_VERSION_CONFLICT` | 409 | PATCH의 version이 현재 값과 다름(동시 수정 포함) |

모든 endpoint는 `Authorization: Bearer {accessToken}` 인증이 필요하다.

## GET /api/trips/unavailable-dates

- 목적: 확정된 여행 때문에 선택할 수 없는 날짜 구간을 반환한다(캘린더 disabled 표시용).

### Request

Query: `from`(YYYY-MM-DD), `to`(YYYY-MM-DD) — 이 범위와 겹치는 확정 여행만 반환한다.

### Responses

##### 200 OK

```json
[
  {"tripId": 1, "startDate": "2026-10-01", "endDate": "2026-10-04"}
]
```

---

## POST /api/trips/context/check

- 목적: 저장 없이 날짜 중복 여부를 미리 확인한다.

### Request

```json
{"startDate": "2026-10-10", "nights": 2}
```

### Responses

##### 200 OK

```json
{
  "available": false,
  "endDate": "2026-10-12",
  "conflicts": [
    {"tripId": 1, "title": null, "startDate": "2026-10-01", "endDate": "2026-10-04"}
  ],
  "dayWindows": [
    {"dayIndex": 0, "date": "2026-10-10", "windowStart": "12:00", "windowEnd": "20:00"},
    {"dayIndex": 1, "date": "2026-10-11", "windowStart": "09:00", "windowEnd": "20:00"},
    {"dayIndex": 2, "date": "2026-10-12", "windowStart": "09:00", "windowEnd": "17:00"}
  ]
}
```

`available=false`여도 400/409를 반환하지 않는다 — 클라이언트가 캘린더에 즉시 표시하도록 200으로 알려준다.

##### 400 Bad Request

`TRIP_INVALID_START_DATE` 또는 `TRIP_INVALID_NIGHTS`.

---

## POST /api/trips

- 목적: 중복이 없을 때만 DRAFT 여행을 만들고 요청자를 OWNER participant(READY)로 등록한다.

### Request

```json
{
  "startDate": "2026-10-10",
  "nights": 2,
  "transport": "WALK",
  "originLat": 37.5665,
  "originLng": 126.9780
}
```

`originLat`/`originLng`는 함께 주거나 함께 생략해야 한다.

### Responses

##### 201 Created

```json
{
  "id": 1,
  "status": "DRAFT",
  "startDate": "2026-10-10",
  "endDate": "2026-10-12",
  "nights": 2,
  "transport": "WALK",
  "originLat": 37.5665,
  "originLng": 126.978,
  "version": 0,
  "dayWindows": [ "..." ],
  "draftInvalidated": false
}
```

##### 400 Bad Request

`TRIP_INVALID_START_DATE` / `TRIP_INVALID_NIGHTS` / `TRIP_INVALID_TRANSPORT` / `TRIP_INVALID_ORIGIN`.

##### 409 Conflict

```json
{
  "status": 409,
  "code": "TRIP_DATE_OVERLAP",
  "message": "이미 확정된 여행과 날짜가 겹칩니다.",
  "details": {
    "conflicts": [
      {"tripId": 1, "title": null, "startDate": "2026-10-01", "endDate": "2026-10-04"}
    ]
  }
}
```

#### Side effects

- `trip_plans`에 DRAFT 1건, `trip_participants`에 OWNER+READY 1건을 생성한다.

---

## GET /api/trips/{id}/context

- 목적: 여행 context를 조회한다.
- 권한: 소유자만(다른 참여자 접근은 WORK-04 이후)

### Responses

##### 200 OK

`POST /api/trips`의 201 응답과 같은 형식(`draftInvalidated`는 항상 `false`).

##### 404 Not Found

`TRIP_NOT_FOUND` — 존재하지 않거나 본인 소유가 아니다(구분해서 노출하지 않는다).

---

## PATCH /api/trips/{id}/context

- 목적: DRAFT 여행의 날짜·이동수단·출발지를 바꾼다.
- 권한: 소유자만, DRAFT 상태만

### Request

```json
{
  "startDate": "2026-10-12",
  "nights": 1,
  "transport": "CAR",
  "originLat": 37.5665,
  "originLng": 126.9780,
  "version": 0
}
```

`version`은 직전에 조회한 응답의 `version`이어야 한다.

### Responses

##### 200 OK

수정된 context + `"draftInvalidated": true`(이후 지역/코스 단계를 다시 계산해야 함을 뜻한다) + 증가된 `version`.

##### 409 Conflict

- `TRIP_CONTEXT_LOCKED`: 이미 확정/취소된 여행이다.
- `TRIP_VERSION_CONFLICT`: `version`이 현재 값과 다르다(동시 수정 포함 — 동시에 같은 버전으로 두 요청이 오면
  정확히 하나만 200을 받는다).
- `TRIP_DATE_OVERLAP`: 변경한 날짜가 다른 확정 여행과 겹친다.

##### 404 Not Found

`TRIP_NOT_FOUND`.

#### Related

- [작업 명세](../features/trip-context.md)
- [사용자 정보 주입](../conventions/유저-정보-주입.md)
