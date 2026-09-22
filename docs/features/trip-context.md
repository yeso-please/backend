# 여행 context와 날짜 중복 차단

- 상태: done (guest 참여자·지역 추첨·확정 흐름은 후속 작업)
- 담당 범위: trip
- 작성일: 2026-09-21
- 갱신일: 2026-09-21
- 관련 이슈: #13 (WORK-03)
- 관련 API: [Trip API](../api/trips.md)

## 목표

지역 추첨 전 DRAFT 여행 방을 만들고, 같은 사용자의 확정(CONFIRMED) 여행과 날짜가 겹치는 요청을 클라이언트
우회 여부와 무관하게 서버가 항상 거부한다.

## 범위

### 포함

- `POST /api/trips/context/check`: 저장 없이 겹침 여부·확정 시 종료일·일자별 활동 시간대를 계산
- `POST /api/trips`: 겹치지 않을 때만 DRAFT 여행 생성 + OWNER participant를 같은 트랜잭션에서 READY로 생성
- `GET /api/trips/{id}/context` / `PATCH /api/trips/{id}/context`: 소유자만, DRAFT만 수정 가능(낙관적 버전)
- `GET /api/trips/unavailable-dates`: 확정 여행 때문에 선택할 수 없는 날짜 구간
- overlap 판정은 같은 owner의 **CONFIRMED** 여행만 대상으로 하며 양끝을 포함한다
- 당일/다일 여행의 일자별 활동 시간대(첫날 12~20, 중간 09~20, 마지막 날 09~17) 계산

### 제외 (후속)

- 지역 추첨(WORK-05) — DRAFT의 `region`은 이번 범위에서 항상 null이다
- 비회원(GUEST) participant, 초대(WORK-04)
- 여행 확정(WORK-08) — `CONFIRMED` 상태로의 전이 자체(및 그때의 idempotency/overlap 재검사)는 이번 범위 밖이고,
  테스트에서는 repository로 직접 상태를 바꿔 확정 상태를 시뮬레이션한다

## 사용자 흐름

1. 사용자가 캘린더에서 날짜를 고르기 전 `GET /api/trips/unavailable-dates`로 선택 불가 구간을 받는다.
2. 날짜를 고르면 `POST /api/trips/context/check`로 즉시 겹침 여부를 확인한다(저장 없음).
3. 겹치지 않으면 `POST /api/trips`로 DRAFT 여행을 만든다 — 이 순간 요청자 자신이 OWNER participant로
   등록되고 즉시 READY 상태다.
4. DRAFT 상태에서는 `PATCH /api/trips/{id}/context`로 날짜·이동수단·출발지를 다시 바꿀 수 있다 — 성공하면
   `draftInvalidated=true`가 내려와 이후 단계(지역/코스)를 다시 계산해야 함을 알린다.
5. 여행이 확정되면(WORK-08) context 수정은 `TRIP_CONTEXT_LOCKED`로 막힌다.

## 완료 기준

- [x] `nights`는 0~6만 허용하고 -1/7은 400 `TRIP_INVALID_NIGHTS`를 반환한다.
- [x] `startDate`는 오늘 이후만 허용하고 오늘/과거는 400 `TRIP_INVALID_START_DATE`를 반환한다.
- [x] `transport`가 WALK/CAR/PUBLIC_TRANSIT이 아니면 400 `TRIP_INVALID_TRANSPORT`를 반환한다.
- [x] `origin`은 lat/lng를 함께 주거나 함께 생략해야 하며, 범위를 벗어나면 400 `TRIP_INVALID_ORIGIN`을 반환한다.
- [x] 같은 owner의 CONFIRMED 여행과 날짜가 겹치면(포함/부분 겹침 포함, 양끝 포함) check는 `available=false`+
      `conflicts`를, create/patch는 409 `TRIP_DATE_OVERLAP`+`details.conflicts`(trip id/title/기간)를 반환한다.
- [x] DRAFT/CANCELLED 여행은 overlap 판정에서 제외된다.
- [x] 여행 생성 시 owner participant가 같은 트랜잭션에서 READY로 만들어진다(trip당 OWNER는 DB에서 유일).
- [x] 존재하지 않거나 타인 소유인 여행은 404 `TRIP_NOT_FOUND`로 동일하게 응답한다(소유 여부 비노출).
- [x] 확정된 여행의 context를 고치려 하면 409 `TRIP_CONTEXT_LOCKED`를 반환한다.
- [x] `PATCH`는 `version`이 필수이며 낙관적 버전이 다르면 409 `TRIP_VERSION_CONFLICT`를 반환하고, 동시에 같은
      버전으로 두 요청이 오면 정확히 하나만 성공한다.
- [x] 당일은 12~20시, 다일은 첫날 12~20·중간 09~20·마지막 날 09~17시 활동 시간대를 반환한다(윤년·연말 경계 포함).
- [x] 성공·실패·동시성 통합 테스트가 있다(`TripIntegrationTest`), 겹침 판정과 시간대 계산 단위 테스트가
      있다(`TripPlanOverlapTest`, `TripDayWindowCalculatorTest`).
- [x] API 문서가 구현과 일치한다.

## 구현 메모

- Entity: `trip.domain.TripPlan`(버전·상태·날짜·이동수단·출발지), `trip.domain.TripParticipant`(OWNER 생성만
  다룸), `Transport`/`TripPlanStatus`/`TripParticipantType`/`TripParticipantStatus`
- Repository: `trip.infrastructure.TripPlanRepository`(overlap 조회 파생 쿼리),
  `trip.infrastructure.TripParticipantRepository`
- Service: `trip.application.TripService` — 필드 검증(startDate/nights/transport/origin), overlap 조회,
  낙관적 버전 검사(사전 비교 + Hibernate `ObjectOptimisticLockingFailureException` 포착 둘 다)
- Controller: `trip.presentation.TripController`
- DTO: `CreateTripRequest`, `TripContextCheckRequest`, `UpdateTripContextRequest`, `TripContextResponse`,
  `TripContextCheckResponse`, `TripConflictResponse`, `DayWindowResponse`, `UnavailableDateRangeResponse`
- 도메인 로직: `TripDayWindowCalculator`(활동 시간대), `TripPlan.overlaps()`(양끝 포함 겹침 판정)
- 예외: `trip.domain.TripException`(베이스) → `InvalidStartDateException`, `InvalidNightsException`,
  `InvalidTransportException`, `InvalidOriginException`, `TripDateOverlapException`(409, `details.conflicts`
  포함), `TripNotFoundException`(404), `TripContextLockedException`(409), `TripVersionConflictException`(409)
- 공통 계층 확장: `DomainException`/`ApiErrorResponse`에 `details`(구조화된 부가 정보) 필드를 추가했다 —
  기존 오류는 빈 map이라 `@JsonInclude(NON_EMPTY)`로 응답 모양이 그대로 유지된다.
- 마이그레이션: `V4__trip_context.sql` — `trip_plans.region_id` nullable, `start_date`/`end_date` NOT NULL,
  `version` 추가, `status` CHECK에 `CANCELLED` 추가, origin XOR/범위 CHECK 추가, 미사용 `trip_members` 제거,
  `trip_participants`에 OWNER 유일성 unique index 추가
- 패키지 이동: 평면 `domain` 패키지의 `TripPlan`/`Transport`/`TripPlanStatus`를 `trip.domain`으로 옮겼다
  (docs/conventions/모듈-의존성.md의 "수정할 때 점진적으로 이동" 원칙) — `TripStop`/`TripInvitation`(둘 다 후속
  작업의 미완성 골격)은 import만 갱신했다.

## 결정과 미해결 사항

- **`trip_members` 제거**: WORK-04 이후 모든 작업서가 `trip_participants`(GUEST 지원)를 참조하고
  `trip_members`(OWNER/MEMBER만 구분)는 어디에도 쓰이지 않아, `onboarding_responses`와 같은 이유로 제거했다.
- **타인 접근을 404로 통일**: trip 도메인에는 별도의 FORBIDDEN 코드가 명세돼 있지 않아, 존재하지 않는 리소스와
  접근 권한 없는 리소스를 구분하지 않고 `TRIP_NOT_FOUND` 404로 응답해 존재 여부 자체를 노출하지 않는다.
- **낙관적 버전 이중 검사**: 클라이언트가 보낸 `version`이 현재 값과 다르면 쓰기 전에 즉시
  `TRIP_VERSION_CONFLICT`로 막고, 그 검사를 통과한 두 요청이 진짜로 동시에 들어오는 경우는 JPA
  `@Version`이 flush 시점에 `ObjectOptimisticLockingFailureException`을 던지게 하고 이를 같은 예외로
  변환한다 — 사전 검사만으로는 진짜 동시 요청(race)을 잡지 못하기 때문이다.
- **공통 `details` 필드 추가**: 작업서의 오류 envelope 예시는 이미 `details`를 포함하지만 기존 구현에는
  없었다. WORK-03의 overlap 응답에 conflicts 목록이 반드시 필요해 공통 계층에 추가했다 — 이후 작업(WORK-05
  `ignoredConditions`, WORK-08 `conflicts` 등)도 같은 필드를 재사용할 수 있다.
- **미해결**: `title`/situation 스냅샷 등 후속 작업이 쓸 필드는 스키마에 남겨뒀지만 이번 범위에서는 채우지
  않는다. 여행 확정(WORK-08) 시 이 필드들을 어떻게 채울지는 그때 다시 정의한다.

## 변경 이력

| 날짜 | 변경 | 이유 |
|---|---|---|
| 2026-09-21 | context 생성/조회/수정, overlap 차단, 활동 시간대 계산 최초 구현 | WORK-03(이슈 #13) |
