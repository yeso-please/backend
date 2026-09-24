# 7. 지역·관광지

- 계약 상태: draft
- 모듈: `attraction/region`
- 정책 소스: [지역·관광지 품질](../mvp/decisions.md#지역관광지-품질), [지역 소개 콘텐츠](../mvp/data-and-recommendation.md#4-지역-소개-콘텐츠)

**호출 주체** — 7-1은 회원. 7-2~7-4는 공공 관광 정보이므로 인증된 주체 누구나(회원, 공유 링크 share session) 호출할 수 있다. 특정 여행에 대한 권한은 확인하지 않는다.

**추천 가능 관광지** — 유효한 지역·좌표, 공백이 아닌 상세 설명, 검증된(`VALID`) 이미지 1장 이상, 차단성 품질 이슈 없음을 모두 만족하는 장소. 이 판정은 수집 결과에서 파생하며 사람이 수동으로 켜지 않는다.

**추첨 가능 지역** — 승인된(`APPROVED`) 최신 소개문, 검증된 대표 이미지, 추천 가능 관광지 `days × 밀도 상한 + min(days, 3)`개 이상(RELAXED 상한 4, PACKED 6).

**관광지 유형 `category`**

| 값 | 표시 |
|---|---|
| `NATURE` | 자연 |
| `HISTORY_CULTURE` | 역사·문화 |
| `ACTIVITY` | 체험·레포츠 |
| `WALK_REST` | 산책·휴식 |
| `ETC` | 기타 |

원천 분류(TourAPI `contentTypeId`·분류 코드)에서 `category`와 기본 체류시간(60·90·120분)을 정하는 매핑 표는 [결정 필요 A-1](#결정-필요)이다. 5장 코스와 이 장이 같은 표를 쓴다.

**오류 코드**

| code | HTTP | 상황 |
|---|---:|---|
| `REGION_INVALID_DAYS` | 400 | `days`가 1~7 밖 |
| `MAP_BOUNDS_INVALID` | 400 | `bbox` 형식 오류, 최소가 최대보다 큼, 한국 범위 밖 |
| `REGION_NOT_FOUND` | 404 | 없는 `SIG_CD` |
| `ATTRACTION_NOT_FOUND` | 404 | 없는 관광지 |
| `REGION_CONTENT_NOT_READY` | 422 | 승인된 소개문이나 검증된 대표 이미지가 없음 |

---

### 7-1. 지역 목록

> `호출: 회원` · `⬜ 미구현`

```
GET /api/regions?days=3&scheduleDensity=RELAXED
```

| Query | 필수 | 설명 |
|---|---|---|
| `days` | 예 | 1~7. 여행 일수 |
| `scheduleDensity` | 아니오 | `RELAXED` \| `PACKED`. 생략하면 호출자의 최신 온보딩 값, 없으면 `RELAXED` |

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
| `NO_APPROVED_CONTENT` | 승인된 소개문 없음 |
| `NO_VALID_HERO_IMAGE` | 검증된 대표 이미지 없음 |
| `INSUFFICIENT_ATTRACTIONS` | 이 일수·밀도에 필요한 추천 가능 관광지 부족 |

추첨 불가 지역도 지도에는 표시한다. 인기·별점 순위는 제공하지 않는다.

| 오류 | HTTP | code |
|---|---:|---|
| 일수 오류 | 400 | `REGION_INVALID_DAYS` |

---

### 7-2. 지역 카드

> `호출: 인증된 주체` · `⬜ 미구현`

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

| 필드 | 설명 |
|---|---|
| `introduction` | 2~4문단. 검증된 사실·대표 관광지·출처로만 쓰고 사람이 승인한 문장 |
| `landmarks` | 승인된 소개문이 근거로 쓴 대표 관광지 중 추천 가능한 것 1~3개, 소개문에 나온 순서 |
| `characteristics`, `historyHighlights` | 승인된 지역 소개 콘텐츠의 태그. 없으면 빈 배열 |
| `heroImage.license` | 원천이 밝힌 이용 조건. 없으면 `null` |

승인 콘텐츠가 없는 지역에 임시 문구·허구 소개를 만들지 않는다.

| 오류 | HTTP | code |
|---|---:|---|
| 없는 지역 | 404 | `REGION_NOT_FOUND` |
| 소개·대표 이미지 미준비 | 422 | `REGION_CONTENT_NOT_READY` |

---

### 7-3. 지도 관광지 핀

> `호출: 인증된 주체` · `⬜ 미구현`

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
- 정렬은 `attractionId` 오름차순이며, `nextCursor`는 그다음 페이지를 가리키는 불투명 문자열이다. 마지막 페이지면 `null`이다.
- `bbox`는 위도 33~39, 경도 124~132 안이어야 한다.

| 오류 | HTTP | code |
|---|---:|---|
| bbox 오류 | 400 | `MAP_BOUNDS_INVALID` |
| 없는 지역 | 404 | `REGION_NOT_FOUND` |

---

### 7-4. 관광지 상세

> `호출: 인증된 주체` · `⬜ 미구현`

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
| `images` | 검증된(`VALID`) 이미지만 |
| `useTime`, `restDate` | 원천 문자열 그대로. 없으면 `null` |
| `estimatedDurationMinutes` | 유형별 기본 체류시간(60·90·120분). 실측이 아니다 |
| `notRecommendableReasons` | `MISSING_DESCRIPTION` \| `MISSING_IMAGE` \| `MISSING_COORDINATE` \| `QUALITY_ISSUE` |

| 오류 | HTTP | code |
|---|---:|---|
| 없는 관광지 | 404 | `ATTRACTION_NOT_FOUND` |

---

## 결정 필요

| ID | 항목 | 현재 초안 |
|---|---|---|
| A-1 | 원천 분류 → `category`·기본 체류시간 매핑 | 적재된 관광지 데이터의 분류 값을 확인한 뒤 표로 확정한다. 초안: 자연 → `NATURE`, 역사·건축·문화시설 → `HISTORY_CULTURE`(문화시설 120분), 레포츠·체험 → `ACTIVITY`(레포츠 120분), 휴양·공원 → `WALK_REST`, 그 외 `ETC`. 포토·전망 성격 장소 60분, 나머지 90분 |
| A-2 | 지역 소개 승인 방식 | 승인 도구를 미뤘다. 승인된 소개문이 하나도 없으면 모든 지역이 추첨 불가다. MVP 시연 지역을 어떤 방식으로 승인해 둘지 정해야 한다 |
