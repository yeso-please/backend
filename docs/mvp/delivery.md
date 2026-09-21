# 초기 세팅과 3인 팀 실행 순서

- 상태: proposed — MVP 작업 배치·선행 계약 기준, 구현 완료 목록 아님
- 담당 범위: WORK별 구현 위치, 담당 분배, 테이블 소유권과 통합 순서
- 갱신일: 2026-09-21
- 기준: [제품 결정](decisions.md), [구현 작업서](implementation-workpack.md), [데이터 설계](data-and-recommendation.md), [모듈·의존성](../conventions/모듈-의존성.md)

## 사용자가 먼저 구현할 범위

완성된 추천 알고리즘이 아니라 팀원이 안전하게 병렬 작업할 기반을 만든다.

1. WORK-00 PostgreSQL/Flyway/Testcontainers/CI와 공통 오류 계약
2. WORK-01 로컬 인증
3. 2~3개 지역 seed로 로그인→온보딩→추첨→규칙 코스→확정/조회 세로 슬라이스
4. 외부 Python/TourAPI/Kakao/LLM adapter와 테스트 stub 경계
5. migration·API 계약 ownership과 팀 브랜치 규칙

기반 완료 전 개발 RDS 전량 이관을 하지 않는다. 로컬 PostgreSQL migration과 작은 seed가 먼저다.

## 담당 분배

| 담당 | 작업 | 선행 조건 | 인계 기준 |
|---|---|---|---|
| 초기 세팅 담당 | WORK-00, 01, 03, 공통 통합·리뷰 | 제품 문서 | 새 clone에서 전체 흐름 테스트 가능 |
| 백엔드 A | WORK-02, 04, 05 | 인증·공통 schema | 온보딩/손님/추첨/카드 실패 경로 포함 |
| 백엔드 B | WORK-06, 07, 08, 09 주담당 | 관광지·여행 schema | 밀도별 코스·지도 ADD·TourAPI 우선 식당·권한·데이터 리포트 포함 |

WORK-09의 빌드·CI·개발 RDS 환경은 초기 세팅 담당이 협업한다. WORK-09A를 초기에 병행하고 09B의 개발 RDS 반영은 검증 게이트 이후 진행한다. migration 번호는 작업 시작 전에 예약해 충돌을 막는다.

## WORK별 구현 위치

모듈 경계와 분할 이유는 [모듈·의존성](../conventions/모듈-의존성.md), 라벨 기준은 [이슈 라벨](../conventions/issue-labels.md)을 따른다. 경로의 `…`는 `application`과 HTTP 진입점이 있는 `presentation`이다. 엔티티·규칙은 각 모듈의 `domain`, Repository·외부 adapter는 `infrastructure`에 둔다. 하나의 WORK가 여러 모듈을 수정할 수 있으며 URL 접두사와 모듈 이름은 일치할 필요가 없다.

| WORK | 구현 위치 | 영역 라벨 | 담당 |
|---|---|---|---|
| 00 PostgreSQL 기반 | `shared` + 빌드·Flyway·CI 설정 | area: platform, area: shared | 초기 세팅 |
| 01 로컬 인증·refresh 회전 | `auth` | area: auth | 초기 세팅 |
| 02 온보딩·재검사·임베딩 | `profile` | area: profile | 백엔드 A |
| 03 여행 context·날짜 중복 차단 | `trip/…/context` | area: trip | 초기 세팅 |
| 04 비회원 초대·VIEW/EDIT 공유 | `trip/…/invite`, `auth` 세션, `profile/application` 온보딩 재사용 | area: trip, area: auth | 백엔드 A |
| 05 지역 품질·추첨·카드 | `attraction/…/region` 조회·계산, `trip/…/context` 추첨 요청 조율·저장 | area: attraction, area: trip | 백엔드 A |
| 06 코스 조립·폴백·제목 | `trip/…/course` | area: trip, area: attraction | 백엔드 B |
| 07 지도 편집·식당 | `trip/…/course` 편집·여행별 식당 요청 조율, `attraction/…/region` 지도·상세·후보 조회 | area: trip, area: attraction | 백엔드 B |
| 08 확정·멱등성·조회·수정 | `trip/…/course` | area: trip | 백엔드 B |
| 09 이관·품질·TourAPI·개발 RDS | `attraction/application/ingestion` (엔드포인트 없음) | area: attraction, area: platform | 백엔드 B |

WORK-04의 guest session 발급·검증은 `auth`, 초대 대상과 참여자 상태는 `trip`, 응답·성향·벡터 처리는 `profile`이다. 공유 URL이 `/courses/.../share-links`여도 링크 관리는 `trip/invite`가 맡고 일정 조회·변경은 `trip/course`가 맡는다.

WORK-05의 `/discovery/draw`는 `trip/context`에서 여행 소유권·DRAFT·참여자 상태를 읽고, profile 성향과 attraction의 적격성·추첨 계산을 이용한 뒤 여행에 결과를 반영하는 배치안이다. WORK-07의 `tripId/dayIndex/meal`을 받는 식당 API도 `trip/course`가 권한·검색 원점을 검증한 뒤 attraction에 필요한 조건을 전달한다. attraction이 trip을 역호출하는 서비스 순환을 피한다. 지역 카드·지도·관광지 상세는 attraction에서 제공한다. API URL과 응답 계약은 바꾸지 않는다.

WORK-06의 코스 초안은 DB에 저장하지 않는다. WORK-07은 전달된 초안을 재검증·재계산하고, WORK-08에서 stops·meal·taste snapshot 저장과 여행 CONFIRMED 전환을 한 트랜잭션으로 처리한다.

## 병렬 작업 전에 합의할 계약

패키지를 나눠도 공유 엔티티·Repository·migration 충돌은 남는다. 다음 계약을 해당 WORK 구현 전에 작은 PR로 정한다.

| 연결 WORK | 먼저 정할 계약 | 주의점 |
|---|---|---|
| 02 ↔ 04 | user/guest 제출 입력, 성향 조회 결과, 참여자 READY 갱신 책임 | profile이 trip 서비스를 역호출하지 않도록 trip이 조율; guest 참조·latest pointer의 FK와 소유권을 함께 검토 |
| 03 ↔ 05 ↔ 08 | 여행 상태/version, 지역·밀도 반영, 날짜 중복 검사·확정 lock | context와 course가 같은 날짜 규칙 사용; 추첨 계산 후 저장 시 상태 재검증 |
| 04 ↔ 06~08 | owner/VIEW/EDIT 판정, 링크 만료·폐기, 접근 가능한 여행 범위 | 인증 주체 주입만으로 자원 권한 검사가 끝난 것으로 보지 않음 |
| 05 ↔ 06·07 ↔ 09 | 지역 적격성·관광지 품질, 조회 DTO, 공식 코스·식당 source 계약 | 수집/조회/확정에서 품질 판정이 달라지지 않도록 규칙 공유 |
| 06 ↔ 07 ↔ 08 | draftVersion, 일정 검증, 식당 snapshot 검증, 확정 멱등성 | 클라이언트 초안을 신뢰하지 않고 서버에서 다시 검증 |

기본 순서는 `00 → (01, 09A) → 02/03 → 04 → 05 → 06 → 07 → 08`이다. 데이터가 필요한 기능은 09A의 작은 검증 seed로 먼저 통합한다. 패키지 골격이 있다는 이유로 선행 계약 없이 각 WORK를 독립 구현하지 않는다.

## 모듈별 소유 테이블

테이블 소유 모듈이 스키마·쓰기 규칙을 책임진다. 다른 모듈의 작업에서 변경이 필요하면 소유 모듈과 계약을 합의하고 해당 모듈 코드와 migration을 함께 수정한다. 신규 기능은 다른 모듈의 Repository 대신 `application` 계약으로 조회·변경을 요청한다. 기존 엔티티의 모듈 간 JPA 참조는 남아 있으므로 완전한 격리가 구현된 상태는 아니다.

아래 첫 표는 현재 Flyway에 존재하는 업무 테이블의 소유권이다. 테이블 존재가 해당 WORK의 기능 구현 완료를 뜻하지는 않는다.

| 모듈 | 소유 테이블 |
|---|---|
| `auth` | `users`, `social_accounts`, `refresh_tokens` |
| `profile` | `onboarding_submissions`, `onboarding_answers`, `liked_trips`, `user_taste_vectors`, `guest_taste_vectors`, `onboarding_responses`(전환용), `friendships`, `user_interactions`(WORK 미정) |
| `attraction` | `regions`, `region_contents`, `attractions`, `attraction_images`, `attraction_embeddings`, `official_courses`, `official_course_stops`, `ingestion_runs`, `data_quality_issues` |
| `trip` | `trip_plans`, `trip_members`, `trip_participants`, `trip_stops`, `meal_stops`, `trip_invitations`, `course_share_links` |
| `shared` | 없음 |

명세에는 있지만 현재 Flyway에 아직 없는 항목도 소유권을 지정한다.

| 모듈 | 예정 데이터 | 근거·상태 |
|---|---|---|
| `profile` | 임베딩 job | WORK-02의 재시도·상태 저장; 물리 테이블명은 해당 migration에서 확정 |
| `attraction` | `restaurants`, `restaurant_sources`, `region_food_themes` | 데이터 설계와 WORK-07·09; 아직 미생성 |
| `trip` | 확정 멱등성 key·body hash·결과 | WORK-08; 저장 구조·물리 테이블명은 해당 migration에서 확정 |

`onboarding_responses`는 WORK-02에서 신규 코드가 사용하지 않을 전환용 테이블이다. 실제 drop은 데이터 전환과 함께 별도 migration에서 결정한다. `friendships`와 `user_interactions`는 `profile`에 배치했지만 현재 WORK-00~09에 구현 작업이 없으므로 완료 범위로 세지 않는다. 데이터 설계의 논리명 `trips`·`invites`에 대응하는 현재 물리명은 `trip_plans`·`trip_invitations`다.

평면 `com.yeso.backend.domain`의 클래스·enum 18개는 모두 소유 모듈로 옮겼다. 마지막으로 남았던 `OnboardingResponse`, `Friendship`, `UserInteraction`과 관련 enum 2개도 `profile/domain`에 있으며 최상위 `domain` 패키지는 없다. 이번 이동은 패키지·import 변경이고 스키마 변경은 없다.

## 주차가 아니라 통과 게이트

| Gate | 통과 조건 |
|---|---|
| G0 계약 | docs/mvp와 프론트 JSON 승인, 외부 길찾기만 미결정 표시 |
| G1 기반 | PostgreSQL/Flyway/Testcontainers/CI/인증 통과 |
| G2 핵심 흐름 | 로컬 seed로 로그인→온보딩→추첨→코스→저장 |
| G3 협업 | 회원만 최초 일정 생성, 손님 온보딩, 기본 VIEW/선택 EDIT 링크, 참여자 성향 반영 |
| G4 데이터 | 이관 2회 멱등, 품질 리포트, 기간별 적격 지역 산출 |
| G5 외부 연동 | TourAPI 39/공공 식당/Kakao Local/Python/LLM 장애·쿼터 폴백 |
| G6 dev RDS | snapshot, migration info, 데이터 대조, 복구 리허설 |

## 브랜치와 리뷰

- 한 작업은 한 기능 브랜치/PR이며 `docs/features/` 명세와 `docs/api/` 계약을 코드 전에 추가한다.
- 같은 migration/엔티티를 둘이 동시에 고치지 않는다. 공용 스키마는 계약 PR을 먼저 병합한다.
- 에이전트 결과는 테스트 로그와 diff를 사람이 리뷰한다. 문서에 적혔다는 이유로 RDS mutation, 외부 대량 호출, 링크 권한 확대를 자동 승인하지 않는다.
- API breaking change는 프론트 예시와 migration 영향을 함께 리뷰한다.

## 데이터 배포 순서

```text
Flyway 설계 → 로컬 PostgreSQL → Testcontainers → 작은 seed
→ 데모 이관 리허설/품질 리포트 → 개발 RDS snapshot
→ Flyway migrate → 데이터 upsert → 대조/복구 시험
```

운영 RDS는 MVP 구현과 dev 리허설이 끝난 뒤 별도 작업으로 진행한다.
