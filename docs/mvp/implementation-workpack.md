# MVP 에이전트 구현 작업서 — 단독 실행 계약

- 상태: ready
- 기준일: 2026-09-20
- 사용법: 이 문서 전체와 구현할 `TARGET_WORK=WORK-XX` 한 줄만 에이전트에게 전달한다.
- 우선순위: 이 문서가 구형 `FEATURE-SPEC.md`, `API-DESIGN-DRAFT.md`, `BUTTON-SPEC.md`보다 우선한다.

이 문서는 다른 기획 문서를 보지 않아도 TriPin MVP 전체와 각 작업의 입력·출력·규칙·DB·테스트를 이해하도록 만든 실행 명세다. 지정된 WORK만 구현하고 범위 밖 개선은 `Remaining`에 적는다.

## 1. 제품 전체 계약

TriPin은 이메일로 가입한 사용자가 여행 성향을 입력하고, 날짜와 선택 조건에 따라 전국 250개 시군구 중 한 곳을 추첨한 뒤, 본인과 비회원 동행자의 성향·동선·식사시간을 고려한 여행 코스를 만드는 서비스다.

```text
가입/로그인 → 온보딩 → 여행 날짜/기간 입력 → 선택적 친구 초대
→ 완전/조건 기반 지역 추첨 → 지역 카드 → 코스 초안
→ 관광지 편집/식당 선택 → 확정 → VIEW/EDIT 공유
```

고정값:

- 로컬 이메일 로그인만 제공한다. 소셜 로그인은 제외한다.
- `nights=0..6`, `days=nights+1`, `endDate=startDate+nights`다.
- 기존 확정 여행과 날짜가 겹치면 캘린더에서 선택할 수 없고 서버도 생성·변경을 거부한다.
- 지역 리롤은 직전 지역을 제외하지 않는다.
- 추천 관광지는 유효한 지역·좌표·상세 설명·검증 이미지가 모두 필요하다.
- 지역은 승인 소개문·검증 hero image·`days*density.maxPlacesPerDay+min(days,3)`개 추천 후보가 필요하다.
- 다일 여행은 첫날 12:00 도착, 마지막 날 17:00 출발이다. 당일은 12:00~20:00다.
- 일정 밀도는 `RELAXED` 최대 4곳, `PACKED` 최대 6곳이며 시간 제약상 목표를 억지로 채우지 않는다. 점심은 12:00~13:00, 저녁은 18:00~19:00이다.
- Python은 임베딩만 생성하고 Spring이 점수와 코스를 계산한다.
- LLM은 실제 선택 장소를 바꾸지 않고 40자 이내 제목만 만든다.
- 길찾기 확정 전 이동은 직선거리 추정이며 항상 `estimated=true`다.

MVP 제외: 카카오 로그인/서버 직접 메시지, 회원 친구 병합, 실시간 공동 편집, 근거 없는 식당 맛·평점·인기·대표메뉴 추론, 혼잡도, 실측 체류시간, pgvector.

## 2. 모든 에이전트가 지킬 실행 프롬프트

```text
TARGET_WORK 하나만 완성하라.
먼저 AGENTS.md, docs/conventions, 현재 entity/migration/test와 git status를 읽어라.
사용자 변경을 덮어쓰지 마라. 시작 전에 계약과 코드 차이, 수정 파일,
새 Flyway migration, 테스트 목록을 보고하라.

Java 21/Spring Boot 4.1/PostgreSQL 17/Flyway를 사용한다.
Flyway SQL만 schema를 바꾸며 ddl-auto는 validate다. 공유된 migration은 수정하지 않는다.
DTO는 record, request는 Bean Validation, entity는 API로 직접 반환하지 않는다.
Controller/Application/Domain/Infrastructure 책임을 분리한다.
외부 연동은 adapter 뒤에 두고 테스트는 stub/fake로 수행한다.
비밀번호, API key, JWT, refresh/invite/share token 원문, 자유서술을 로그에 남기지 않는다.
권한은 application service에서 resource와 함께 검사한다.
코드+migration+테스트+docs/features+docs/api를 같은 PR에 포함한다.

완료 전 ./gradlew compileJava test --no-daemon을 실행한다.
DB 변경은 PostgreSQL Testcontainers에서 빈 DB migrate, JPA validate,
제약 실패, migrate 재실행 no-op을 확인한다. 못 돌린 테스트를 통과했다고 쓰지 마라.
```

공통 API: base `/api`, 날짜 `YYYY-MM-DD`, 시간 `HH:mm`/Asia-Seoul, 내부 ID `Long`, 지역만 5자리 `SIG_CD`. 성공은 wrapper 없이 DTO, 생성 201, 조회/수정 200, 빈 성공 204다.

오류 envelope:

```json
{"timestamp":"2026-09-20T10:00:00Z","status":400,"code":"COMMON_INVALID_REQUEST","message":"요청 값이 올바르지 않습니다.","path":"/api/example","fieldErrors":[],"details":{}}
```

`fieldErrors/details`는 비면 생략 가능하다. CORS는 정확한 origin allowlist만 허용한다. token은 최초 링크 교환 뒤 URL에서 제거하고 DB에는 SHA-256 hash만 저장한다.

권장 순서: `00 → (01,09A) → 02/03 → 04 → 05 → 06 → 07 → 08 → 10`, 이후 `09B/dev RDS`다.

---

## WORK-00 PostgreSQL 기반

구현 완료, 기준 커밋 `06b4ac5`다. PostgreSQL 17 Compose, Flyway V1, `app` schema, `ddl-auto=validate`, Testcontainers, CI, 공통 오류, `@CurrentUserId`가 있다. 후속 작업은 V1을 고치지 않고 V2 이상을 추가한다. `onboarding_responses`는 WORK-02 전환용 임시 테이블이며 pgvector는 추가하지 않는다.

---

## WORK-01 로컬 인증과 refresh 회전

### 기능

이메일·비밀번호·닉네임 가입, 로그인, access 갱신, 로그아웃, 내 정보 조회를 구현한다. 가입 직후에도 token을 발급한다.

- email: trim+lowercase, 유효 형식, 최대 190자
- password: 8~64자이며 UTF-8 72 bytes 이하, BCrypt 저장
- nickname: trim 1~30자
- 로그인 실패는 계정 존재 여부와 무관하게 동일 401
- access JWT 30분: `sub=userId,type=access,iat,exp,jti`, key 최소 256 bit
- refresh는 256 bit 이상 opaque token, 14일, SHA-256 hash만 저장
- cookie `refresh_token; HttpOnly; SameSite=Lax; Path=/api/auth`; production만 Secure
- refresh 성공 시 기존 row를 lock해 폐기하고 새 token으로 회전
- 폐기 token 재사용 시 같은 family의 미만료 token 전부 폐기
- 동시 refresh는 정확히 하나만 성공

API:

| Method | Path | 성공 | 오류 |
|---|---|---|---|
| POST | `/auth/signup` `{email,password,nickname}` | 201 AuthResponse+cookie | 400, `EMAIL_ALREADY_EXISTS` 409 |
| POST | `/auth/login` `{email,password}` | 200 AuthResponse+cookie | `INVALID_CREDENTIALS` 401 |
| POST | `/auth/refresh` cookie | 200 새 access+cookie | refresh invalid/expired 401 |
| POST | `/auth/logout` | 항상 204, 있으면 폐기 | 없음 |
| GET | `/users/me` | user+onboarding 상태 | 401 |

```json
{"user":{"id":1,"email":"user@example.com","nickname":"여행자","profileImage":null},"accessToken":"<jwt>","tokenType":"Bearer","expiresInSeconds":1800,"onboardingCompleted":false}
```

Migration: refresh에 `family_id UUID`, `revoked_at`, `replaced_by_token_id`를 추가한다. JWT filter가 `AuthenticatedUserPrincipal`을 넣고 임시 permit-all/in-memory user를 제거한다. public은 auth, 질문 조회, invite/share 교환뿐이다.

테스트: 정규화 중복, BCrypt, 동일 로그인 오류, JWT 위조/만료/type, 회전/재사용/로그아웃, 두 thread refresh, cookie profile별 속성, `/me`, 비밀 로그 미노출.

완료 문서: `docs/features/auth-local-login.md`, `docs/api/auth.md`.

---

## WORK-02 온보딩·재검사·임베딩 job

### 질문과 점수

버전은 `demo-mbti-v1`. 각 문항 choice는 1/2이고 글자는 다음과 같다.

| No | 축 | 질문 | 선택 1 → 글자 | 선택 2 → 글자 |
|---:|---|---|---|---|
| 1 | JP | 여행을 떠날 때 계획은 | 내가 걷는 길이 곧 여행코스 → P | 계획은 필수 → J |
| 2 | JP | 여행 경비는 | 당장 국제거지만 안되면 되지! → P | 걸어다니는 계산기로 변신 → J |
| 3 | JP | 여행을 다녀온 후 | 홈스윗홈.. 침대로 점프! → P | 캐리어를 열고 물건을 정리한다 → J |
| 4 | JP | 여행지에서 식사할 때 | 유~명한 맛집을 작정하고 노리는 헌터 → J | 처음 본 순간 사랑에 빠진 길거리 가게 → P |
| 5 | SN | 여행지에서 길을 잃었을 때 | 왔던 길로 돌아가는 헨젤과 그레텔st. → S | 자꾸 걸어 나가면 길이 있겠지, 지구는 둥그니까 → N |
| 6 | SN | 화려한 건축물을 보며 드는 생각은 | "어떤 방법으로 지었을까?" 고민한다 → S | "와 멋있다..." 감탄한다 → N |
| 7 | TF | 아침에 늦잠 잔 친구에게 | "여행이 역시 피곤하지." → F | "내일은 시간 지키자." → T |
| 8 | TF | 친구에게 차 사고가 났다고 전화 왔을 때 나의 대답은 | "괜찮아? ㅠㅠ 다친 데는 없어?" → F | "보험 들었어?" → T |
| 9 | TF | 친구가 쓸데없는 기념품을 살 때 | "그래 니가 행복하다면..." → F | "그거 결국 쓰레기 된다" → T |
| 10 | EI | 나는 여행지를 선택할 때 주로 | 사람이 많은 도시로 → E | 나무가 많은 자연으로 → I |
| 11 | EI | 숙소를 구할 때 | 저녁에 바비큐 파티를 여는 곳 → E | 조용하고 아늑한 곳 → I |
| 12 | EI | 여행지에 대한 감상을 | 말로 내뱉어야 직성이 풀린다 → E | 내 마음 속에 저_장, 마음에 담고 느낀다 → I |

축별 선택 글자의 개수를 비교하고 동점이면 `I/N/F/P`를 선택한다. 결과 순서는 `EI + SN + TF + JP`다. 이 표를 그대로 `GET /onboarding/questions`의 서버 상수와 테스트 fixture로 사용하며, 문구나 매핑을 바꾸면 기존 version을 수정하지 말고 새 version을 만든다.

경험 태그 최대 5개: `자연,바다,산,산책,골목,역사,시장,로컬 음식,카페,휴식,실내,체험`. 제외 태그: `계단·경사 많은 곳,물놀이,야간 이동,오래 걷기`.

MBTI 12문항과 별도로 일정 밀도 선호를 필수로 받는다. `RELAXED`는 하루 최대 4곳, `PACKED`는 최대 6곳이다. 이 값은 MBTI 글자 계산에 포함하지 않으며 여행별 course draft 요청에서 override할 수 있다.

Submission 규칙:

- 1~12 답이 각각 정확히 한 번, choice 1/2
- liked trips 0~30, 같은 SIG_CD 중복 금지, note trim 최대 500자
- liked tag는 경험 태그 부분집합, region은 DB에 존재
- user 또는 guest participant 중 정확히 한 owner
- 제출/답변은 immutable, 재검사는 새 UUID와 latest pointer 교체
- profile text는 MBTI→일정 밀도→정렬된 경험→제외→SIG_CD순 liked trip으로 결정적 합성
- 자유서술은 로그 금지

API: `GET /onboarding/questions` public, `POST /onboarding/submissions` user, `GET /onboarding/me` user. 제출에는 `scheduleDensity`가 필요하고 응답은 `submissionId,questionVersion,mbtiCode,scheduleDensity,profileText,tasteStatus,onboardingCompleted,createdAt`이다.

Python은 transaction 성공 뒤 job으로 호출한다. `{requestId,text,modelVersion,templateVersion}`→`{embeddingBase64,dimension,...}`. 성공 READY, 일시 장애 PENDING+재시도, 영구 실패 FAILED다. 실패해도 submission과 onboarding 완료를 되돌리지 않는다. 운영 profile에서 fake vector 금지.

Migration: `question_version` 문자열화, submission `status/taste_status/completed_at/schedule_density`, 경험/제외/note/tags, user/guest latest pointer, embedding job(`attempts,next_attempt_at,last_error_code`). 기존 `onboarding_responses`는 신규 코드에서 사용하지 않는다.

오류: `INVALID_QUESTION_VERSION`, `MISSING_QUESTION_ANSWER`, `DUPLICATE_QUESTION_ANSWER`, `INVALID_CHOICE`, `INVALID_SCHEDULE_DENSITY`, `TOO_MANY_EXPERIENCE_TAGS`, `UNKNOWN_TAG`, `DUPLICATE_LIKED_REGION`, `TOO_MANY_LIKED_REGIONS`, `REGION_NOT_FOUND`.

테스트: 동점 포함 MBTI, 누락/중복, scheduleDensity 두 값/invalid, 태그 5/6, liked 0/30/31, 재검사 불변성, owner XOR, Python success/timeout/5xx/dimension mismatch.

완료 문서: `docs/features/onboarding.md`, `docs/api/onboarding.md`.

---

## WORK-03 여행 context와 날짜 중복 차단

지역 추첨 전 DRAFT 여행 방을 만든다. `startDate`는 오늘 이후, nights 0~6, transport `WALK|CAR|PUBLIC_TRANSIT`, origin은 lat/lng 둘 다 있거나 둘 다 없다. DRAFT는 region이 null일 수 있고 owner participant를 같은 transaction에서 READY로 만든다.

Overlap은 같은 owner의 CONFIRMED만 대상으로 양끝 포함한다. 겹치는 날짜는 캘린더에서 disabled로 표시하고, 클라이언트를 우회한 요청도 서버가 409로 거부한다.

```text
existing.startDate <= requested.endDate && existing.endDate >= requested.startDate
```

API:

- `GET /trips/unavailable-dates?from=&to=`: 확정 여행 때문에 선택 불가능한 날짜 구간
- `POST /trips/context/check {startDate,nights}`: 저장 없이 계산, 중복이면 `available=false`와 conflicts
- `POST /trips {startDate,nights,transport,origin?}`: 중복이 없을 때만 201 DRAFT+owner participant
- `GET /trips/{id}/context`: 권한 있는 사용자
- `PATCH /trips/{id}/context`: owner+DRAFT만, 변경 시 `draftInvalidated=true`

당일 12~20, 다일 첫날 12~20, 중간 09~20, 마지막 09~17을 반환한다. 확정 여행 날짜 수정은 `TRIP_CONTEXT_LOCKED` 409다.

Migration: `trip_plans.region_id` nullable, `CANCELLED` 상태와 optimistic `version`, owner participant DB unique. 오류: `INVALID_START_DATE/NIGHTS/TRANSPORT/ORIGIN`, `TRIP_DATE_OVERLAP`, `TRIP_NOT_FOUND`, `TRIP_CONTEXT_LOCKED`, `TRIP_VERSION_CONFLICT`.

테스트: 0/6박과 -1/7박, 윤년/연말, overlap 네 형태의 check/create/patch 차단, DRAFT/CANCELLED 제외, origin XOR/범위, 타인 접근, 확정 수정, 동시 version 충돌.

완료 문서: `docs/features/trip-context.md`, `docs/api/trips.md`.

---

## WORK-04 비회원 초대와 VIEW/EDIT 공유

최초 여행 방 생성·지역 추첨·코스 생성은 회원 소유자만 한다. 소유자는 `친구와 같이 여행가기`에서 초대 링크를 만들고 프론트 카카오톡 공유 UI 또는 링크 복사로 전달한다. MVP 서버는 카카오 메시지나 앱 알림을 직접 발송하지 않는다.

초대 손님은 가입 없이 표시 이름과 온보딩을 완료해 추천 성향에 참여한다. 완성 일정은 기본 VIEW이며, 소유자가 피그마처럼 링크별 권한을 `VIEW|EDIT`로 바꿀 수 있다. 회원가입을 강제하지 않고 이후에도 유효한 링크/share session으로 접근한다.

Invite(참여/온보딩)와 Share(확정 일정 권한)는 별도 token이다. 최소 256 bit URL-safe CSPRNG, DB SHA-256 hash, audience/type/expiry/revokedAt를 둔다. 생성 응답에서 원문을 한 번만 반환한다. 기본 7일, 허용 1~30일이다.

상태: `INVITED→ONBOARDING→READY`, 어느 상태든 REVOKED 가능. 표시 이름 trim 1~30자. 온보딩 완료면 vector가 PENDING이어도 READY지만 점수에서는 vector 준비 전 제외한다.

Invite API:

- `POST /trips/{tripId}/invites {permission,expiresInDays}` owner
- `GET/PATCH /trips/{tripId}/invites[/{inviteId}]` owner
- `GET /invites/{token}` public 최소 요약
- `POST /invites/{token}/participants {displayName}` participant+guest session
- `POST /invite-participants/{id}/onboarding` guest session, WORK-02 service 재사용

Share API: `POST/GET/PATCH /courses/{id}/share-links`, `GET /shared/courses/{token}`. 링크 open은 HttpOnly short session으로 교환 후 token 없는 URL로 303 redirect한다. VIEW는 조회만, EDIT는 장소·순서·식당만 변경하며 날짜/owner/participant/share 권한은 금지한다. 카카오는 프론트 Share JS만 사용하고 서버 메시지 API는 구현하지 않는다.

오류: `INVITE_EXPIRED/REVOKED`, `GUEST_SESSION_INVALID`, `SHARE_LINK_EXPIRED/REVOKED`, `INSUFFICIENT_SHARE_PERMISSION`, `TOKEN_AUDIENCE_MISMATCH`. 만료/폐기 410, 권한 403.

DB: invite permission/revokedAt, participant latest onboarding/version, 동일 invite+session participant unique. 테스트: 원문 미저장, 만료 경계, token type 혼용, 동시 participant 생성, 다른 participant 제출, VIEW/EDIT 행렬, 다른 trip ID 조합, redirect/history에서 token 제거.

완료 문서: `docs/features/trip-invitation.md`, `docs/api/invitations-and-sharing.md`.

---

## WORK-05 지역 품질·추첨·카드

추천 관광지는 region, 유효 좌표, nonblank description, VALID image 최소 1개, blocking quality issue 없음이 모두 필요하다. 지역은 최신 APPROVED content, VALID hero, 후보 수 `days*density.maxPlacesPerDay+min(days,3)` 이상일 때만 eligible이다. density가 없으면 owner의 온보딩 값을 사용한다.

FULL_RANDOM은 적격 지역 동일 weight. CONDITIONAL은 1개 이상 `DISTANCE|MY_TASTE|COMPANION_TASTE`:

```text
distanceScore = 1-clamp(haversineKm(origin,center)/300,0,1)
tasteScore = 지역 관광지 cosine 상위 min(5,N) 평균을 (x+1)/2 변환
companionScore = 호환 vector가 있는 READY 참여자별 지역 점수의 평균
weight = 1 + 선택되어 실제 적용된 score 합
```

데이터 없는 조건은 `ignoredConditions`; 전부 무시돼도 base random+warning. 조건 배열이 빈 CONDITIONAL은 400. 최대값 선택이 아니라 weight 확률 추첨이다. 랜덤 source는 주입해 테스트한다. 리롤 exclusion은 없다.

API: `GET /regions?days=&scheduleDensity=`, `POST /discovery/draw {tripId,mode,conditions,scheduleDensity?}`, `GET /regions/{sigCd}/card?days=&scheduleDensity=`. 날짜/origin/participants는 trip에서 읽고 client participant IDs를 받지 않는다. draw 결과와 적용 density는 DRAFT trip에 저장한다.

카드는 title, 2~4문단 introduction, hero source/license, characteristics, history highlights, 추천 가능 landmark 1~3, sources, updatedAt을 반환한다. 미승인/품질 부족은 422이며 허구 폴백 금지다.

오류: `INVALID_DAYS`, `NO_CONDITION_SELECTED`, `TRIP_CONTEXT_INCOMPLETE`, `NO_ELIGIBLE_REGION`, `REGION_CONTENT_NOT_READY`.

테스트: days 1~7, 품질 교집합, 승인/hero, 동일 weight 경계 매핑, 조건 단독/조합, origin/vector/companion 없음, 동일 지역 재등장, 후보 0 summary, 카드 source/landmark.

완료 문서: `docs/features/region-discovery.md`, `docs/api/regions-and-discovery.md`.

---

## WORK-06 개인화 코스 조립·폴백·제목

`POST /courses/draft`는 `{tripId,scheduleDensity?,regionArrivalTime?,regionDepartureTime?,mealPreferences}`를 받는다. trip은 DRAFT+region 선택 상태다. `scheduleDensity`는 여행별 override이며 없으면 owner 최신 온보딩 값을 쓴다. 기본 window는 당일 12~20, 다일 첫날 12~20, 중간 09~20, 마지막 09~17. 식사 slot 전체가 window 안에 있을 때만 넣는다.

조립 순서:

```text
WORK-05 quality/exclusion filter → owner/READY별 cosine 후 평균
→ category 다양성 후보 → 날짜 window/식사 선점
→ 가까운 순열을 제한된 beam/greedy로 생성
→ hard constraint 검사 → 최고 feasible 일정 → LLM 제목
```

기본 score는 설정/version으로 분리: taste .55 + diversity .20 + exposure .05 - travel .20 - crowding. 체류는 포토/전망 60, 일반 90, 대형 문화·레포츠 120, unknown 90분. `RELAXED`는 하루 최대 4곳, `PACKED`는 최대 6곳이다. 시간·휴무·식사·이동 제약 때문에 상한보다 적으면 유효한 일정과 `DENSITY_TARGET_NOT_MET` warning을 반환한다.

이동 추정:

```text
WALK distance*1.25/4kmh
CAR distance*1.35/35kmh
PUBLIC_TRANSIT distance*1.50/25kmh
```

분 올림, 최소 5분, `ROUTE_TIME_ESTIMATED` warning. Hard constraint는 지역/품질, 중복 없음, 날짜 window, 식사 침범 없음, 알려진 휴무, 선택 밀도의 상한이다. 채우려고 깨지 않는다.

폴백: 호환 vector→PERSONALIZED, 없으면 같은 지역 공식 코스의 추천 가능 mapped stop→TOUR_OFFICIAL, 부족하면 취향 없는 RULE_BASED, 모두 실패하면 422와 날짜별 부족 사유다. 요청마다 Python 재시도 금지.

LLM 입력은 지역/days/실제 장소/검증 태그뿐. 40자 한 제목 검증 실패/timeout/429/5xx면 `{지역명}, {대표 테마}를 따라 걷는 {days}일`. 장소·순서를 LLM에 맡기지 않는다.

CourseDraft는 `tripId,title,titleSource,recommendationMode,assumptions,days[].items,warnings,draftVersion=1`을 반환한다. item은 ATTRACTION 또는 restaurant null 가능한 MEAL이다. 초안은 DB에 저장하지 않는다.

오류: `TRIP_REGION_NOT_SELECTED`, `INVALID_TRAVEL_WINDOW`, `INCOMPATIBLE_DRAFT_VERSION`, `INSUFFICIENT_COURSE_CANDIDATES`.

테스트: 0~6박/시간 경계, 식사 포함·생략, RELAXED 4/PACKED 6과 목표 미달 warning, 중복/휴무, 60/90/120, 세 transport, 동행 평균/결측, exclusion, 세 추천 mode+422, LLM 장애/금칙, 대량 후보 성능.

완료 문서: `docs/features/course-generation.md`, `docs/api/courses.md`.

---

## WORK-07 지도 기반 코스 편집과 지역 음식·식당

자동 추천 결과를 그대로 확정할 필요는 없다. 사용자는 같은 지역의 추천 가능 관광지를 지도 핀으로 탐색하고 직접 추가·교체·삭제·이동할 수 있다. 핀에는 이름과 작은 대표 이미지를 표시하고, 선택 시 상세 설명·유형·예상 체류시간·`코스에 추가` 버튼에 필요한 데이터를 반환한다.

초안은 client가 들고 오지만 서버가 매번 trip/region/attraction IDs로 재검증하고 시간·이동을 재계산한다.

- `POST /courses/alternatives {tripId,draft,currentAttractionId,category?,limit}`: 기본 10/최대30, 같은 지역, 추천 가능, 현재 코스 제외, 교체 위치 전후 이동 반영. UI group은 `자연|역사·문화|체험·레포츠|산책·휴식|기타`.
- `GET /regions/{sigCd}/attractions?bbox=&category=&cursor=&limit=`: 지도 핀용 ID/name/thumbnail/category/lat/lng/recommendable
- `GET /attractions/{id}`: 검증 이미지·상세 설명·이용정보·예상 체류시간·출처
- `POST /courses/draft/rebalance`: operation `ADD|REPLACE|REMOVE|MOVE`, 전체 feasibility 재계산. client 시간/거리와 날짜·region 변경은 신뢰하지 않는다.
- `GET /places/restaurants/recommendations?tripId=&dayIndex=&meal=&radius=5000`: TourAPI `contentTypeId=39`의 같은 지역 음식점을 1순위로, 승인된 공공 지정 식당을 2순위로 반환
- `GET /places/restaurants/search?tripId=&dayIndex=&meal=&query=&radius=5000&page=1`: 직전 관광지 좌표, 없으면 region center에서 Kakao FD6 거리순 fallback
- `POST /courses/draft/restaurant`: 검증된 Kakao snapshot 추가/교체.
- `POST /courses/draft/restaurant/remove`: DELETE body 대신 제거 요청.

식당 추천은 `TOUR_API`, `FARM_RESTAURANT`, `MODEL_RESTAURANT`, `GOOD_PRICE`, `KAKAO` 출처와 근거 label을 함께 반환한다. TourAPI 음식점은 제공된 대표메뉴·취급메뉴·영업정보·이미지만 사용한다. 카카오 결과에는 provider/externalId/name/category/address/roadAddress/lat/lng/distance/phone/placeUrl만 제공하며 평점·맛·인기·대표성·대표메뉴를 만들지 않는다. 선택 결과는 원천 재조회 또는 짧은 signed search result로 위조를 막는다.

지역 음식/특산물은 source가 있는 `region_food_themes`의 APPROVED 항목만 검색 chip과 추천 이유에 사용한다. 근거 없이 “현지인 맛집”, “유명”, “대표 특산물”이라고 표현하지 않는다.

Kakao adapter는 timeout, 429/Retry-After, 5xx, malformed/empty를 구분하고 key를 숨긴다. owner/EDIT만 사용 가능하며 VIEW는 403이다.

오류: `DRAFT_TAMPERED`, `ATTRACTION_NOT_RECOMMENDABLE`, `ATTRACTION_ALREADY_IN_DRAFT`, `ATTRACTION_REGION_MISMATCH`, `MAP_BOUNDS_INVALID`, `SCHEDULE_INFEASIBLE`, `RESTAURANT_SEARCH_ORIGIN_MISSING`, `KAKAO_LOCAL_UNAVAILABLE/RATE_LIMITED`, `RESTAURANT_RESULT_INVALID`.

테스트: bbox/category/cursor, thumbnail/detail, 품질·타지역 ADD/교체 거부, add/replace/remove/move 재계산, 밀도 상한·식사 충돌, TourAPI 39 우선순위와 공공 근거 label, origin fallback, Kakao timeout/429/malformed, menu 경계, snapshot 위조, 권한 행렬, key 로그 미노출.

완료 문서: `docs/features/course-editing.md`, `docs/api/courses.md`, `docs/api/places.md`.

---

## WORK-08 확정·멱등성·조회·수정

`POST /courses`는 Bearer+`Idempotency-Key`와 `{tripId,draft}`를 받는다.

Transaction 순서: owner/DRAFT 확인→trip row lock→key+canonical body hash→현재 context/region/draftVersion/장소 품질/시간/식당 재검증→overlap 재조회 후 발견 시 409→stop/meal/taste snapshot 저장→CONFIRMED.

같은 owner+key+동일 body replay는 기존 결과 200, 다른 body는 `IDEMPOTENCY_CONFLICT` 409. 두 동시 확정은 하나만 저장한다. canonical JSON은 key 순서/공백에 독립적이다. stop order는 날짜별 0부터 연속이며 transaction 실패 시 일부 row가 남지 않는다.

Overlap 409 `TRIP_DATE_OVERLAP`는 같은 owner의 trip ID/title/기간만 `details.conflicts`로 반환하며 acknowledgement로 우회할 수 없다.

- `GET /courses/{id}` owner/share: 안정적 날짜/순서, token hash/raw vector/private answers 제외
- `PATCH /courses/{id}/schedule`: owner/EDIT, version 필수. 장소·순서·식당만 허용; 날짜/region/owner/participant/share 금지. WORK-06/07 검증 재사용

Migration: optimistic `version`, 필요한 snapshot/reason/mode/titleSource, idempotency unique/index. 오류: `IDEMPOTENCY_KEY_REQUIRED/CONFLICT`, `TRIP_ALREADY_CONFIRMED`, `DRAFT_TAMPERED`, `COURSE_NOT_FOUND`, `COURSE_VERSION_CONFLICT`, `SCHEDULE_INFEASIBLE`.

테스트: 정상 snapshot, 확정 직전 overlap 재검사 차단, key replay/conflict, 두 thread, 타지역/날짜/중복/품질 하락 위조, restaurant 위조, 중간 rollback, owner/VIEW/EDIT, 금지 필드, version conflict, 만료 share.

완료 문서: `docs/features/course-confirmation.md`, `docs/api/courses.md`.

---

## WORK-09 H2 이관·품질·TourAPI·개발 RDS

실행 기준은 [`docs/runbooks/rds-postgresql-bootstrap-and-migration.md`](../runbooks/rds-postgresql-bootstrap-and-migration.md)다. 이 문서는 AWS 계정 안전장치, H2 동결, 이관 실행기 구현, 로컬 리허설, RDS 콘솔 생성, TLS·역할 분리, 최초 적재, 자동 Flyway 반영, 검증·복구, TourAPI 보강까지 A부터 Z 순서로 제공한다.

세 PR로 나눈다.

| PR | 범위 | RDS 변경 |
|---|---|---|
| 09A | H2 read-only 이관 실행기, dry-run/apply/resume/validate, 품질 리포트, Testcontainers | 금지 |
| 09B | 개발 RDS bootstrap runbook 검증, 최초 Flyway·데이터 적재 결과와 snapshot 기록 | dev만 수동 승인 |
| 09C | `rdsMigrationInfo/Validate/Migrate`, CI migration 검증, dev 배포 시 자동 Flyway | dev 배포만 |

반드시 `$flyway-rds-sync`와 `$tourapi-detail-backfill` repository skill을 사용한다. 운영 RDS는 변경 금지다. 현재 저장소에는 WORK-09 이관 runner와 migration 전용 Gradle task가 아직 없으므로, 문서에 적힌 명령이 구현되기 전 RDS 이관을 시작하지 않는다.

역할 경계는 “사람이 RDS와 접근 secret 준비, 에이전트가 이관 A~Z 수행”이다. 사람은 비밀번호를 prompt에 붙이지 않고 로컬 secret/환경변수로만 준비한다. 에이전트는 H2 checksum부터 로컬 리허설, dev RDS Flyway·이관·검증·재실행·리포트까지 담당한다. 그대로 전달할 handoff prompt는 위 RDS runbook 0.2절을 사용한다.

이관 대상: `region→regions`, `attraction→attractions+images`, `travel_course→official_courses`, `course_point→official_course_stops`. 사용자/비밀번호/session/token/후기/업로드/개인 일정·온보딩은 제외한다. 데모 `aiSummary`는 승인 근거가 없어 APPROVED로 이관하지 않는다.

데모 서버 중지→H2 복사→SHA-256 manifest→read-only source. H2 dependency는 별도 tool source set/profile에만 둔다.

09A 공개 도구 모드:

```text
demoMigration --source=... --target=... --mode=dry-run
demoMigration --source=... --target=... --mode=apply [--resume-run-id=uuid]
demoMigration --target=... --mode=validate --run-id=uuid
```

기본 batch 200, batch transaction, cursor/resume, `source_system+source_content_id` upsert, 성공 값을 빈 값으로 overwrite 금지, 실패 row quarantine. `ingestion_runs`에 checksum/tool git SHA/count/status 기록. 두 번째 apply는 예상 밖 insert/update 0이어야 한다.

도구는 exit code를 계약으로 제공한다: 0 성공, 2 인자 오류, 3 source/checksum/schema 오류, 4 target/Flyway 오류, 5 quarantine이 있는 PARTIAL, 6 재개 가능한 실행 실패. 결과는 `build/reports/demo-migration/<run-id>/`의 manifest/counts/quality/quarantine/summary 파일로 남긴다.

검증: 250 region, SIG_CD, 고아 FK, source ID 중복, lat 33~39/lng124~132 이탈, 설명/좌표/이미지 각 수와 교집합, official stop 매핑률, 지역 후보 수, days1~7 eligible 수, `source=inserted+updated+skipped+quarantined`.

TourAPI: 누락 field만 호출, 기존 값 우선, 관광지는 common→type intro→필요 시 image, 음식점 `contentTypeId=39`는 common→음식점 intro→image 순이다. empty는 `SOURCE_EMPTY`, 429는 `QUOTA_EXHAUSTED`+cursor, 5xx/network는 제한된 exponential backoff+jitter. dry-run→10~20 canary→review→batch. key/응답 전문 로그 금지, 여러 key로 quota 우회 금지.

식당 보강은 TourAPI 39를 1순위로 수집하고, 농가맛집·모범/향토음식점·착한가격업소 adapter를 출처별로 추가한다. 전화번호→정규화 주소+상호→50m 좌표+상호 순으로 중복 후보를 만들고 애매한 건 병합하지 않는다. 지역 음식·특산물은 근거 URL과 함께 DRAFT로 적재하며 사람 승인 전 사용자에게 노출하지 않는다.

이미지 검증: http/https, private/loopback/link-local 차단, timeout/크기 제한, redirect 최대3회마다 재검증, HEAD 실패 시 작은 range GET, image content-type. `VALID|INVALID|SOURCE_EMPTY`와 시각/source/license 저장.

지역 소개는 검증 사실+대표 관광지+출처→LLM 2~4문단 DRAFT→사람 검토→APPROVED다. 자동 승인 금지.

Dev RDS gate: local 2회 멱등, 전체 테스트, 대상 host/db가 dev, before snapshot, Flyway info/validate, 예상 count review. 적용 뒤 validate/health/count, after snapshot+논리 dump. `MIGRATION_TARGET_ENV=dev`, DB명 `tripin_dev`, 승인된 dry-run UUID와 source checksum을 모두 검사하고 production host guard를 테스트한다.

09C의 자동 schema 계약:

```text
새 V{N} Flyway SQL + entity + test를 같은 PR에 작성
→ PR CI가 PostgreSQL 17 clean/upgrade/no-op/JPA validate
→ merge 후 dev backend 배포
→ Spring Flyway가 tripin_migrator로 pending migration 적용
→ 애플리케이션은 tripin_app으로 시작하고 ddl-auto=validate
```

PR CI는 RDS에 접속하지 않는다. 이미 공유 DB에 적용된 migration 수정, Flyway clean/repair/out-of-order, `baselineOnMigrate`, 운영 자동 migration은 금지한다. GitHub-hosted runner 접속을 위해 5432를 `0.0.0.0/0`로 열지 않는다. 별도 migration runner가 필요하면 RDS와 같은 VPC의 CodeBuild/ECS task를 사용한다.

상태: `RUNNING|SUCCEEDED|PARTIAL|FAILED|QUOTA_EXHAUSTED`. issue: `MISSING_DESCRIPTION/IMAGE/COORDINATE`, `INVALID_IMAGE`, `UNKNOWN_REGION`, `DUPLICATE_SOURCE_ID`, `OFFICIAL_STOP_UNMAPPED`, `SOURCE_EMPTY`.

테스트: 개인정보 제외, dry-run 무변경, reapply, batch 실패/resume, overwrite 방지, quarantine, TourAPI empty/429/5xx/timeout, image SSRF/redirect/oversize, dev/prod guard.

완료 문서: `docs/features/data-migration.md`, 위 RDS/TourAPI runbook, 실제 실행 결과에서 secret을 제거한 migration report와 복구 기록.

---

---

## WORK-10 여행기·사진 지도·친구 공개

확정 코스마다 사진 여행기 하나를 발행하고, 내 지도에서 핀을 눌러 기록을 다시 본다. 친구 공개는 상호 수락 친구만, 링크 공유는 해당 여행기만 읽기 전용으로 제공한다. 전체 지도 공개·피드·댓글·좋아요는 하지 않는다.

- 사진은 1~30장, 허용 이미지 형식/크기 검증과 악성 파일 검사 후 저장한다. 공개 응답에서 EXIF 전체와 원본 파일명을 제거한다.
- 공개 범위는 `PRIVATE|FRIENDS|LINK`, 위치 정밀도는 `EXACT|CITY|HIDDEN`이며 기본은 `PRIVATE`와 `CITY`다.
- 발행 시 확정 지역·장소 카테고리·명시 만족 태그/점수만 낮은 가중치의 취향 신호로 저장한다. 본문·사진 분석·EXIF·열람 수는 추천에 쓰지 않으며 opt-out을 지원한다.
- 친구 요청은 `PENDING→ACCEPTED|REJECTED|BLOCKED`이며 FRIENDS 조회마다 수락 상태를 서버가 확인한다.

테스트: 여행당 단일 여행기, 사진 0/1/30/31장, 파일 검증, 위치 정밀도별 응답, PRIVATE/FRIENDS/LINK 권한 행렬, 친구 차단 즉시 차단, 링크 만료/폐기, 취향 opt-in/out 및 본문·EXIF 비반영.

완료 문서: `docs/features/travel-diary-map-sharing.md`, `docs/api/travel-diaries.md`.

## 3. 전체 MVP 인수 시나리오

1. 가입 후 token과 `onboardingCompleted=false`를 받는다.
2. 12문항을 제출한다. embedding 장애여도 완료된다.
3. 2박3일 DRAFT를 만들며 확정 여행과 겹치는 날짜는 선택·생성할 수 없다.
4. 비회원 친구가 invite로 온보딩해 READY가 된다.
5. 거리+내 취향+동행 취향으로 추첨하고 결측 조건을 확인한다.
6. 승인 지역 카드와 랜드마크를 본다.
7. RELAXED 또는 PACKED와 첫날 12시/마지막 날 17시·식사를 반영한 초안을 받는다.
8. 지도에서 관광지를 직접 추가·교체하고 TourAPI 우선 식당을 선택하면 전체 시간이 재계산된다.
9. 확정 직전 overlap이 생겼으면 409로 차단되고, 아니면 Idempotency-Key로 한 번만 확정된다.
10. 같은 key/body는 replay, 다른 body는 409다.
11. VIEW는 조회만, EDIT는 장소/식당만 변경한다.
12. 만료/폐기 링크는 차단되고 비밀 원문은 DB/log에 없다.

## 4. PR 완료 보고

```text
Contract: TARGET_WORK, 구현 흐름, 제외 범위
Implemented: 계층별 코드/API/migration
Tests: 실행 명령/결과, 실패·경계·동시성
Data: 테이블/제약/index/backfill/복구
Security: 권한/token/log/CORS
External failures: timeout/429/5xx/fallback
Docs: features/api/ADR/runbook
Remaining: 미충족, 다음 WORK interface, 운영 위험
```

완료라고 말하면 안 되는 경우: PostgreSQL 테스트 미실행을 숨김, 외부 장애 미검증, token 원문 노출, service 권한 누락, 동시 중복 가능, client 시간/식당을 신뢰, 품질 미달을 허구로 채움, 공유 Flyway 수정, 범위 밖 기능 혼합.
