# TourAPI 관광지 소개글 보강 실행

이 문서는 `$tourapi-attraction-backfill` 스킬을 사용해 개발 RDS의 소개글 누락 관광지를 TourAPI 원문으로 보강하는 방법이다. 이미지, 좌표, 공식 코스 소개, 유형별 이용정보는 이 절차의 대상이 아니다.

## 1. 사전 준비

### 1.1 TourAPI 키 발급

1. [공공데이터포털](https://www.data.go.kr/)에서 계정을 만들고 로그인한다.
2. [한국관광공사_국문 관광정보 서비스_GW](https://www.data.go.kr/data/15101578/openapi.do) 상세 페이지를 연다. `GW`가 붙은 국문 서비스인지 확인한다.
3. **활용신청**에서 개발 목적으로 신청하고 승인 상태가 된 것을 확인한다. 현재 포털 안내상 개발계정은 자동승인이며 일일 신청 트래픽은 1,000회다. 실제 사용 가능량은 계정의 당일 사용량·서비스 상태에 따라 더 적을 수 있다. 운영 서비스는 별도 활용사례 등록·심의가 필요하므로 이 개발 도구로 호출하지 않는다.
4. 포털의 마이페이지/API 인증키 화면에서 이 서비스에 발급된 **일반 인증키**를 복사한다. 이 저장소는 키를 요청 파라미터에 맞게 인코딩하므로 포털 화면에서 제공하는 일반 인증키(Encoding 또는 Decoding)를 `.env`에 원문으로 넣을 수 있다. 인증키를 채팅·Git·명령 인자·로그에 붙이지 않는다.

### 1.2 로컬 `.env` 설정

저장소의 `.env.example`을 참고해 backend 루트의 `.env`를 준비한다. `.env`는 Git에서 제외된다. 필요한 항목은 아래와 같다.

```dotenv
DB_URL=jdbc:postgresql://<개발-RDS-endpoint>:5432/tripin_dev?currentSchema=app&sslmode=verify-full&sslrootcert=<로컬-CA-bundle-경로>
DB_USERNAME=tripin_app
DB_PASSWORD=<tripin_app-암호>
TOUR_API_KEY=<위에서-발급받은-일반-인증키>
TOUR_API_BASE_URL=https://apis.data.go.kr/B551011/KorService2
INGESTION_ACTOR=<팀에서-알아볼-사용자명>
MIGRATION_TARGET_ENV=dev
MIGRATION_BEFORE_SNAPSHOT=<사용자가-복구-가능하게-관리하는-기준-스냅샷-이름>
```

환경변수 이름·RDS 접속값의 저장 위치는 팀의 [Notion `.env` 안내](https://app.notion.com/p/env-3ea44e7d225a80ff9a74d89cd8f2c0b5?source=copy_link)를 따른다. Notion에는 실제 키와 비밀번호를 문서화하지 말고, 각 개발자가 자신의 로컬 `.env`에 직접 입력한다. `sslrootcert`는 AWS RDS CA bundle의 로컬 절대 경로를 지정한다. 스냅샷은 사용자가 항상 복구 가능한 상태로 관리한다.

필수 확인사항:

- DB는 개발 RDS의 `tripin_dev`이며 TLS `verify-full`이어야 한다. 운영/불명확한 DB는 거부된다.
- DB 계정은 DML 전용 `tripin_app`이다. 관리자·Flyway 계정은 이 도구에 넣지 않는다.
- `MIGRATION_BEFORE_SNAPSHOT`은 사용자가 관리하는 기준 스냅샷 이름이다. 실행기는 이 값을 원격 쓰기 안전 게이트로 요구하지만, 스냅샷의 상태를 AWS에서 매번 독립 확인하지는 않는다.
- TourAPI 키는 개인별로 보관한다. 여러 개발자 키를 한 작업에 합쳐 일일 한도를 우회하지 않는다.

## 2. 실행 방법

스킬을 직접 호출한다.

```text
/tourapi-attraction-backfill
```

또는 `$tourapi-attraction-backfill`로 요청한다. 직접 호출은 현재 설정된 **개발 RDS**의 대상 계산, TourAPI 상세 요청 및 허용된 누락 설명의 적재를 승인한다. 매 실행마다 별도의 전체 적재 승인을 다시 묻지 않는다.

스킬은 아래 순서로 동작한다.

1. `.env` 설정 유무, DB host/database/TLS 및 TourAPI 키 설정을 비밀값 없이 확인한다.
2. 적재 writer lock을 획득하고 다른 지원 배치가 실행 중인지 확인한다.
3. `attractions` 중 TourAPI 원천 ID가 있고, 추천 대상 유형(12 관광지, 14 문화시설, 15 축제·행사, 28 레포츠)에 속하며, 설명이 비고 아직 상세조회 처리되지 않은 행의 수와 요청 예산을 계산한다.
4. `detailCommon2` 최소 파라미터 요청으로 API 응답 코드·원천 ID·`overview`를 검증한다. 오류 응답은 빈 설명으로 분류하지 않는다.
5. 5건 이하 canary와 저장 결과를 확인한 뒤 실행 상한과 당일 잔여 쿼터 범위에서 이어서 적재한다. 한 번의 실행 최대 호출은 950회이며, 초과분은 cursor를 보존해 다음 실행에서 이어간다. 개발계정 일일 1,000회는 기본 제공량이지 잔여량 보장이 아니다.
6. 실행 ID, 성공·원천 항목 없음·실패 건수, 남은 수량과 재개 방법을 보고한다. 성공한 설명은 기존 값을 덮어쓰지 않으며 임베딩 상태를 `PENDING`으로 둔다.

명시적으로 명령 실행이 필요한 경우 dry-run은 다음과 같다.

```powershell
.\gradlew.bat tourApiDetailBackfill --args="--mode=dry-run --scope=attractions --max-calls=100"
```

호출량은 당일 남은 할당량에 맞게 낮춘다. 429/일일 한도 초과 시 자동으로 중단하고, `PARTIAL`/`QUOTA_EXHAUSTED` 상태는 보고된 실행 ID와 cursor를 사용해 이어간다. 이전 프로세스가 실제 종료됐는지 먼저 확인한다.

## 3. 범위와 실패 처리

- 적재 원문은 TourAPI `detailCommon2.overview`뿐이다. 생성형 AI로 원문에 없는 설명을 만들지 않는다.
- 성공 코드(`0000`)인데 요청 ID의 항목이 없거나 소개글이 비어 있을 때만 `SOURCE_EMPTY`로 기록한다. 인증·파라미터·응답형식·ID 검증 오류는 실패로 중단한다.
- 이미지, 좌표, 유형별 상세, 코스 소개는 이 스킬의 승인이 아니다.
- 임베딩 재생성, 추천 적격성 재판정, RDS 스키마 변경도 별도 작업이다.
- 키가 없거나 TLS/개발 DB 검증, writer lock, API 진단 중 하나라도 실패하면 RDS 적재를 진행하지 않는다.

## 공식 출처

- [공공데이터포털 — 한국관광공사 국문 관광정보 서비스_GW](https://www.data.go.kr/data/15101578/openapi.do)
- [한국관광공사 TourAPI 포털](https://api.visitkorea.or.kr/)
