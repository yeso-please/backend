# 데이터 이관·추천·코스 조립 설계

## 1. PostgreSQL 데이터 경계

### 핵심 테이블

| 집합 | 주요 필드/제약 |
|---|---|
| `users`, `refresh_tokens` | email unique, password hash, token hash·expiry·revoked |
| `onboarding_submissions`, `onboarding_answers`, `liked_trips` | owner user/guest participant, question version, immutable history, latest pointer |
| `regions`, `region_contents` | `SIG_CD`, 중심 좌표, 소개/근거/모델/승인 상태, hero image/source |
| `attractions`, `attraction_images`, `attraction_embeddings` | source+content ID unique, description/source/fetchedAt, image validation, model/template/dimension |
| `official_courses`, `official_course_stops` | TourAPI 공식 코스 원문과 같은 지역의 순서 있는 장소 |
| `trips`, `trip_participants`, `trip_stops` | owner, 날짜, transport, participant taste snapshot, visit date/order/type |
| `invites`, `course_share_links` | token hash, permission, expires/revoked, guest status |
| `meal_stops` | 외부 provider/id와 식당 표시 정보 스냅샷, 대표 메뉴 nullable |
| `data_quality_issues`, `ingestion_runs` | 누락 종류, 대상, 실행/재시도/쿼터/요약 |

이미지는 URL만 있다고 유효하지 않다. `validation_status`, `validated_at`, `source_url`, `license_note`를 둔다. 지역 소개는 `DRAFT/APPROVED/REJECTED` 상태와 reviewer를 둔다. 외부 원문은 원천과 수집 시각을 보존한다.

## 2. 데모 데이터 이관

1. 데모 서버를 중지하고 H2 파일 복사본과 SHA-256을 만든다.
2. 개인정보인 계정·후기·일정은 제외하고 지역, 관광지, 확보된 상세·이미지·좌표, 공식 코스를 CSV/JSONL로 추출한다.
3. Flyway로 대상 스키마를 먼저 만든 뒤 staging에 적재한다. `source_system + source_content_id`로 upsert한다.
4. 유효 지역, 좌표 범위, 설명 공백, 이미지 검증, 원천 ID 중복을 검사하고 실패 행은 격리한다.
5. 지역별 전체/설명/이미지/좌표/추천 가능 교집합과 공식 코스 수를 리포트한다.
6. 로컬 PostgreSQL에서 2회 실행 결과가 동일한 것을 확인한 뒤 개발 RDS에 같은 migration과 이관 배치를 적용한다.
7. 운영 RDS 반영은 별도 승인·스냅샷·복구 리허설 이후다.

데모 실측 수치는 시점에 따라 달라진다. 문서에 고정된 총계를 신뢰하지 않고 이관 실행별 `ingestion_runs`에 기록한다.

## 3. TourAPI 상세 보강

- 개발자마다 개인 키를 비공개 환경 변수 `TOUR_API_KEY`로 설정한다. 키를 공유 문서·로그·명령 인자·Git에 넣지 않는다.
- 설명/이미지/좌표가 없는 콘텐츠 ID만 대상으로 공통 상세와 필요한 유형별 소개를 호출한다. 이미 성공한 필드는 덮어쓰지 않는다.
- 실행은 재시작 가능해야 하며 cursor, 성공/없음/재시도/실패, 일일 호출 수를 DB에 기록한다. 429/쿼터 초과는 즉시 중단하고 다음 실행에서 이어간다.
- 원천에 설명이나 이미지가 없으면 `SOURCE_EMPTY`로 기록한다. 생성형 문장으로 원천 상세를 채우지 않는다.
- 수집 후 이미지 HEAD/GET 검증과 품질 리포트를 다시 실행한다. 추천 가능 상태는 검증 결과로 파생하며 수동 boolean으로 조작하지 않는다.
- 반복 절차는 저장소 스킬 `.agents/skills/tourapi-detail-backfill/SKILL.md`를 사용한다.

## 4. 지역 소개 콘텐츠

지역 소개 입력은 지역명·행정구역, 검증된 역사/특성 사실, 대표 관광지 이름과 상세, 출처다. LLM은 이 범위를 벗어난 사실을 만들지 않고 2~4문단을 작성한다.

```text
근거 수집 → 소개 초안(DRAFT) → 출처 대조 → 사람 승인(APPROVED)
→ 대표 이미지 검증 → 추첨 가능 판정
```

출력에는 제목, 소개 본문, 역사/특성 태그, hero image, source IDs, 모델·prompt version을 저장한다. 승인 콘텐츠나 대표 이미지가 없으면 지역을 추천하지 않고 `data_quality_issues`에 남긴다.

## 5. 임베딩과 취향 집계

- Python 서비스는 온보딩/관광지 텍스트를 같은 모델·버전·차원으로 임베딩한다. RDS 업무 테이블을 직접 쓰지 않고 벡터를 Spring에 반환한다.
- 관광지 벡터는 사전 배치, 사용자/손님 벡터는 온보딩 완료 시 생성한다. 변경된 텍스트는 `PENDING`으로 되돌린다.
- Spring은 지역/품질 필터 후 코사인 유사도와 규칙 점수를 계산한다. 초기 후보 규모에서는 애플리케이션 계산, 측정 후 필요할 때 pgvector로 옮긴다.
- 소유자와 READY 참여자별 관광지 점수를 먼저 구한 뒤 산술 평균한다. 결측 사용자는 평균 모수에서 제외한다. 제외 조건은 누구 한 명이라도 선택하면 hard filter로 적용한다.
- Python 장애 때 새 임베딩을 요청 시마다 재시도하지 않는다. 저장된 호환 벡터가 없으면 공식 코스→비개인화 규칙 순으로 폴백한다.

## 6. 지역 추첨 점수

`FULL_RANDOM`은 요청 일수에 적격인 지역에 동일 확률을 준다. `CONDITIONAL`은 사용자가 체크한 항목만 정규화해 합한다.

```text
weight = base(1)
       + checked(distance) × distanceScore
       + checked(myTaste) × ownerTasteScore
       + checked(companionTaste) × meanCompanionScore
```

점수를 그대로 최대값 선택하지 않고 0보다 큰 가중치로 확률 추첨한다. 위치/호환 벡터/READY 동행자가 없으면 해당 항목을 `ignoredConditions`로 반환한다. 기간별 적격 기준은 추천 가능 관광지 `days × 4 + min(days,3)`과 승인 지역 카드다.

## 7. 코스 조립

```text
품질·지역 필터 → 개인별 유사도 평균 → 유형 다양성 후보 풀
→ 날짜별 가용 시간(첫날 12:00, 마지막 날 15:00 기본)
→ 점심/저녁 슬롯 선점 → 가까운 후보 순열 생성
→ 단순 체류 슬롯+이동 추정으로 feasibility 검사
→ 상위 일정 선택 → 감성 제목 생성 → 초안 반환
```

- 하루 최대 4곳이며 슬롯을 채우기 위해 제약을 깨지 않는다.
- 체류시간은 포토/전망 등 60분, 일반 관광지 90분, 대형 문화시설·레포츠 120분의 카테고리 기본값이다. 모두 `estimated`다.
- 점심은 12:00~13:00, 저녁은 18:00~19:00 기본이다. 사용자가 바꾼 값으로 전체 일정을 재검사한다.
- 이동은 Haversine 거리와 보수 계수로 1차 추정한다. 외부 길찾기 실패 정책 확정 전에는 항상 근사 경고를 붙인다.
- 목적 함수는 `취향 적합 + 유형 다양성 - 이동시간 - 과밀`이다. 날짜 범위, 중복, 식사 침범, 알려진 휴무는 감점이 아니라 불가능 조건이다.
- 공식 코스 폴백은 같은 지역이며 현재 기간에 유효하고 추천 가능 관광지로 매핑되는 stop만 쓴다. 매핑이 부족하면 규칙 코스로 넘어간다.

## 8. 편집·식당·제목

- 대체 후보는 같은 지역, 추천 가능, 현재 초안에 없음이 필수다. UI 분류는 `자연`, `역사·문화`, `체험·레포츠`, `산책·휴식`, `기타`다.
- 식당 검색은 카카오 Local `FD6`, 직전 관광지 좌표, 기본 반경 5km, 거리순이다. API 결과는 맛·대표성·대표 메뉴를 보장하지 않으므로 그런 문구를 만들지 않는다.
- 선택 식당은 일정 재현을 위해 외부 응답의 표시 필드를 스냅샷 저장한다. 외부 식당 master를 추천 데이터로 취급하지 않는다.
- LLM 제목 입력에는 지역명, 일정 일수, 실제 장소명, 검증 태그만 넣는다. 40자 이내 제목 1개를 생성하고 금칙어/없는 장소를 검사한다. 실패하면 규칙 제목을 즉시 사용한다.

## 9. 관측·테스트

- 여행 일수별 적격 지역 수와 제외 사유, 지역별 추천 가능 관광지 수
- 설명/이미지/좌표 각각과 교집합 보유율, `SOURCE_EMPTY`/실패/재시도 건수
- 모델·템플릿 버전별 벡터 상태와 차원 불일치
- 개인화/공식 코스/규칙 폴백 비율, 후보 부족 422 비율
- 0~6박, 당일치기, 식사 경계, 중복 없음, SHARE VIEW/EDIT, 날짜 경고 확인, 동시 확정 통합 테스트
- TourAPI·Kakao Local·Python·LLM 장애와 호출량 제한 테스트

## 아직 열려 있는 한 가지

외부 길찾기 성공/실패 때 확정을 막을지 근사값으로 저장할지는 후속 결정이다. 결정 전 구현은 `estimated=true`와 경고를 유지하며 실제 도로 시간이라고 주장하지 않는다.

## 외부 계약 참고

- [Kakao Talk Share JavaScript](https://developers.kakao.com/docs/ko/kakaotalk-share/js-link): 사용자가 친구나 채팅방을 선택해 링크를 공유한다.
- [Kakao Local REST API](https://developers.kakao.com/docs/en/local/dev-guide): 음식점 카테고리 검색과 장소 표시 필드.
- [공공데이터포털](https://www.data.go.kr/): TourAPI 키 발급·활용 신청과 원천 명세 확인.
