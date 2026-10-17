# 지역 대표 사진 수집·검수

지역 카드(7-2) `heroImage` ②에 쓰는 시군구 대표 사진([계약](../api/attraction.md#지역-대표-사진), [출처 조사](../design/region-photo-research.md)). 사람이 지역마다 한 장을 승인한 것만 나간다.

## 1. 후보 수집 (관광사진 정보 API)

- 공공데이터포털 "한국관광공사_관광사진 정보_GW" 활용신청이 된 키(`TOUR_API_KEY`와 같은 계정)로 `PhotoGalleryService1/gallerySearchList1`을 부른다. 하루 1,000건.
- 시군구마다 이름으로 검색(`강릉`, 구는 `부산 중구`, 시 아래 구는 `포항 남구` → `포항`)하고, **촬영지(`galPhotographyLocation`)가 그 시군구인 사진만** 남긴다. 광주광역시·전라남도는 촬영지가 `전남광주통합특별시`로도 적혀 있다.
- 풍경 단어(전경·야경·해변·산·폭포 등)가 있으면 앞으로, 음식·실내·전시·축제·숙박은 빼고, 같은 제목은 한 장만. 지역당 최대 5장.
- 결과는 `candidates.jsonl` 한 줄에 한 지역: `{"sigCd", "regionName", "candidates": [{"galContentId", "title", "imageUrl", "photographer", "location", "month"}]}`.
- 2026-10-17 수집: 250곳 중 후보 3장 이상 211곳, 1~2장 26곳, 0장 13곳(대표 관광지 사진을 그대로 쓴다).

## 2. 검수

DB 없이도 할 수 있다. 후보 파일로 만든 `region-photos.html`(사진을 보고 지역마다 한 장 클릭, "쓸 사진 없음" 가능)에서 **검수 CSV 내려받기**를 누르면 아래 형식의 CSV가 나온다. 엑셀로 직접 채워도 된다.

| 열 | 설명 |
|---|---|
| `sig_cd`, `source_content_id` | 사진을 찾는 키. 바꾸지 않는다 |
| `decision` | `APPROVE`(지역마다 한 장) · `REJECT` · 빈칸(보류) |
| `note` | 남길 말(선택) |

고를 때: 그 지역을 대표하는 풍경인지, 안내판·클로즈업·사람 얼굴이 주인공이 아닌지, 계절감이 너무 강하지 않은지 본다.

## 3. 적재·반영

```bash
./gradlew regionPhotoBackfill --args='--mode=import-candidates --in=candidates.jsonl'
./gradlew regionPhotoBackfill --args='--mode=import --in=region-photos-reviewed.csv --reviewer=이름'
```

- `import-candidates`: 후보를 `region_hero_photos`에 DRAFT로 넣는다. 같은 사진은 다시 넣지 않고, 이미 검수한 사진은 건드리지 않는다. 이미지 주소는 `https://tong.visitkorea.or.kr/`만 받는다.
- `import`: 모든 줄을 먼저 검사하고 하나라도 틀리면 아무것도 쓰지 않는다. 같은 지역에 APPROVE가 두 줄이면 거부한다. 새로 승인하면 그 지역에 이전에 승인된 사진은 `REJECTED`가 된다. DRAFT인 사진만 바꾼다.
- `--mode=export --out=photos.csv`: DB에 남은 DRAFT를 같은 형식으로 뽑는다.
- 원격 쓰기는 `SummaryBackfill`과 같은 규칙(개발 RDS `tripin_dev`, `sslmode=verify-full`, `MIGRATION_TARGET_ENV=dev`, `MIGRATION_BEFORE_SNAPSHOT`)일 때만 된다. V23 migration이 먼저 적용되어 있어야 한다.

## 표시

사진은 원천 URL을 그대로 쓴다(복사하지 않는다). 공공누리 제1유형이라 **출처 표시가 조건**이다: 화면에 `heroImage.sourceName`·`photographer`·`license`(예: "한국관광공사 김지호 · 공공누리 제1유형")를 함께 보여 준다.
