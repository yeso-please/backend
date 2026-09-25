# 추천·데이터 설계

데이터 이관·품질 판정·추천 점수·코스 배치를 **어떻게 계산하는지** 적는다. 제품 정책은 [product.md](../product.md), 밖에서 보이는 API 동작은 [`api/`](../api/README.md)가 기준이다. 추천은 요청한 사람의 취향 기준이고 확정 단계는 없다.

## 1. PostgreSQL 데이터 경계

### 핵심 테이블

| 집합 | 주요 필드/제약 |
|---|---|
| `users`, `refresh_tokens` | email unique, password hash, token hash·expiry·revoked |
| `onboarding_submissions`, `onboarding_answers`, `liked_trips` | 회원 소유, question version, immutable history, latest pointer |
| `regions`, `region_contents` | `SIG_CD`, 중심 좌표, 소개/근거/모델/승인 상태, hero image/source |
| `attractions`, `attraction_images`, `attraction_embeddings` | source+content ID unique, description/source/fetchedAt, image validation, model/template/dimension |
| `official_courses`, `official_course_stops` | TourAPI 공식 코스 원문과 같은 지역의 순서 있는 장소 |
| `trip_plans`, `trip_participants` | 생성자, 날짜, transport, 지역 선택 방식·밀도, 코스 제목·추천 모드·취향 기준 회원·최초 생성 시각 |
| `course_items` | 코스의 날짜별 순서 목록. 관광지와 식사를 한 목록에 두고 `kind`로 구분, 체류·이동 분(참고용) |
| `trip_invitations`, `course_share_links` | token hash, expires/revoked (권한 없음, 공유는 읽기 전용) |
| `course_meal_restaurants` | 식사 항목에 고른 식당 스냅샷(provider/외부 id, 표시 정보, 근거·출처), 식사 한 자리에 하나 |
| `restaurants`, `restaurant_sources` | TourAPI 39·농가맛집·모범/향토음식점·착한가격업소의 통합 식당과 원천 근거 |
| `region_food_themes` | SIG_CD별 음식·특산물, 근거 출처, DRAFT/APPROVED/REJECTED |
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
- 관광지 벡터는 사전 배치, 회원 벡터는 설문 완료 시 생성한다. 변경된 텍스트는 `PENDING`으로 되돌린다.
- Spring은 지역/품질 필터 후 코사인 유사도와 규칙 점수를 계산한다. 초기 후보 규모에서는 애플리케이션 계산, 측정 후 필요할 때 pgvector로 옮긴다.
- 관광지 점수는 **요청한 사람** 한 명의 취향 벡터로 구한다. 참여자 취향을 평균하지 않는다. 제외 조건은 판정 가능한 것(`물놀이` 분류)만 적용한다(2026-09-25). `야간 이동`은 코스가 시각을 다루지 않아 저장만 한다. 제외 조건도 요청자의 것을 적용한다.
- Python 장애 때 새 임베딩을 요청 시마다 재시도하지 않는다. 저장된 호환 벡터가 없으면 공식 코스→비개인화 규칙 순으로 폴백한다.

## 6. 지역 추첨 점수

API 계약은 [3-7 지역 정하기](../api/trip.md#3-7-지역-정하기)이며, 이 절은 그 식을 풀어 쓴 것이다. 둘이 다르면 API가 기준이다.

`RANDOM`은 요청 일수·밀도에 적격인 지역에 동일 확률을 준다. `CONDITIONAL`은 사용자가 체크한 조건(`DISTANCE`, `MY_TASTE`)의 점수를 기본값 1에 더한다. 각 점수는 0~1이다.

```text
weight = 1
       + checked(DISTANCE) × distanceScore      # 1 - clamp(출발지~지역 중심 km / 300, 0, 1)
       + checked(MY_TASTE) × requesterTasteScore # 요청자 벡터와 지역 추천 가능 관광지 코사인 상위 min(5,N)개 평균 c → (c + 1) / 2
```

참여자 취향은 쓰지 않는다(§5). 점수를 그대로 최대값 선택하지 않고 0보다 큰 가중치로 확률 추첨한다. 출발지나 요청자의 호환 벡터가 없으면 해당 조건을 `ignoredConditions`로 반환하고, 전부 무시되면 균등 추첨과 `ALL_CONDITIONS_IGNORED` 경고를 준다. 지도에서 직접 고른 지역(`MANUAL`)은 점수 없이 적격 여부만 검사한다. 기간별 적격 기준은 추천 가능 관광지 `days × density.maxPlacesPerDay + min(days,3)`과 승인 지역 카드다. RELAXED의 상한은 4, PACKED는 6이다.

## 7. 코스 조립

```text
품질·지역 필터 → 요청자 취향 유사도 → 유형 다양성 후보 풀
→ 날짜별 몇 곳 배치(밀도 목표)
→ 가까운 순으로 순서 정하기
→ 식사(점심·저녁) 위치 삽입
→ 규칙 제목(LLM은 선택) → 코스 반환
```

- 코스는 시각 없는 날짜별 순서 목록이다(2026-09-25). 가용 시간·식사 시각·feasibility 검사는 없다.
- RELAXED는 하루 4곳, PACKED는 6곳이 자동 생성의 목표다(수동 편집은 개수 제한 없음). 지역 후보가 모자라 목표보다 적게 배치되면 `DENSITY_TARGET_NOT_MET`을 반환한다. 하루 최소 1곳을 못 채우는 날이 있을 때만 `COURSE_INSUFFICIENT_CANDIDATES`(422)다.
- 모든 날에 점심·저녁을 하나씩 넣는다. 점심은 그날 관광지의 대략 절반 뒤, 저녁은 맨 끝이다(정확한 위치 규칙은 #49). 식사는 사용자가 순서를 옮길 수 있지만 삭제·추가는 할 수 없다.
- 체류시간은 포토/전망 등 60분, 일반 관광지 90분, 대형 문화시설·레포츠 120분의 카테고리 기본값이며 식사는 60분이다. 모두 참고용 `estimated`다.
- 이동은 Haversine 거리와 보수 계수로 1차 추정한 참고용 분이다. 식사 항목은 이동을 계산하지 않고, 식사 뒤 관광지는 식사 앞 마지막 관광지에서 잰다. 외부 길찾기 실패 정책 확정 전에는 항상 근사 경고를 붙인다. 편집(추가·교체·삭제·이동)마다 이동시간을 다시 계산한다.
- 목적 함수는 `취향 적합 + 유형 다양성 - 이동시간`이다. 날짜 범위, 중복은 감점이 아니라 불가능 조건이다.
- 공식 코스 폴백은 같은 지역이며 현재 기간에 유효하고 추천 가능 관광지로 매핑되는 stop만 쓴다. 매핑이 부족하면 규칙 코스로 넘어간다.

## 8. 지도 편집·지역 음식·식당·제목

- 대체·추가 후보는 같은 지역, 추천 가능, 현재 초안에 없음이 필수다. UI 분류는 `자연`, `역사·문화`, `체험·레포츠`, `산책·휴식`, `기타`다. 지도 bbox API는 핀용 이름·썸네일·좌표를 가볍게 반환하고 상세는 별도 조회한다.
- 식당 1순위는 TourAPI `contentTypeId=39`다(MVP). 지역 농산물과 향토성을 명시한 농촌진흥청 농가맛집, 지자체 모범/향토음식점, 행정안전부 착한가격업소를 출처별 adapter로 보완한다(추가 기능, MVP에서는 빈 섹션). 추천이 모두 비면 프론트가 카카오 Local 검색(5-6)으로 넘어간다. 일반음식점 인허가는 추천 근거가 아니라 영업상태 보조검증에만 쓴다.
- 같은 식당의 전화번호 완전 일치 → 정규화 주소+상호 → 50m 이내 좌표+상호 순으로 병합하고, 애매하면 자동 병합하지 않는다. 모든 원천과 갱신일을 보존한다.
- 카카오 Local `FD6`는 그날 순서에서 식사 앞 마지막 관광지 좌표(없으면 지역 중심), 기본 반경 5km, 거리순 fallback이다. API 결과는 맛·대표성·대표 메뉴를 보장하지 않으므로 그런 문구를 만들지 않는다.
- 지역 음식·특산물은 `region_food_themes`에 근거 문장과 URL을 저장하고 사람 승인 후 공개한다. 농산물 유통 품목 하나만으로 대표 특산물을 확정하지 않는다.
- 선택 식당은 일정 재현을 위해 외부 응답의 표시 필드와 확인된 메뉴·근거 label을 스냅샷 저장한다. 카카오 장소를 자체 맛집 master로 승격하지 않는다.
- MVP 제목은 규칙 제목이다. LLM 제목은 설정 플래그로 켤 때만 쓴다. LLM 제목 입력에는 지역명, 일정 일수, 실제 장소명, 검증 태그만 넣는다. 40자 이내 제목 1개를 생성하고 금칙어/없는 장소를 검사한다. 실패하면 규칙 제목을 즉시 사용한다.

## 9. 관측·테스트

- 여행 일수별 적격 지역 수와 제외 사유, 지역별 추천 가능 관광지 수
- 설명/이미지/좌표 각각과 교집합 보유율, `SOURCE_EMPTY`/실패/재시도 건수
- 모델·템플릿 버전별 벡터 상태와 차원 불일치
- 개인화/공식 코스/규칙 폴백 비율, 후보 부족 422 비율
- 0~6박, 당일치기, RELAXED/PACKED, 식사 경계, 날짜 중복 차단, 읽기 전용 공유, 동시 편집(version) 통합 테스트
- TourAPI·Kakao Local·Python·LLM 장애와 호출량 제한 테스트

## 아직 열려 있는 한 가지

외부 길찾기를 도입할지와 실패 시 처리는 MVP 이후 결정이다. 결정 전 구현은 `estimated=true`와 경고를 유지하며 실제 도로 시간이라고 주장하지 않는다.

## 외부 계약 참고

- [Kakao Talk Share JavaScript](https://developers.kakao.com/docs/ko/kakaotalk-share/js-link): 사용자가 친구나 채팅방을 선택해 링크를 공유한다.
- [Kakao Local REST API](https://developers.kakao.com/docs/en/local/dev-guide): 음식점 카테고리 검색과 장소 표시 필드.
- [공공데이터포털](https://www.data.go.kr/): TourAPI 키 발급·활용 신청과 원천 명세 확인.
