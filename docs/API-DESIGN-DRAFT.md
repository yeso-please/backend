# 백엔드 API 설계 초안 (v0.1)

> demo 레포(`yeso-please/demo`) 분석 기반. 인증은 데모에 재사용할 코드가 없어 새로 설계,
> 추천 로직은 데모의 `TravelerProfileService` 코사인 유사도 방식을 임베딩 기반으로 발전시킴.

## 1. 전체 아키텍처

```
┌────────────┐      ┌──────────────────┐      ┌─────────────────────┐
│  Frontend  │─────▶│   Backend API     │─────▶│  SQLite              │
│  (SPA)     │◀─────│  (Spring Boot)    │◀─────│  users / attractions │
└────────────┘      └──────┬────────────┘      │  embedding = BLOB    │
                            │                   └─────────────────────┘
              ┌─────────────┼─────────────┐
              ▼             ▼             ▼
        ┌──────────┐  ┌──────────────┐  ┌────────────┐
        │ Kakao/    │  │ Ollama        │  │  (기존 demo │
        │ Google    │  │ (로컬 임베딩,  │  │  TourAPI    │
        │ OAuth2    │  │  bge-m3, 무료)│  │  동기화 로직 │
        └──────────┘  └──────────────┘  └────────────┘
```

- 세션 대신 **JWT(access+refresh)** — 프론트/백엔드 분리 구조이므로.
- 소셜 로그인은 **프론트가 인가코드를 받아 백엔드로 전달** → 백엔드가 토큰 교환·사용자 조회 후 자체 JWT 발급 (백엔드가 OAuth 시크릿을 관리).
- **DB: SQLite** — 별도 DB 서버 없이 파일 하나로 운영. pgvector 같은 네이티브 벡터 인덱스는 없지만, 관광지 수천 건 규모에서는 애플리케이션 레벨 브루트포스 코사인 유사도로 충분(§3 참고), 벡터는 `BLOB`(float 배열 직렬화)으로 저장.
- **임베딩 모델: Ollama로 로컬 실행** (예: `bge-m3`, 다국어/한국어 지원 양호) — OpenAI 등 외부 API는 호출당 과금 + API 키/결제 계정 필요. Ollama는 자체 서버에서 돌리는 오픈소스 모델이라 호출 비용이 없음(인프라 비용만 발생). 온보딩 응답 임베딩·관광지 임베딩 모두 같은 로컬 모델로 생성해야 같은 벡터 공간에서 비교 가능.

## 2. DB 스키마 (ERD)

```
users                        social_accounts
┌───────────────────┐        ┌───────────────────────┐
│ id            PK   │◀──┐   │ id                PK   │
│ email      UNIQUE  │   │   │ user_id           FK  ─┼──┐
│  (nullable)         │   └───┤ provider  (kakao/google)│  │
│ password_hash      │       │ provider_user_id        │  │
│  (nullable)         │       │  UNIQUE(provider,       │  │
│ nickname            │       │   provider_user_id)     │  │
│ profile_image       │       │ created_at              │  │
│ status              │       └───────────────────────┘  │
│ created_at          │                                   │
│ updated_at          │       refresh_tokens               │
└─────────┬───────────┘       ┌───────────────────────┐   │
          │ 1                 │ id                PK   │   │
          │                   │ user_id           FK  ─┼───┘
          │ 1                 │ token_hash              │
          ▼                   │ expires_at              │
┌────────────────────┐        │ revoked                 │
│ user_taste_vectors │        └───────────────────────┘
│ user_id      PK,FK │
│ embedding    BLOB   │        onboarding_responses
│ profile_text  TEXT  │        ┌───────────────────────┐
│ model_version       │        │ id                PK   │
│ updated_at          │        │ user_id           FK   │
└────────────────────┘        │ question_key            │
                               │ answer_value  (JSONB)   │
                               │ created_at              │
                               └───────────────────────┘

attractions                   attraction_embeddings
┌────────────────────┐        ┌───────────────────────┐
│ id             PK   │◀───┐  │ attraction_id   PK,FK │
│ name                │    └──┤ embedding      BLOB    │
│ category            │       │ model_version          │
│ region              │       │ updated_at             │
│ description   TEXT   │       └───────────────────────┘
│ tags        TEXT[]   │
│ lat / lng            │       user_interactions (선택, 추후)
│ source_content_id    │       ┌───────────────────────┐
│ embedding_status     │       │ id                PK   │
│  (PENDING/DONE/FAILED)│       │ user_id           FK   │
│ created_at           │       │ attraction_id     FK   │
└────────────────────┘        │ action (view/like/save)│
                               │ created_at              │
                               └───────────────────────┘
```

**설계 포인트**
- `users.password_hash`, `email` 모두 nullable → 소셜 전용 가입 허용 (데모의 실수를 미리 회피).
- `social_accounts`를 별도 테이블로 분리 → 한 계정에 카카오+구글 동시 연결(계정 통합) 가능.
- `user_taste_vectors` / `attraction_embeddings`를 users/attractions와 분리 → 임베딩 모델 교체 시 `model_version`만 바꿔 재생성, 원본 테이블 스키마 안정적으로 유지.
- `embedding`은 `BLOB`에 float 배열을 직렬화해서 저장(예: `float[]` → `ByteBuffer`). SQLite엔 벡터 인덱스가 없으므로 검색 시 서버 기동 시 `attraction_embeddings`를 메모리에 캐싱해두고 애플리케이션에서 코사인 유사도를 브루트포스로 계산(§3). 수천 건 규모면 전체 스캔도 수 ms~수십 ms 수준이라 인덱스 없이도 충분.

## 3. 추천 로직: 콘텐츠 기반 임베딩 추천

**원칙: 관광지 임베딩은 추천 요청 시점이 아니라 사전 배치로 미리 계산해둔다.** 추천 API가 호출될 때마다 Ollama로 관광지를 임베딩하면 매 요청이 느려지고 낭비이므로, 추천 시점엔 이미 계산되어 저장된 벡터끼리만 비교한다.

### 3.1 사전 임베딩 파이프라인 (관광지 → `attraction_embeddings`)

```
                    ┌─────────────────────────────────────────┐
                    │            attractions 테이블             │
                    │  (신규 등록 / TourAPI 동기화로 갱신)         │
                    └───────────────┬─────────────────────────┘
                                    │ INSERT/UPDATE 발생
                                    ▼
                    embedding_status = 'PENDING' 마킹
                                    │
                    ┌───────────────┴─────────────────────────┐
                    │  임베딩 배치 잡 (스케줄러, 예: 5분마다)      │
                    │  PENDING 또는 model_version 불일치 행 조회  │
                    └───────────────┬─────────────────────────┘
                                    ▼
                    텍스트 합성: name+category+tags
                    (description 있으면 이어붙임, 없으면 폴백)
                                    ▼
                    Ollama 임베딩 모델 호출 (순차 또는 소규모 동시성)
                                    ▼
                    attraction_embeddings UPSERT
                    (embedding BLOB, model_version, updated_at)
                                    ▼
                    attractions.embedding_status = 'DONE'
                                    │
                    ┌───────────────┴─────────────────────────┐
                    │  추천 서버 기동 시 / 배치 완료 후          │
                    │  전체 attraction_embeddings 를 메모리에    │
                    │  캐싱 (in-memory float[] 배열)             │
                    └───────────────────────────────────────────┘
```

- `attractions`에 `embedding_status` 컬럼(`PENDING` / `DONE` / `FAILED`) 추가 — 어떤 행이 아직 임베딩 안 됐는지 추적. 신규 관광지 등록·description 갱신 시 자동으로 `PENDING`으로 리셋.
- **description 없음은 예외가 아니라 상시 케이스로 취급**: 데모 실측(2026-08-13) 기준 전체 6,769건 중 description 보유는 1,046건(15.5%)뿐이고, TourAPI 원본 자체에 설명이 없는 곳도 있어 상세 백필 배치가 끝나도 영구히 비어있는 항목이 남는다. 이 임베딩 배치는 TourAPI 상세 백필(`tour.api.backfill`, 관광지 설명 자체를 채우는 별개 배치)과 독립적으로 돌되, 상세 백필이 나중에 description을 채워 넣으면(`detailFetched` 갱신) 해당 관광지의 `embedding_status`를 `PENDING`으로 리셋해 재임베딩 트리거.
- **최초 백필**: 서비스 오픈 전에 기존 관광지 전체(데모 기준 6,769건)를 한 번에 돌리는 1회성 배치가 필요 — 데모의 `TourSyncService`/`AttractionDetailBackfillService` 뒤에 이 임베딩 배치를 이어 붙이는 구조가 자연스러움.
- **증분 처리**: 이후 TourAPI 동기화로 신규/변경 관광지가 생기면 스케줄러(데모의 `SchedulingConfig` 패턴 재사용)가 주기적으로 `PENDING` 건만 집어 처리 — 매번 전체 재계산 안 함.
- 관광지 설명(description)이 희소하다는 데모의 데이터 캐비닛(15.5%만 채워짐)을 감안 — description 없는 항목은 name+category+tags만으로 임베딩 생성하는 폴백 필요(아래 텍스트 합성 규칙 참고).
- Ollama 로컬 모델은 처리량이 외부 API보다 낮을 수 있어 배치 크기/동시성을 조절 가능하게 설계(예: 초당 N건 제한) — 관리자 트리거 API(§4)로 진행 상황 확인 가능해야 함.

#### 텍스트 합성 규칙 (임베딩 입력 텍스트를 만드는 방법)

**원칙: 어떤 관광지든 절대 빈 텍스트/의미 없는 텍스트가 되면 안 된다.** 구조화된 필드(카테고리·지역·태그)를 항상 기본 뼈대로 쓰고, `description`은 있을 때만 덧붙이는 계층 구조.

```
1순위(필수)  name + category + region        → 항상 존재하는 필드
2순위(태그)  DB tags 컬럼
             + ExperienceTags 키워드 매칭으로 name/description에서 추론한 태그
             (데모 로직 재사용, 중복 제거, 최대 5개)
3순위(선택)  description (있으면 덧붙임, 없으면 생략)
```

**조합 템플릿** (키워드 나열이 아니라 자연어 문장형 — 문장형 텍스트가 임베딩 모델 성능이 더 좋음):

```
"{name}은(는) {region}에 위치한 {category}이다. 특징: {tag1, tag2, ...}. {description}"
```

- `description`이 없으면 마지막 문장을 생략: `"{name}은(는) {region}에 위치한 {category}이다. 특징: {tags}."`
- `tags`가 하나도 안 잡히는 극단적 희소 케이스면 태그 문장도 생략: `"{name}은(는) {region}에 위치한 {category}이다."` (이 최소 뼈대는 name/category/region이 필수 컬럼이라 항상 채워짐 — 완전 빈 텍스트는 구조적으로 발생 불가).
- `description`은 500자 초과 시 앞부분만 사용(TourAPI 설명은 보통 앞부분에 핵심 정보가 몰려 있고, 모델 입력 길이·임베딩 비용 절약).
- 전처리: HTML 태그 제거, 연속 공백/개행 정규화, TourAPI 특유의 노이즈 문자열(예: "※", 반복되는 안내 문구) 제거.
- **온보딩 프로필 텍스트도 같은 자연어 문장형 스타일**로 맞춤(§3.2) — 구조를 똑같이 맞출 필요는 없지만, "짧은 키워드 나열" vs "자연어 문장"처럼 스타일 자체가 갈리면 같은 임베딩 공간에서 비교했을 때 편향이 생길 수 있어 스타일은 통일.
- **템플릿 버전 관리**: 이 합성 규칙 자체를 바꾸면(필드 추가/순서 변경 등) 기존에 저장된 임베딩이 새 텍스트와 안 맞게 됨 → `model_version`과 별개로 `template_version`을 두거나, 규칙 변경 시 전체 `attractions.embedding_status`를 `PENDING`으로 리셋해 재배치.

### 3.2 추천 요청 시점 흐름

```
[온보딩 응답]                     [attraction_embeddings]
 태그 선택 + 자유서술 텍스트          (이미 사전 계산되어 저장됨)
        │                                  │
        ▼                                  │
  프로필 텍스트 합성                        │
  "바다·한적함·로컬맛집 선호..."             │
        │                                  │
        ▼                                  │
  Ollama 임베딩 모델 호출 (온보딩 시 1회)     │
        │                                  │
        ▼                                  ▼
  user_taste_vectors.embedding (BLOB)  ──▶  코사인 유사도 계산
                                            (애플리케이션 레벨, 인메모리 전수 비교)
                                                  │
                                                  ▼
                                       top-N 필터링 (지역/카테고리) → 추천 응답
```

- 데모의 `TravelerProfileService`와 동일한 아이디어(사용자 벡터 vs 콘텐츠 벡터의 코사인 유사도)이지만, **손수 만든 12차원 태그 벡터 대신 실제 임베딩 모델**을 사용해 표현력을 확장.
- 최소 유사도 임계값 + region/category 필터는 데모의 `MIN_SIMILARITY`/`RELATIVE_BAND` 패턴을 그대로 채택할 가치 있음 (완전 무관한 추천 방지).
- 사용자 벡터 업데이트: 온보딩 시 1차 생성 → 이후 `user_interactions`(좋아요/저장) 발생 시 방문 콘텐츠 벡터를 만족도 가중 평균해 재계산 (데모의 "여행 DNA" 가중 평균 방식 재사용). 이 값은 관광지와 달리 실시간 계산이라 호출량이 적어(유저 액션당 1회) 사전 배치가 필요 없음.
- 추천 API는 `attraction_embeddings`에 `embedding_status='DONE'`인 행만 후보로 사용 — 아직 임베딩 안 된 신규 관광지는 배치가 돌 때까지 추천 후보에서 자동 제외.

## 4. API 엔드포인트

### 인증
| Method | Path | 설명 |
|---|---|---|
| POST | `/api/auth/signup` | 이메일/비밀번호 회원가입 |
| POST | `/api/auth/login` | 이메일/비밀번호 로그인 → access+refresh 토큰 |
| POST | `/api/auth/social/{provider}` | provider=kakao\|google, body `{ code }` → 토큰 교환 후 신규/기존 유저 판별, JWT 발급 |
| POST | `/api/auth/refresh` | refresh 토큰으로 access 토큰 재발급 |
| POST | `/api/auth/logout` | refresh 토큰 폐기 |
| GET  | `/api/users/me` | 내 정보 조회 |

### 온보딩 / 취향
**인증 선택(Optional Auth)** — `Authorization` 헤더가 있으면 로그인 사용자로, 없으면 게스트로 처리(FEATURE-SPEC §v1 구현 범위 참고).

| Method | Path | 설명 |
|---|---|---|
| GET  | `/api/onboarding/questions` | 온보딩 질문 목록 |
| POST | `/api/onboarding/responses` | 응답 제출. 로그인 시 `onboarding_responses`/`user_taste_vectors`에 저장, 게스트는 저장 없이 `{ tasteVector }`(base64, opaque)를 응답으로 돌려줌 — 클라이언트가 localStorage에 들고 있다가 아래 `tasteVector` 파라미터로 재사용 |
| GET  | `/api/users/me/taste` | 내 취향 벡터 메타(요약 태그 등, 원본 벡터는 비노출) — 로그인 필수 |

### 추천 · 지역 추첨 · 코스 생성 공통: `tasteVector` 파라미터
아래 엔드포인트들은 모두 **인증 선택**이다. `Authorization` 헤더가 있으면 서버가 user_id로 저장된 취향 벡터를 찾아 쓰고,
없으면 요청 바디의 `tasteVector`(게스트가 온보딩 응답으로 받은 값)를 그대로 사용한다. 요청에 취향 벡터가 아예 없으면(헤더도 없고
`tasteVector`도 없으면) `FULL_RANDOM`/취향 미반영 동작으로 폴백한다(FEATURE-SPEC §3 예외 규칙).

### 추천
| Method | Path | 설명 |
|---|---|---|
| GET  | `/api/recommend?limit=10&region=` | 콘텐츠 기반 추천 (코사인 유사도), `tasteVector` 선택 파라미터 |
| POST | `/api/recommend/feedback` | `{ attractionId, action }` — 좋아요/저장 시 취향 벡터 갱신 큐잉. **로그인 필수**(게스트는 벡터를 서버에 안 두므로 갱신 대상이 없음 — 세션 대신 클라이언트가 들고 있는 모델이라 즉시 갱신 큐잉이 성립 안 함) |

### 관리자 — 관광지 임베딩 배치 (§3.1)
| Method | Path | 설명 |
|---|---|---|
| POST | `/api/admin/attractions/embeddings/backfill` | `PENDING`/버전 불일치 관광지 전체 임베딩 배치 트리거 (최초 백필용) |
| GET  | `/api/admin/attractions/embeddings/status` | 진행 상황 조회: 전체/PENDING/DONE/FAILED 건수 |
| POST | `/api/admin/attractions/{id}/embedding` | 특정 관광지 임베딩 단건 재생성 |

## 5. 전체 사용자 플로우 (지역 추첨 → 코스 슬롯 → 확정)

```
1. 로그인/회원가입 (§1)
        │
        ▼
2. 취향 온보딩 (§3.2) — 자연 선호도, MBTI류 성향 등 1회 저장
        │
        ▼
3. [추천] 버튼 → 지역 추첨 (2단계 슬롯머신: 도 → 시/군)
        │   완전랜덤 모드 / 취향반영랜덤 모드 중 선택
        │   + 이번 여행의 "상황"(동행유형/기간/예산 등, §5.1) 매 요청마다 별도 입력
        ▼
4. [코스] 버튼 → 코스 슬롯 초안 생성 (관광지 N곳, 이동수단·거주지 기반 동선 정렬)
        │
        ├─▶ 슬롯별로 좋아요(잠금) / 별로(리롤 대상) 표시
        │        │
        │        ▼
        │   잠긴 슬롯은 유지, 나머지 슬롯만 재추첨 ── 반복 가능
        │        │
        └────────┘
        │  (개수 조정·수동 추가/삭제도 이 단계에서)
        ▼
5. 코스 확정 저장
        │
        ├──▶ 6. 캘린더에 날짜 지정 (중복 방지, §5.3 — 방식 미확정)
        │
        └──▶ 7. 친구 초대(링크) → 같이 코스 보기
```

### 5.1 "사용자 상황(situation)"은 취향과 분리된 별도 입력

온보딩에서 저장하는 취향(§3.2)은 "이 사람이 대체로 뭘 좋아하는가"이고, **상황은 이번 한 번의 여행에만 적용되는 휘발성 컨텍스트**라 저장된 프로필과 분리한다.

| 구분 | 저장 위치 | 예시 |
|---|---|---|
| 취향(taste) | `user_taste_vectors` (영구) | 자연 선호, MBTI형 성향, 좋아하는 태그 |
| 상황(situation) | 요청 파라미터 (휘발성, 저장 안 함) | 동행유형(혼자/커플/가족/친구), 기간(반나절/1박2일), 이동수단, 거주지(출발지) |

지역 추첨과 코스 생성 API는 둘 다 `situation` 객체를 바디로 받는다. 상황이 취향 임베딩 자체를 바꾸진 않고, **후보 필터링/가중치**로만 작용(예: "가족" 동행이면 체험형 태그 가중, "차량 없음"이면 도보 동선 가능한 반경으로 후보 제한).

### 5.2 완전랜덤 vs 취향반영랜덤

지역 추첨(§5의 3번)에 두 모드를 둔다:
- `FULL_RANDOM`: 250개 시군구 중 균등 확률로 추첨 (데모의 기존 방식, 취향 무시)
- `PREFERENCE_WEIGHTED`: 지역별 "취향 적합도"(그 지역 관광지들과 사용자 벡터의 평균/최대 유사도)를 가중치로 삼아 추첨 — 완전 결정론적 top-1이 아니라 **가중 확률 추첨**이라 매번 같은 지역이 나오진 않음(발견의 재미 유지 + 취향 반영 균형).

사용자가 매 추첨 시 모드를 고를 수도 있고, 프로필에 기본값을 저장해도 됨(추가 결정 필요, §9).

### 5.3 코스 슬롯: 잠금/리롤은 상태를 서버에 저장하지 않는 stateless 설계

- 코스 "초안"은 DB에 저장하지 않고 **클라이언트가 현재 슬롯 상태(잠금 여부 포함)를 매 요청에 담아 보내는 방식**으로 설계 — 서버 세션/드래프트 테이블 불필요, 새로고침해도 클라이언트가 들고 있는 상태만 날아감(확정 전까지는 가벼운 데이터).
- 리롤 시 서버는 요청에 포함된 `locked=true` 슬롯은 그대로 두고, `locked=false` 슬롯만 새 후보로 교체. 이미 보여줬던 관광지 ID들(요청에 포함된 전체 stops)은 자동으로 제외 후보에 들어가 중복 재추천 방지.
- "확정"(`POST /api/courses`) 시점에만 실제 `trip_plans`/`trip_stops`에 영속화.

## 6. DB 스키마 추가분 (코스/친구초대)

```
trip_plans                    trip_stops
┌────────────────────┐        ┌───────────────────────┐
│ id             PK   │◀───┐  │ id                PK   │
│ owner_user_id  FK   │    └──┤ trip_plan_id      FK   │
│ region_id      FK   │       │ attraction_id     FK   │
│ transport (walk/car/│       │ order_index             │
│  public)             │       │ source (RECOMMEND/     │
│ origin_lat/lng       │       │  MANUAL)                │
│ situation      JSON  │       └───────────────────────┘
│ status (DRAFT/       │
│  CONFIRMED)          │       trip_members
│ start_date            │       ┌───────────────────────┐
│ end_date              │       │ id                PK   │
│ created_at            │       │ trip_plan_id      FK   │
└────────────────────┘        │ user_id           FK   │
                               │ role (OWNER/MEMBER)     │
friendships                   │ joined_at                │
┌────────────────────┐        └───────────────────────┘
│ id             PK   │
│ user_id        FK   │        trip_invitations
│ friend_id      FK   │        ┌───────────────────────┐
│ status (PENDING/    │        │ id                PK   │
│  ACCEPTED)           │        │ trip_plan_id      FK   │
│ created_at            │       │ invite_token   UNIQUE  │
└────────────────────┘        │ invited_by_user_id FK  │
                               │ expires_at              │
                               │ created_at              │
                               └───────────────────────┘
```

- `trip_plans.status='DRAFT'`는 §5.3의 stateless 초안과는 별개로, "확정은 했지만 날짜는 아직 안 정함" 같은 중간 상태를 위해 남겨둠(초안 자체를 서버에 저장하고 싶어지면 이 상태를 씀 — 지금은 선택 사항).
- `trip_stops.order_index`가 곧 동선 순서. 이동수단·거주지(origin) 기반 정렬은 저장 시점에 1회 계산해서 순서를 고정(데모의 "경로는 저장 시점 1회 계산" 원칙과 동일 — 매번 재계산하면 카카오모빌리티 API 한도를 태움, §7 남은 결정 참고).
- `friendships`는 단방향 row 2개로 양방향 친구 관계 표현(요청자→대상, 수락 시 대상→요청자도 생성) 또는 status 컬럼으로 요청/수락 상태만 추적 — 세부 방식은 구현 시 결정.

## 7. API 엔드포인트 추가분

### 지역 추첨 (인증 선택)
| Method | Path | 설명 |
|---|---|---|
| POST | `/api/discovery/draw` | `{ mode: FULL_RANDOM\|PREFERENCE_WEIGHTED, situation, tasteVector? }` → `{ regionId, province, city }`. `tasteVector`는 게스트일 때만 사용(§4) |

### 코스 슬롯 (stateless, §5.3, 인증 선택)
| Method | Path | 설명 |
|---|---|---|
| POST | `/api/courses/draft` | `{ regionId, transport, origin, situation, stopCount, tasteVector? }` → 슬롯 초안 생성 |
| POST | `/api/courses/draft/reroll` | `{ regionId, transport, origin, situation, stops:[{attractionId, locked}], tasteVector? }` → 잠긴 슬롯 유지, 나머지만 재추천 |
| PATCH | `/api/courses/draft/stops` | 개수 변경/수동 추가·삭제 |
| POST | `/api/courses` | **인증 필수.** 초안 확정 저장 → `trip_plans`/`trip_stops` 생성. 게스트가 만든 슬롯도 로그인 직후 이 API에 그대로 실어 보내면 저장됨(별도 마이그레이션 엔드포인트 없음, FEATURE-SPEC §5.1) |
| GET  | `/api/courses/{id}` | 확정된 코스 조회 |

### 캘린더
| Method | Path | 설명 |
|---|---|---|
| POST | `/api/courses/{id}/schedule` | `{ startDate, endDate }` → 날짜 지정, 기존 확정 코스와 겹치면 경고/차단(방식 미확정, §9) |

### 친구 · 초대
| Method | Path | 설명 |
|---|---|---|
| POST | `/api/friends/requests` | `{ targetUserId }` 친구 요청 |
| POST | `/api/friends/requests/{id}/accept` | 친구 요청 수락 |
| POST | `/api/courses/{id}/invitations` | 초대 링크 발급 → `{ inviteUrl }` |
| POST | `/api/invitations/{token}/accept` | 초대 수락 → `trip_members`에 추가 |

## 8. 데모 레포에서 참고할 것

- 데모 PRD §8이 이미 `TripPlan/TripMember/TripInvitation/TripDay/TripItem/RouteSnapshot` 등 유사한 엔티티 체크리스트를 제안해둠 — 이번 §6 스키마와 이름이 겹치는 부분은 의도적으로 맞춤(향후 데모 코드를 참고하기 쉽도록).
- 캘린더 중복 방지 관련해선 데모에 참고할 기존 구현이 없음 — 완전히 새로 설계.
- "완전랜덤 vs 취향반영랜덤"은 데모의 기존 지역 추첨 로직(균등 확률, 인기도 가중 없음 — PRD §2.2 제품 원칙)에서 `PREFERENCE_WEIGHTED` 모드만 새로 추가하는 셈.

## 9. 남은 결정 사항 (확인 필요)
1. 카카오 외 구글도 필수인지, 애플 로그인 필요 여부
2. Spring Boot 유지 여부 (데모와 스택 통일 시 이점 있음, JDBC 드라이버만 SQLite용으로 교체)
3. Ollama를 어디서 구동할지 (로컬 개발 PC vs 배포 서버) — 배포 서버 스펙에 따라 `bge-m3`(파라미터 큼, 정확도↑) 대신 더 가벼운 모델(`nomic-embed-text` 등)로 낮출 수도 있음
4. **캘린더 연동 방식**: (a) 자체 내부 캘린더(trip_plans.start_date/end_date만으로 겹침 검사) vs (b) 구글 캘린더 실제 연동(추가 OAuth 스코프, 이벤트 생성 API 호출 필요) — 사용자도 아직 고민 중이라고 밝힘, 우선 (a)로 MVP 가고 (b)는 후속으로 미루는 걸 제안
5. **완전랜덤/취향반영랜덤 기본값**: 사용자가 매번 선택하게 할지, 프로필에 기본 모드를 저장해서 원클릭으로 갈지
6. **이동수단·거주지(origin) 기반 동선 계산**: 카카오모빌리티 API(데모에서 이미 사용 중, 하루 300회 한도)로 실제 경로 최적화를 할지, 아니면 단순 거리 기반 근사 정렬로 갈지 — 한도 고려 시 저장(확정) 시점 1회만 호출하는 데모 원칙을 따르는 게 안전
7. **친구 초대 링크의 인증 요구 수준**: 링크만 있으면 누구나 들어올 수 있게 할지, 초대받은 사람도 가입/로그인 필수로 할지
