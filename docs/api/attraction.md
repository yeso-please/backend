# 7. 지역·관광지

- 계약 상태: agreed
- 모듈: `attraction/region`
- 정책 소스: [지역·관광지 품질](../product.md#지역관광지-품질), [지역 소개 콘텐츠](../design/recommendation.md#4-지역-소개-콘텐츠)

**호출 주체** — 7-1~7-4 모두 회원. 특정 여행에 대한 권한은 확인하지 않는다. 공유 링크 소지자(share session)는 호출할 수 없다. 비회원 뷰어는 [4-13](trip.md#4-13-공유-코스-조회) 응답만으로 코스 화면을 그린다(2026-09-25).

**추천 가능 관광지** — 유효한 지역·좌표, 공백이 아닌 상세 설명, 검증된(`VALID`) 이미지 1장 이상, 차단성 품질 이슈 없음을 모두 만족하는 장소. 차단성 품질 이슈는 `data_quality_issues`에서 심각도 `ERROR`이고 열린(`OPEN`) 이슈다. `WARNING`이나 해결된 이슈는 막지 않는다. 쇼핑·숙박·음식점은 코스 후보가 아니라서 빠진다. 이 판정은 수집 결과에서 파생하며 사람이 수동으로 켜지 않는다. 소개문은 가장 최근에 승인된 한 건을 본다.

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
| 음식점(contentType 39) | 관광지가 아니라 식당 원천 | — |

- 제외 조건 `물놀이`는 해수욕장·계곡(자연 소분류)과 수상 레포츠(`A0302`)를 뜻한다.
- TourAPI가 신규 분류 체계로 바뀌면 같은 의미의 코드로 대응한다.
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
  "introduction": ["첫 문단…", "둘째 문단…"],
  "heroImage": {"url": "https://…", "sourceName": "한국관광공사", "sourceUrl": "https://…", "license": "공공누리 1유형"},
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
| `heroImage` | 승인된 콘텐츠의 이미지가 검증되지 않았거나 소개 콘텐츠가 없으면 `null` |
| `heroImage.license` | 원천이 밝힌 이용 조건. 없으면 `null` |

승인 콘텐츠가 없는 지역에는 도시명을 제목으로 쓰고, 소개·태그·관광지·출처는 빈 배열, `heroImage`와 `updatedAt`은 `null`로 응답한다. 임시 문구·허구 소개를 만들지 않는다.

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
- 쇼핑·숙박·음식점은 코스 후보가 아니라서 핀에 넣지 않는다(2026-09-27). 식당은 5-5·5-6으로 고른다.
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
  "sources": [{"name": "한국관광공사 TourAPI", "contentId": "126207", "fetchedAt": "2026-09-30T00:00:00"}]
}
```

| 필드 | 설명 |
|---|---|
| `description` | 원천 상세 설명. 안전하게 정제한 텍스트. 생성형 문장으로 채우지 않는다 |
| `images` | 검증된(`VALID`) 이미지만. `sourceName`은 원천이 TourAPI면 `한국관광공사` |
| `useTime`, `restDate` | 원천 문자열 그대로. 없으면 `null` |
| `estimatedDurationMinutes` | 유형별 기본 체류시간(60·90·120분). 실측이 아니다 |
| `notRecommendableReasons` | `MISSING_DESCRIPTION` \| `MISSING_IMAGE` \| `MISSING_COORDINATE` \| `QUALITY_ISSUE` |

| 오류 | HTTP | code |
|---|---:|---|
| 없는 관광지, 또는 쇼핑·숙박·음식점(코스 후보가 아닌 장소) | 404 | `ATTRACTION_NOT_FOUND` |
