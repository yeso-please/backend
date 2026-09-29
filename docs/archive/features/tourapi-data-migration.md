# TourAPI 데모 데이터 이관

- 상태: 로컬 및 개발 RDS 이관·재적용 검증 완료 / 이미지 검증·지역 콘텐츠 승인은 후속 작업
- 담당 범위: 데모 H2의 TourAPI 지역 참조·관광지(12/14/15/28/32/38)·음식점(39)·공식 코스(25)·경유지만 PostgreSQL로 옮긴다.
- 마지막 갱신일: 2026-09-27
- 관련: [MVP 결정](../mvp/decisions.md), [RDS 런북](../runbooks/rds-postgresql-bootstrap-and-migration.md)

## 결정

`region.sig_cd`는 외래키 참조용으로만 옮기고 데모 `ai_summary`는 옮기지 않는다. 출처 키는 `TOUR_API + source_content_id`로 식별한다. H2에는 원천 구분 컬럼이 없으므로 TourSyncService가 적재한 지정 테이블만 읽는다. 개인 계정·온보딩·여행기·일정·업로드와 비TourAPI 음식 데이터는 조회·이관하지 않는다.

관광지 유형은 TourAPI 콘텐츠 유형 ID로 보존한다. 상세조회 여부는 원본 boolean으로 보존하고 실제 조회 시각은 알 수 없으므로 `detail_fetched_at`을 채우지 않는다. 축제 기간은 `yyyyMMdd` 문자열을 검증해 DATE로 옮긴다. 대표 이미지는 검증 전 `PENDING`으로 두며, 설명·이미지·좌표가 있어도 이미지 검증 및 지역 소개 승인 전 추천 가능으로 표시하지 않는다. 공식 경유지는 원본 `point_index` 순서로 보존하며 연결 관광지가 없어도 원본 콘텐츠 ID를 보존한다.

## 완료 조건

- Flyway append-only migration과 스키마 검증
- 원본 read-only 백업의 SHA-256 기록
- dry-run 무변경, 로컬 apply, validate, 재적용 시 inserted/updated 0
- 결측·중복·미연결 경유지와 추천 가능 후보 품질 리포트
- 개발 RDS는 별도 설정, 사전 snapshot, 승인된 dry-run 및 TLS 확인 후에만 적용

## 로컬 검증 결과 (2026-09-27)

- 원본 백업 SHA-256: `fee56562c20a84156e279c836b8d781c455e9cf7924788bcbd6d56d810c7897a`
- PostgreSQL 17: 지역 250, 관광지 12,164, 관광지 이미지 10,933, 음식점 8,540, 공식 코스 347, 경유지 537. 계정 0.
- 최초 적재 격리 0; 동일 원본 재적용 삽입·수정 0. V4 추가 후 음식점 경유지 24건 연결.
- 경유지 매핑: 관광지 234, 음식점 24, 미연결 279. 설명·이미지·좌표 모두 있는 관광지는 3,887건이지만 이미지 검증 상태가 PENDING이므로 VALID 이미지는 0건이다.
- 미연결·결측은 `data_quality_issues`에 기록한다. 이미지 검증·상세 보강은 아직 실행하지 않았다.

## 개발 RDS 적용 결과 (2026-09-27)

- Flyway V1~V4와 동일 H2 백업을 개발 RDS `tripin_dev`에 적용했다. 최종 수량은 위 로컬 결과와 같고 원천 ID 중복·격리·개인 계정 데이터는 0건이다.
- 재적용 실행 ID `3ff4ba08-1ec6-44e4-a063-a1ab27e3bb6a`: 삽입 0, 수정 0, 격리 0. 전체 Testcontainers 테스트와 실제 `dev-rds` 웹 애플리케이션 기동도 통과했다.
- 사전·사후 수동 스냅샷의 사용 가능 상태는 AWS 콘솔에서 사용자가 확인했다. 상세 실행 기록과 논리 백업 checksum은 [RDS 런북](../runbooks/rds-postgresql-bootstrap-and-migration.md)에 남겼다.
