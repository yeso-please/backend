# MVP 에이전트 구현 작업서

각 작업은 독립 PR이다. 에이전트에게 아래 공통 프롬프트와 해당 작업 블록만 전달한다. 한 PR에서 다른 작업까지 확장하지 않는다.

## 공통 프롬프트

```text
먼저 docs/mvp/README.md, decisions.md, flow.md, api.md,
data-and-recommendation.md와 docs/conventions/을 읽어라.
docs/mvp가 구형 FEATURE-SPEC/API-DESIGN-DRAFT/BUTTON-SPEC보다 우선한다.
지정된 WORK 항목만 구현하라. 시작 전에 현재 코드와 계약의 차이,
필요한 Flyway migration, 수정할 파일과 테스트 목록을 보고하라.
코드+migration+통합 테스트+docs/features+docs/api를 같은 PR에 넣어라.
외부 API는 adapter 뒤에 두고 테스트에서는 stub하라. 비밀값을 커밋하거나 로그에 남기지 마라.
완료 시 실행한 명령/결과, 실패 경로, DB 변경, 남은 위험을 보고하라.
```

## WORK-00 PostgreSQL 기반

범위: PostgreSQL Compose, 환경별 설정, Flyway V1, `ddl-auto=validate`, Testcontainers, CI, 공통 오류와 인증 사용자 주입.

필수 스키마: users/refresh, onboarding history, regions/contents, attractions/images/embeddings, official courses, trips/participants/stops/meals, invites/share links, ingestion/quality. 기존 엔티티와 migration을 맞춘다.

완료 기준: 새 clone에서 DB 기동→migration→테스트가 한 명령으로 통과한다. SQLite/Ollama 직접 설정을 제거하거나 legacy profile로 격리한다. 운영 RDS는 변경하지 않는다.

## WORK-01 로컬 인증

범위: signup/login/refresh/logout/me, BCrypt, JWT access, HttpOnly refresh cookie, token hash·rotation·revocation.

테스트: 이메일 중복, 동일 인증 오류, 만료, 폐기 token 재사용, refresh rotation 경쟁, cookie 속성.

## WORK-02 온보딩·프로필 재검사

범위: 데모 12문항/채점/태그/제외 조건/좋았던 여행지의 버전된 API, immutable submission, 최신 포인터, 프로필 재검사, 임베딩 작업 상태.

데모 참고: `TravelMbtiService`, `ExperienceTags`, `TravelPreferenceService`, `VisitOnboardingController`. 비로그인 세션 저장은 복사하지 않는다. 손님 온보딩은 WORK-04가 같은 application service를 호출할 수 있게 한다.

테스트: 12문항 누락/중복, 태그 5개, 여행지 30개, 재검사 이력, Python timeout에도 완료 유지.

## WORK-03 여행 맥락·중복 경고

범위: 시작일+nights 검증, endDate 계산, 0~6박, 확정 여행 overlap 조회. 차단하지 않고 경고를 반환하며 확정 시 acknowledgement를 요구한다.

테스트: 경계 날짜 하루 겹침, 완전 포함, 취소 여행 제외, acknowledgement false 409/true 저장, 동시 멱등성.

## WORK-04 초대 손님·공유 권한

범위: invite/share token hash, VIEW/EDIT, 만료/폐기, 손님 표시 이름·온보딩·READY, 참여자 성향 조회, 공개 상세 접근.

프론트 카카오톡 공유가 사용할 URL만 백엔드가 발급한다. 카카오 메시지 API 호출은 구현하지 않는다.

테스트: 원문 token DB/로그 미노출, VIEW 수정 403, EDIT 범위, 만료 410, 여행 간 token 재사용 거부.

## WORK-05 지역·추첨·카드

범위: 250 지역 목록, 여행 일수별 품질 게이트, FULL_RANDOM, 체크형 CONDITIONAL, 동일 지역 재등장 허용, 승인 지역 콘텐츠 카드.

테스트: 완전 랜덤 균등성, 각 조건 on/off, 위치/사용자/동행 벡터 없음, 조건 0개 400, 카드 미승인/이미지 실패/후보 부족 422.

## WORK-06 관광지 후보·코스 조립

범위: Spring 후보 점수, 참여자 평균, 공식 코스/규칙 폴백, 날짜별 4곳 상한, 단순 체류 슬롯, 식사 슬롯, 이동 근사, LLM 제목 adapter.

테스트: 0~6박, 당일 12~15시, 휴무·중복·식사 침범, Python/LLM 장애, 공식 코스 매핑 부족, 후보 부족.

## WORK-07 편집·식당 검색

범위: 유형별 대체 관광지, 교체/삭제/순서 재검증, Kakao Local FD6 프록시, 직전 관광지 기준 검색, 식당 스냅샷 추가/교체/제거.

대표 메뉴는 자동 추론하지 않는다. REST key는 서버 비밀 설정만 사용한다.

테스트: 현재 코스 제외, 다른 지역 거부, 기준점 폴백, Kakao timeout/429, 메뉴 null, EDIT token 권한.

## WORK-08 확정·조회

범위: 전체 초안 재검증, Idempotency-Key, overlap acknowledgement, 날짜별 stop/meal 저장, owner/share 조회·수정.

테스트: 같은 key 같은 body 동일 결과, 다른 body 409, 두 동시 확정, tampered attraction/region/date 거부, 권한 매트릭스.

## WORK-09 데이터 이관·보강

범위: 데모 비개인정보 추출/upsert/검증 리포트, 개발 RDS 적용, TourAPI 상세 보강.

반드시 `.agents/skills/tourapi-detail-backfill/SKILL.md`와 `.agents/skills/flyway-rds-sync/SKILL.md`를 사용한다. 첫 실행은 로컬 PostgreSQL, 다음은 개발 RDS다. 운영 RDS는 별도 승인 없이는 대상이 아니다.

## PR 완료 템플릿

```text
Contract: 참조한 docs/mvp 항목
Implemented: 코드/API/migration
Tests: 실행 명령과 결과
Data: 생성·변경 테이블/행 및 롤백 방법
External failures: 검증한 timeout/limit/fallback
Docs: 갱신한 docs/features와 docs/api
Remaining: 계약 미충족 또는 후속 사항
```
