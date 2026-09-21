# 온보딩·재검사·임베딩 job

- 상태: done (guest 참여자 제출은 WORK-04 이후로 미룸)
- 담당 범위: onboarding
- 작성일: 2026-09-21
- 갱신일: 2026-09-21
- 관련 이슈: #12 (WORK-02)
- 관련 API: [Onboarding API](../api/onboarding.md)

## 목표

로그인한 사용자가 여행 MBTI 12문항·일정 밀도·경험/제외 태그·좋았던 여행지를 제출하면 결정적으로 MBTI 코드와
임베딩 입력 텍스트(profileText)를 만들고, Python 임베딩 서비스를 트랜잭션 밖에서 비동기로 호출해 취향
벡터(user_taste_vectors)를 준비한다. 임베딩이 실패해도 온보딩 자체는 항상 완료된다.

계약 원문: [`docs/mvp/onboarding-questionnaire.md`](../mvp/onboarding-questionnaire.md),
[`docs/mvp/implementation-workpack.md`](../mvp/implementation-workpack.md) WORK-02.

## 범위

### 포함

- `GET /api/onboarding/questions`: 12문항·선택지·글자 매핑·태그 사전을 공개 API로 제공(인증 불필요)
- `POST /api/onboarding/submissions`: 로그인 사용자의 제출 — 검증, MBTI 채점, profileText 합성, 저장,
  임베딩 job 등록
- `GET /api/onboarding/me`: 로그인 사용자의 최신 제출 결과 + 온보딩 완료 여부
- 제출 immutable + 재검사 시 최신 pointer만 교체(`users.latest_onboarding_submission_id`)
- 임베딩 adapter(`EmbeddingClient`) 뒤에 실제 HTTP 연동(`HttpEmbeddingClient`)을 두고, 성공/일시 장애(재시도)/
  영구 실패/dimension 불일치를 구분해 job 상태(`PENDING/READY/FAILED`)로 흡수
- 제출 트랜잭션 커밋 후에만 임베딩을 호출(`AFTER_COMMIT`)하고, 재시도는 `@Scheduled` sweeper가 처리

### 제외 (후속)

- guest(초대 손님) 온보딩 제출 — `trip_participants` 기반 guest 세션이 있어야 하며 WORK-04(초대) 이후 구현
- `guest_taste_vectors` 반영 — 위와 동일한 이유로 후속. `EmbeddingJob.ownerType=GUEST` 스키마는 미리 마련해뒀다
- 온보딩 결과를 활용하는 지역 추첨/코스 생성(WORK-05/06)

## 사용자 흐름

1. 클라이언트가 `GET /api/onboarding/questions`로 12문항과 태그 사전을 받는다(인증 불필요).
2. 로그인한 사용자가 `POST /api/onboarding/submissions`로 답·일정 밀도·태그·좋았던 여행지를 제출한다.
3. 서버는 즉시 MBTI 코드와 profileText를 계산해 201과 함께 반환한다 — 이 시점에 임베딩 호출까지 이미
   끝나 있어 응답의 `tasteStatus`가 최종 상태(READY/PENDING/FAILED)를 반영한다.
4. `GET /api/onboarding/me`로 언제든 최신 제출과 온보딩 완료 여부를 조회한다.
5. 재검사하면 새 submission이 만들어지고 `latest_onboarding_submission_id`만 바뀐다 — 이전 제출은 그대로 남는다.

## 완료 기준

- [x] 12문항 모두 정확히 한 번씩 답하지 않으면 400 `MISSING_QUESTION_ANSWER`/`DUPLICATE_QUESTION_ANSWER`를 반환한다.
- [x] choice가 1/2가 아니면 400 `INVALID_CHOICE`를 반환한다.
- [x] 동점 축은 뒤 글자(I/N/F/P)로 결정되고, 최종 코드는 EI+SN+TF+JP 순서다.
- [x] scheduleDensity가 RELAXED/PACKED가 아니면 400 `INVALID_SCHEDULE_DENSITY`를 반환한다.
- [x] 경험 태그가 5개를 넘거나 사전에 없으면 각각 `TOO_MANY_EXPERIENCE_TAGS`/`UNKNOWN_TAG`를 반환한다.
- [x] 좋았던 여행지가 30개를 넘거나(`TOO_MANY_LIKED_REGIONS`), 같은 지역이 중복되거나(`DUPLICATE_LIKED_REGION`),
      존재하지 않는 지역이면(`REGION_NOT_FOUND`, 400) 거부한다.
- [x] questionVersion이 현재 서버 상수와 다르면 400 `INVALID_QUESTION_VERSION`을 반환한다.
- [x] 제출은 immutable이며, 재검사는 새 submission을 만들고 `latest_onboarding_submission_id`만 바꾼다.
- [x] profileText는 MBTI→일정 밀도→정렬된 경험 태그→제외 태그→SIG_CD 오름차순 liked trip 순서로 결정적으로 만들어진다.
- [x] 임베딩 성공/일시 장애(timeout·429·5xx)/영구 실패(4xx)/dimension 불일치를 구분해 job이
      READY/PENDING(재시도 예약)/FAILED로 정확히 갱신되고, 어느 경우든 submission 자체는 항상 성공한다.
- [x] 자유서술(note)은 응답에는 포함되지만 서버 로그에는 남기지 않는다(auth 패키지와 동일하게 onboarding 코드
      전체에 로그 호출이 없다).
- [x] `GET /api/onboarding/questions`는 인증 없이 호출할 수 있다.
- [x] 성공·실패·재검사·임베딩 성공/실패 통합 테스트가 있다(`OnboardingIntegrationTest`), MBTI 채점과 profileText
      합성 단위 테스트가 있다(`MbtiScorerTest`, `OnboardingProfileTextComposerTest`).
- [x] API 문서가 구현과 일치한다.

## 구현 메모

- Entity: `onboarding.domain.OnboardingSubmission`, `OnboardingAnswer`, `LikedTrip`, `EmbeddingJob`;
  `auth.domain.User.latestOnboardingSubmissionId` 추가
- Repository: `onboarding.infrastructure.OnboardingSubmissionRepository`, `OnboardingAnswerRepository`,
  `LikedTripRepository`, `EmbeddingJobRepository`, `RegionRepository`(region bounded context가 생기기 전
  임시 위치), `UserTasteVectorRepository`
- Service: `onboarding.application.OnboardingService`(검증·채점·저장), `OnboardingEmbeddingRunner`(임베딩
  호출과 job/submission 상태 갱신, `@Transactional(REQUIRES_NEW)`), `OnboardingSubmittedEventListener`
  (`@TransactionalEventListener(AFTER_COMMIT)`으로 위 runner를 외부 bean 호출), `EmbeddingRetrySweeper`
  (`@Scheduled`로 기한이 지난 PENDING job 재시도)
- Controller: `onboarding.presentation.OnboardingController`
- DTO: `OnboardingQuestionsResponse`, `AnswerRequest`, `LikedTripRequest`, `OnboardingSubmissionRequest`,
  `OnboardingSubmissionResponse`, `OnboardingMeResponse`
- 도메인 로직: `OnboardingQuestionBank`(고정 질문·태그 상수), `MbtiScorer`(동점 처리 포함 채점),
  `OnboardingProfileTextComposer`(결정적 profileText 합성)
- 예외: `onboarding.domain.OnboardingException`(베이스) → `InvalidQuestionVersionException`,
  `MissingQuestionAnswerException`, `DuplicateQuestionAnswerException`, `InvalidChoiceException`,
  `InvalidScheduleDensityException`, `TooManyExperienceTagsException`, `UnknownTagException`,
  `DuplicateLikedRegionException`, `TooManyLikedRegionsException`, `OnboardingRegionNotFoundException`(400)
- 인프라: `EmbeddingClient`(adapter 인터페이스) → `HttpEmbeddingClient`(실제 연동), `EmbeddingProperties`
  (`embedding.*`), `Utf8MaxByteSize`류와 마찬가지로 검증은 `presentation.validation` 대신 서비스 계층에서
  도메인 예외로 처리(스케줄 밀도·태그처럼 표준 Bean Validation로 표현하기 어려운 규칙이라)
- 마이그레이션: `V2__onboarding_submissions.sql` — `onboarding_submissions`에 `mbti_code`(rename),
  `status/taste_status/completed_at/schedule_density/experience_tags/exclude_tags` 추가, `liked_trips`에
  `note/tags` 추가 및 `display_name` 제거 + `(submission_id, region_id)` unique, `users`/`trip_participants`에
  `latest_onboarding_submission_id` 추가, `embedding_jobs` 신설, 전환용 `onboarding_responses` 제거

## 결정과 미해결 사항

- **`onboarding_responses` 제거**: WORK-00 baseline 주석이 "WORK-02가 이 테이블을 제거한다"고 명시해, 사용하지
  않는 `OnboardingResponse` 엔티티와 테이블을 이번 migration에서 함께 삭제했다.
- **liked trip의 `display_name` 제거**: 원 스키마는 자유 라벨(`display_name NOT NULL`)을 요구했지만, 실제
  계약(`docs/mvp/onboarding-questionnaire.md`)은 `sigCd`+선택적 `note`+경험 태그 부분집합만 정의한다. 라벨
  없이도 지역명은 `regions` 테이블에서 조회할 수 있어 중복 데이터를 만들지 않기로 했다.
- **제출 응답을 두 단계로 분리**: `submit()`이 만드는 응답 DTO를 그 자리에서 바로 반환하면 `tasteStatus`가
  임베딩 처리 전(PENDING) 스냅샷이 된다 — 트랜잭션 커밋+`AFTER_COMMIT` 임베딩 처리가 서비스 메서드 반환
  "직전"에 동기로 끝나므로, controller는 `submit()`(id만 반환) 이후 `getSubmissionResponse(id)`로 다시 읽어
  최종 상태를 응답한다.
- **`@TransactionalEventListener`를 별도 bean으로 분리**: 같은 클래스 안에서 리스너 메서드가
  `this.runJob(...)`을 직접 호출하면 Spring AOP 프록시를 거치지 않아 `@Transactional(REQUIRES_NEW)`가
  조용히 무시된다(자기 호출 한계) — `OnboardingSubmittedEventListener`가 `OnboardingEmbeddingRunner`를 외부
  bean으로 주입받아 호출하게 분리했다.
- **`UserTasteVector`에 `Persistable` 추가**: `@MapsId`로 PK가 미리 채워진 엔티티는 Spring Data JPA가 항상
  "기존 row"로 오판해 `save()`가 `merge()`를 시도하다 lazy 참조 때문에 `AssertionFailure: null identifier`로
  실패했다 — `isNew()`를 명시해 새 row는 `persist()`, 기존 row는 `merge()`가 되도록 고쳤다.
- **`submission.status`(SUBMITTED 하나뿐)**: 작업서가 별도 컬럼으로 요구했지만 현재는 원자적으로 완성된
  제출만 저장하므로 실질적인 분기가 없다 — 향후 상태가 늘어날 여지를 위해 컬럼과 enum만 마련해뒀다.
- **미해결**: guest 온보딩(WORK-04 이후), `EmbeddingRetrySweeper`의 재시도 간격/횟수 조정은 실제 Python
  서비스 SLA가 정해지면 다시 튜닝이 필요하다.

## 변경 이력

| 날짜 | 변경 | 이유 |
|---|---|---|
| 2026-09-21 | 온보딩 질문·제출·재검사·임베딩 job 최초 구현 | WORK-02(이슈 #12) |
