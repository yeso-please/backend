# 7. 지역·관광지

- 계약 상태: agreed
- 모듈: `attraction/region`
- 정책 소스: [지역·관광지 품질](../product.md#지역관광지-품질), [지역 소개 콘텐츠](../design/recommendation.md#4-지역-소개-콘텐츠)

**호출 주체** — 7-1~7-4 모두 회원. 특정 여행에 대한 권한은 확인하지 않는다. 공유 링크 소지자(share session)는 호출할 수 없다. 비회원 뷰어는 [4-13](trip.md#4-13-공유-코스-조회) 응답만으로 코스 화면을 그린다(2026-09-25).

**추천 가능 관광지** — 유효한 지역·좌표, 공백이 아닌 상세 설명, 검증된(`VALID`) 이미지 1장 이상, 차단성 품질 이슈 없음을 모두 만족하는 장소. 차단성 품질 이슈는 `data_quality_issues`에서 심각도 `ERROR`이고 열린(`OPEN`) 이슈다. `WARNING`이나 해결된 이슈는 막지 않는다. 쇼핑·숙박·음식점과 캠핑장(분류 `AC05`)은 코스 후보가 아니라서 빠진다. 이 판정은 수집 결과에서 파생하며 사람이 수동으로 켜지 않는다. 소개문은 가장 최근에 승인된 한 건을 본다.

**추첨 가능 지역** — 지역 소개문과 대표 이미지가 없어도 추천 후보가 될 수 있다. 요청한 일정에 맞는 추천 가능 관광지 `days × 밀도 상한 + min(days, 3)`개 이상(RELAXED 상한 4, PACKED 6)이 필요하다. 승인된 소개문은 카드에 표시하며, 콘텐츠가 없으면 지역명 기반의 빈 콘텐츠 카드를 돌려준다. 적격 지역이 없는 기간은 3-2 `eligibleRegionCount`로 미리 경고한다.

**관광지 유형 `category`**

| 값 | 표시 |
|---|---|
| `NATURE` | 자연 |
| `HISTORY_CULTURE` | 역사·문화 |
| `ACTIVITY` | 체험·레포츠 |
| `WALK_REST` | 산책·휴식 |
| `ETC` | 기타 |

**원천 분류 → `category`·기본 체류시간** — TourAPI 분류코드(대분류 `cat1`, 중분류 `cat2`)로 정한다. 데이터 보강 때 이 코드를 함께 수집한다. 5장 코스와 이 장이 같은 표를 쓴다.

| TourAPI 분류 | `category` | 기본 체류 |
|---|---|---:|
| 자연(`A01`) | `NATURE` | 90분 |
| 역사관광지(`A0201`) | `HISTORY_CULTURE` | 90분 |
| 건축·조형물(`A0205`) | `HISTORY_CULTURE` | 60분 |
| 문화시설(`A0206`, contentType 14) | `HISTORY_CULTURE` | 120분 |
| 휴양관광지(`A0202`) | `WALK_REST` | 90분 |
| 체험관광지(`A0203`) | `ACTIVITY` | 90분 |
| 레포츠(`A03`, contentType 28) | `ACTIVITY` | 120분 |
| 산업관광지(`A0204`), 분류 없음 | `ETC` | 90분 |
| 쇼핑(contentType 38), 숙박(contentType 32) | 코스 후보가 아니다 | — |
| 숙박 > 캠핑(새 분류체계 `lclsSystm2 = AC05`: 일반야영장·오토캠핑장·카라반·글램핑장) | 콘텐츠 유형이 레포츠(28)여도 코스 후보가 아니다 (2026-10-09) | — |
| 음식점(contentType 39) | 관광지가 아니라 식당 원천 | — |

- 제외 조건 `물놀이`는 해수욕장·계곡(자연 소분류)과 수상 레포츠(`A0302`)를 뜻한다.
- TourAPI가 신규 분류 체계로 바뀌면 같은 의미의 코드로 대응한다.
- 새 분류체계 코드는 `attractions.lcls_systm1~3`(V19)에 둔다. 값이 비어 있는 관광지는 콘텐츠 유형 판정만 적용한다.
- 분류코드를 수집하기 전(#50)에는 콘텐츠 타입으로 근사한다: 문화시설(14) → `HISTORY_CULTURE` 120분, 레포츠(28) → `ACTIVITY` 120분, 나머지 → `ETC` 90분.

**오류 코드**

| code | HTTP | 상황 |
|---|---:|---|
| `REGION_INVALID_DAYS` | 400 | `days`가 1~7 밖 |
| `ATTRACTION_INVALID_BOUNDS` | 400 | `bbox` 형식 오류, 최소가 최대보다 큼, 한국 범위 밖 |
| `REGION_NOT_FOUND` | 404 | 없는 `SIG_CD` |
| `ATTRACTION_NOT_FOUND` | 404 | 없는 관광지 |

---

### 7-1. 지역 목록

> `호출: 회원` · `✅ 구현`

```
GET /api/regions?days=3&scheduleDensity=RELAXED
GET /api/regions
```

`days` 없이 부르면 추첨 가능 여부를 계산하지 않고 지역 목록만 준다. 여행이 없는 온보딩(2-2)의 좋았던 여행지 선택 화면이 이 형태를 쓴다.

| Query | 필수 | 설명 |
|---|---|---|
| `days` | 아니오 | 1~7. 여행 일수. 생략하면 추첨 가능 여부를 계산하지 않는다 |
| `scheduleDensity` | 아니오 | `RELAXED` \| `PACKED`. `days`가 있을 때만 쓴다. 생략하면 `RELAXED`다. 프론트는 여행의 밀도(없으면 `/onboarding/me`의 밀도)를 보낸다. 다른 값이면 `400 COMMON_INVALID_REQUEST` |

**Response `200 OK`** — 전국 지도에 그릴 250개 지역 전체

```json
{
  "days": 3,
  "scheduleDensity": "RELAXED",
  "eligibleCount": 187,
  "regions": [
    {"sigCd": "47130", "province": "경상북도", "city": "경주시", "centerLat": 35.856, "centerLng": 129.225, "drawEligible": true, "ineligibleReasons": []},
    {"sigCd": "47940", "province": "경상북도", "city": "울릉군", "centerLat": 37.484, "centerLng": 130.905, "drawEligible": false, "ineligibleReasons": ["INSUFFICIENT_ATTRACTIONS"]}
  ]
}
```

| `ineligibleReasons[]` | 뜻 |
|---|---|
| `INSUFFICIENT_ATTRACTIONS` | 이 일수·밀도에 필요한 추천 가능 관광지 부족 |

추첨 불가 지역도 지도에는 표시한다. 인기·별점 순위는 제공하지 않는다.

`days`를 생략하면 응답 모양은 같고 `days`·`scheduleDensity`·`eligibleCount`와 각 지역의 `drawEligible`·`ineligibleReasons`가 `null`이다(필드를 빼지 않는다).

| 오류 | HTTP | code |
|---|---:|---|
| `days`를 보냈는데 1~7 밖 | 400 | `REGION_INVALID_DAYS` |
| `scheduleDensity`가 `RELAXED`·`PACKED`가 아님 | 400 | `COMMON_INVALID_REQUEST` |

---

### 7-2. 지역 카드

> `호출: 회원` · `✅ 구현`

```
GET /api/regions/{sigCd}/card
```

**Response `200 OK`**

```json
{
  "sigCd": "47130",
  "province": "경상북도",
  "city": "경주시",
  "title": "천년의 시간이 머무는 도시",
  "tagline": "왕릉 사이 고요한 길을 걷는 곳",
  "tags": ["역사", "야경", "산책"],
  "introduction": ["첫 문단…", "둘째 문단…"],
  "heroImage": {"url": "https://…", "sourceName": "한국관광공사", "sourceUrl": "https://…", "license": "공공누리 1유형", "attractionId": null},
  "characteristics": ["역사 유적", "야경"],
  "historyHighlights": ["신라의 수도"],
  "landmarks": [{"attractionId": 5012, "name": "대릉원", "thumbnailUrl": "https://…"}],
  "sources": [{"name": "한국관광공사 TourAPI", "url": "https://…"}],
  "updatedAt": "2026-09-30T00:00:00"
}
```

**구현 메모(2026-09-25 기술 결정)** — 이 필드들은 attraction 트랙(#40·#51)이 추가하는 새 migration으로 저장한다. `region_contents`에 `characteristics`(jsonb 문자열 배열), `landmarks`(jsonb `{attractionId, order}` 배열), `sources`(jsonb `{title, url}` 배열, 응답의 `name`은 `title`), `hero_image_source_name`을 더한다. `introduction`은 `TEXT` 그대로 두고 API가 빈 줄(`\n\n`)로 나눠 문단 배열로 준다. `historyHighlights`는 기존 `history_tags`(쉼표로 구분한 문자열, 예: `신라의 수도, 불교 문화`), `heroImage.sourceUrl`·`license`는 기존 `hero_image_source_url`·`hero_image_license_note`다.

| 필드 | 설명 |
|---|---|
| `introduction` | 소개 콘텐츠가 있을 때 문단 배열. 콘텐츠가 없으면 빈 배열 |
| `landmarks` | 승인된 소개문이 근거로 쓴 대표 관광지 중 추천 가능한 것 최대 3개, 소개문에 나온 순서. 나중에 추천 불가가 된 곳은 빠진다 |
| `characteristics`, `historyHighlights` | 승인된 지역 소개 콘텐츠의 태그. 없으면 빈 배열 |
| `tagline` | 지도에서 지역을 눌렀을 때 보이는 한 줄 소개(30자 이내). **사람이 승인한 것만**. 없으면 `null` ([한 줄 소개와 태그](#한-줄-소개와-태그-89)) |
| `tags` | [태그 사전](#태그-사전)의 값 0~4개, 승인된 것만. 없으면 빈 배열. 화면은 `#`을 붙여 그린다 |
| `heroImage` | ① 승인된 지역 소개의 검증된 이미지 → ② 없으면 **지역 대표 관광지의 검증된(`VALID`) 첫 이미지** → ③ 둘 다 없으면 `null` |
| `heroImage.attractionId` | ②로 채웠을 때 그 관광지 ID(화면에서 관광지 상세로 이을 수 있다). ①이면 `null` |
| `heroImage.license` | 원천이 밝힌 이용 조건. 없으면 `null` |

승인 콘텐츠가 없는 지역에는 도시명을 제목으로 쓰고, 소개·특징·관광지·출처는 빈 배열, `updatedAt`은 `null`로 응답한다. 임시 문구·허구 소개를 만들지 않는다. `tagline`·`tags`는 지역 소개(`region_contents`)와 따로 승인하므로 소개가 없어도 나올 수 있다.

**대표 관광지**(②와 한 줄 소개 생성의 근거) — 그 지역의 추천 가능 관광지 중 **TourAPI 추천코스(관광공사)에 지점으로 자주 나오는 순**, 같으면 상세 설명이 긴 순, 그다음 ID 순. 대표 사진은 원천 이미지라 생성물이 아니므로 승인 없이 쓴다(2026-10-10). AI Hub 방문 빈도는 쓰지 않는다(이용 조건상 학습·평가용).

| 오류 | HTTP | code |
|---|---:|---|
| 없는 지역 | 404 | `REGION_NOT_FOUND` |

---

### 7-3. 지도 관광지 핀

> `호출: 회원` · `✅ 구현`

```
GET /api/regions/{sigCd}/attractions?bbox=129.15,35.78,129.30,35.90&category=NATURE&cursor=&limit=100
```

| Query | 필수 | 설명 |
|---|---|---|
| `bbox` | 아니오 | `minLng,minLat,maxLng,maxLat`. 생략하면 지역 전체 |
| `category` | 아니오 | 유형 필터 |
| `cursor` | 아니오 | 이전 응답의 `nextCursor` |
| `limit` | 아니오 | 기본 100, 최대 200 |

**Response `200 OK`** — 핀에 필요한 가벼운 필드만

```json
{
  "items": [
    {"attractionId": 5012, "name": "대릉원", "thumbnailUrl": "https://…", "category": "HISTORY_CULTURE", "lat": 35.838, "lng": 129.211, "recommendable": true}
  ],
  "nextCursor": null
}
```

- 추천 불가 장소도 항상 포함한다(`recommendable: false`). 프론트는 핀을 흐리게 그리고 "코스에 추가"를 비활성화하며, 서버도 5-3에서 거부한다. 좌표가 없는 장소는 핀을 그릴 수 없으므로 뺀다.
- 쇼핑·숙박·음식점은 코스 후보가 아니라서 핀에 넣지 않는다(2026-09-27). 캠핑장(분류 `AC05`)도 같다(2026-10-09). 식당은 5-5·5-6으로 고른다.
- 정렬은 `attractionId` 오름차순이며, `nextCursor`는 그다음 페이지를 가리키는 불투명 문자열이다. 마지막 페이지면 `null`이다.
- `bbox`는 위도 33~39, 경도 124~132 안이어야 한다.

| 오류 | HTTP | code |
|---|---:|---|
| bbox 오류 | 400 | `ATTRACTION_INVALID_BOUNDS` |
| `category`가 목록 밖, `limit`가 1~200 밖, `cursor` 위조 | 400 | `COMMON_INVALID_REQUEST` |
| 없는 지역 | 404 | `REGION_NOT_FOUND` |

---

### 7-4. 관광지 상세

> `호출: 회원` · `✅ 구현`

```
GET /api/attractions/{attractionId}
```

**Response `200 OK`**

```json
{
  "attractionId": 5012,
  "regionSigCd": "47130",
  "name": "대릉원",
  "category": "HISTORY_CULTURE",
  "address": "경북 경주시 황남동 …",
  "lat": 35.838, "lng": 129.211,
  "description": "…",
  "images": [{"url": "https://…", "sourceName": "한국관광공사", "license": null}],
  "useTime": "09:00~22:00",
  "restDate": "연중무휴",
  "estimatedDurationMinutes": 90,
  "estimated": true,
  "recommendable": true,
  "notRecommendableReasons": [],
  "sources": [{"name": "한국관광공사 TourAPI", "contentId": "126207", "fetchedAt": "2026-09-30T00:00:00"}],
  "oneLine": "왕릉 사이로 난 고요한 길을 걷는 곳",
  "tags": ["역사", "산책"],
  "summaryBasis": "SOURCE_SUMMARY"
}
```

| 필드 | 설명 |
|---|---|
| `description` | 원천 상세 설명. 안전하게 정제한 텍스트. 생성형 문장으로 채우지 않는다 |
| `images` | 검증된(`VALID`) 이미지만. `sourceName`은 원천이 TourAPI면 `한국관광공사` |
| `useTime`, `restDate` | 원천 문자열 그대로. 없으면 `null` |
| `estimatedDurationMinutes` | 유형별 기본 체류시간(60·90·120분). 실측이 아니다 |
| `notRecommendableReasons` | `MISSING_DESCRIPTION` \| `MISSING_IMAGE` \| `MISSING_COORDINATE` \| `QUALITY_ISSUE` |
| `oneLine` | 카드용 한 줄 소개(50자 이내). **사람이 승인한 것만**. 없으면 `null`. `description`(원천)과 다른 필드다 |
| `tags` | [태그 사전](#태그-사전)의 값 0~4개, 승인된 것만. 없으면 빈 배열 |
| `summaryBasis` | `SOURCE_SUMMARY`(원천 설명을 요약) \| `NAME_CATEGORY`(설명이 없어 이름·분류만으로 쓴 문장 — 화면에 "AI가 이름·분류로 만든 소개"를 표시한다) \| `oneLine`이 없으면 `null` |

| 오류 | HTTP | code |
|---|---:|---|
| 없는 관광지, 또는 쇼핑·숙박·음식점·캠핑장(코스 후보가 아닌 장소) | 404 | `ATTRACTION_NOT_FOUND` |

---

### 한 줄 소개와 태그 (#89)

지역 카드(7-2)의 `tagline`·`tags`와 관광지 상세(7-4)의 `oneLine`·`tags`·`summaryBasis`는 **LLM 초안 → 사람 승인**으로 만든다(2026-10-10).

- **저장**: `attraction_summaries`(관광지당 1행), `region_summaries`(지역당 1행). 원천 설명(`attractions.description`)과 지역 소개(`region_contents`)를 덮어쓰지 않는다. 상태는 `DRAFT` → `APPROVED` \| `REJECTED`이고 **`APPROVED`만 API에 나간다**.
- **생성**: ai `POST /summaries/attractions`(10곳씩), `POST /summaries/region`. 입력은 TourAPI 공개 데이터(이름·분류·지역·설명)뿐이다. 회원 정보는 보내지 않는다.
- **원천 설명이 없는 관광지**는 이름·분류만으로 초안을 만든다(`NAME_CATEGORY`). "LLM으로 없는 설명을 지어내지 않는다"는 규칙의 예외라서 ① 프롬프트가 입력에 없는 사실(경관·역사·메뉴·시설)을 금지하고 ② 사람이 승인해야 나가며 ③ `summaryBasis`로 화면에 구분해 표시한다. `description` 필드는 여전히 원천만 쓴다.
- **재생성**: 근거(이름·분류·설명·대표 관광지)와 프롬프트 버전의 해시(`source_hash`)가 바뀌면 다시 만든다. 이때 행은 `DRAFT`로 돌아가 다시 승인해야 한다. 같은 입력으로 다시 실행하면 아무것도 바뀌지 않는다.
- **표시용**이다. 임베딩 문장에 넣지 않는다(넣으면 템플릿 버전을 올리고 벡터를 다시 만들어야 한다).
- 배치·검수 절차: [runbook](../runbooks/summary-review.md).

#### 태그 사전

2026-10-10 팀 확정. 사전에 없는 태그는 저장하지 않는다(DB 제약).

| 묶음 | 태그 | 정하는 방식 |
|---|---|---|
| 자연 | 바다, 산, 숲, 호수·강, 섬, 꽃 | 분류코드 규칙 (꽃은 설명 키워드도) |
| 역사·문화 | 역사, 전통, 박물관, 사찰 | 분류코드 규칙 |
| 도시·생활 | 시장, 카페, 야경, 골목 | 분류코드 규칙 + 설명 키워드 |
| 활동 | 액티비티, 산책, 체험 | 분류코드 규칙 (산책은 설명 키워드도) |
| 분위기 | 감성여행, 힐링, 가족여행, 데이트 | LLM이 사전 안에서 선택 (일부 분류는 규칙: 웰니스 → 힐링, 동물원·수족관 → 가족여행) |

지역 태그는 지역 관광지 분류 분포에서 8% 이상·3곳 이상인 것(최대 3개)을 규칙으로 붙이고 분위기 태그를 더한다. 규칙 코드표는 ai `tripin_ai/summary/tags.py`.
