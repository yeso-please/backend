---
name: tourapi-detail-backfill
description: Backfill missing TourAPI attraction descriptions, images, coordinates, and official-course mappings into PostgreSQL with quota-aware, resumable batches and a data-quality report. Use for implementing, dry-running, or executing TriPin tourism-data enrichment; do not use for ordinary API request handling.
---

# TourAPI 상세 보강

목표는 누락 데이터를 안전하게 채우고 추천 가능 여부를 재계산하는 것이다. 호출 수를 소비하거나 DB를 변경하기 전에 대상과 환경을 확정한다.

## 기준 문서

먼저 `docs/product.md`와 `docs/design/recommendation.md`를 읽는다. 데모 수집 코드는 참고만 하고 backend의 PostgreSQL 스키마와 Flyway 계약을 따른다.

## 사전 점검

1. 대상 환경(`local` 또는 명시된 `dev`)과 DB host/database를 비밀값 없이 출력한다. 운영 환경이면 실행하지 말고 별도 승인·스냅샷 절차를 요구한다.
2. `TOUR_API_KEY`가 존재하는지만 검사하고 값은 출력하지 않는다. 키 발급·공유·Git 저장을 대신하지 않는다.
3. 현재 `ingestion_runs`와 미완료 cursor를 확인한다. 실행 중인 동일 job이 있으면 중복 실행하지 않는다.
4. 다음 집합의 개수와 예상 호출 수를 dry-run한다: 설명 누락, 이미지 누락, 좌표 누락, 공식 코스 미매핑. 이미 성공했거나 `SOURCE_EMPTY`인 필드는 기본적으로 재호출하지 않는다.
5. 서비스의 현재 일일/초당 한도보다 작은 호출 예산을 정한다. 여러 개발자 키를 한 작업에 묶어 쿼터를 우회하지 않는다.

## 구현·실행 규칙

- 원천 키는 `(source_system, source_content_id)`로 upsert한다. 수집 도중 종료되어도 같은 cursor부터 재개할 수 있어야 한다.
- 목록보다 공통 상세, 이미지, 유형별 소개, 공식 코스 순으로 필요한 endpoint만 호출한다. 응답 원문 전체가 필요하지 않으면 보존하지 말고 원천 ID·필드·수집 시각을 저장한다.
- 성공, `SOURCE_EMPTY`, 재시도 가능, 영구 실패를 구분한다. timeout/5xx는 제한된 지수 backoff, 429/일일 한도는 즉시 중단한다.
- 기존 검수 데이터를 빈 값이나 품질이 낮은 응답으로 덮어쓰지 않는다. 텍스트 변경 시 임베딩 상태를 `PENDING`으로 바꾼다.
- 이미지 URL은 별도 검증 전 `UNVERIFIED`다. 설명·이미지·좌표가 모두 검증돼야 추천 가능하다.
- API 응답이나 LLM으로 없는 설명을 지어내지 않는다. 원천에 없으면 `data_quality_issues`에 남긴다.

## 완료 검증

- 실행 ID, cursor, 시작/종료 시각, 호출 수, 성공/empty/retry/failure 수를 출력한다.
- 전후 지역별 설명·이미지·좌표와 추천 가능 교집합을 비교한다.
- 동일 범위를 재실행해 행 수와 값이 변하지 않는 멱등성을 확인한다.
- 로그와 git diff에서 API key, DB URL credential, 개인정보를 검색한다.
- 구현 변경이 있으면 통합 테스트와 관련 `docs/api`를 함께 갱신한다.

외부 호출이나 DB 변경을 요청받지 않은 코드/문서 작업에서는 dry-run 계획과 구현까지만 하고 실제 배치를 실행하지 않는다.
