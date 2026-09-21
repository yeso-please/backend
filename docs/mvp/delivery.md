# 초기 세팅과 3인 팀 실행 순서

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
| 백엔드 B | WORK-06, 07, 08, 09 공동 | 관광지·여행 schema | 밀도별 코스·지도 ADD·TourAPI 우선 식당·권한·데이터 리포트 포함 |

WORK-02와 WORK-04는 온보딩 application service 계약을 먼저 작은 PR로 합의한다. WORK-03과 WORK-08은 overlap 및 멱등성 repository를 함께 설계한다. migration 번호는 작업 시작 전에 예약해 충돌을 막는다.

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
