---
name: tourapi-attraction-backfill
description: Automatically inspect, collect, and backfill all eligible missing TourAPI attraction descriptions into the development PostgreSQL RDS, including TourAPI calls and writes. Use when invoked by /tourapi-attraction-backfill; do not use for ordinary API handling or course ingestion.
---

# TourAPI 관광지 상세설명 RDS 보강

목표는 개발 RDS의 TourAPI 관광지 중 상세설명이 없는 대상을 찾아 한국관광공사 TourAPI에서 설명을 받아 저장하는 과정을 반복 가능하고 안전하게 자동화하는 것이다. 기본 대상은 `attractions.description`이며, 코스 설명·이미지·좌표·유형별 상세·경유지는 이 작업 범위에 포함하지 않는다. 호출 수를 소비하거나 DB를 변경하기 전에 대상·계약·환경을 확정한다.

## 기준 문서

먼저 `docs/product.md`와 `docs/design/recommendation.md`를 읽는다. 데모 수집 코드는 참고만 하고 backend의 PostgreSQL 스키마와 Flyway 계약을 따른다.

## 사전 점검

1. 대상 환경은 기본적으로 사용자가 지정한 개발 RDS(`tripin_dev`)다. DB host/database/TLS 모드를 비밀값 없이 표시하고, 운영/불명확한 대상을 거부한다. 개발 RDS 스냅샷은 사용자가 항상 복구 가능한 상태로 유지·관리하며, 에이전트는 매번 별도 상태 확인을 반복하지 않는다.
2. `TOUR_API_KEY`가 존재하는지만 검사하고 값은 출력하지 않는다. 키 발급·공유·Git 저장을 대신하지 않는다.
3. 현재 `ingestion_runs`와 미완료 cursor를 확인한다. 같은 RDS의 대량 적재/보강은 `IngestionWriterLock` session advisory lock을 먼저 획득한다. 잠금을 얻지 못하면 현재 작업 메타데이터를 사용자에게 보여주고 즉시 중단한다.
4. RDS에서 TourAPI 원천 ID가 있고 설명이 비어 있는 관광지 수와 유형별 수를 dry-run한다. 이미 상세 조회했거나 `SOURCE_EMPTY`인 항목은 기본적으로 반복 요청하지 않는다. 재시도하려면 먼저 이전 실패 사유를 진단한다.
5. 1건 진단 호출로 키·endpoint·응답 형식을 확인한 후에만 canary와 batch를 진행한다. `detailCommon2`에는 `contentId`를 전달하고 `contentTypeId`는 넣지 않는다(공통 상세 호출에서 타입 파라미터를 생략하며, 실제 원천 `contenttypeid=25` 코스 ID를 넣었을 때 invalid parameter 오류가 발생한 이력이 있음). 요청 수신 전 `resultCode=0000`, 유효한 item의 ID 일치, `overview` 유무를 구분한다. non-success JSON/XML, 파싱 실패, ID 불일치, 인증·파라미터·쿼터 오류를 빈 소개문으로 취급하거나 `SOURCE_EMPTY`로 기록하지 않는다.
6. 실행기의 호출 상한(최대 950회/실행)과 TourAPI의 현재 당일 잔여 한도를 확인 가능한 범위에서 확인한다. 잔여량이 불명확하면 공식 한도를 넘지 않도록 호출 상한을 낮추고 cursor 기반으로 여러 실행으로 나눈다. 여러 개발자 키를 묶어 쿼터를 우회하지 않는다.

## 구현·실행 규칙

- 관광지 원천 키는 `(source_system, source_content_id)`로 식별한다. 수집 도중 종료되어도 cursor 기반으로 재개한다.
- 이 작업에서는 `detailCommon2`의 `overview`만 요청한다. 전체 응답을 저장하지 않고 원천 ID·대상·결과 코드·수집 시각 등 최소한의 실행/품질 메타데이터만 남긴다.
- 성공, `SOURCE_EMPTY`, 재시도 가능, 영구 실패를 구분한다. timeout/5xx는 제한된 지수 backoff, 429/일일 한도는 즉시 중단한다.
- 응답 코드를 검증한 성공 응답에서 해당 ID의 item이 정상 반환되고 `overview`가 비었을 때만 `SOURCE_EMPTY`로 기록한다. API가 오류 코드를 주거나 예상 형식이 아니면 실행을 실패/부분 종료시키고, 호출 파라미터와 민감정보를 제외한 오류 코드만 보고한다.
- 기존 검수 데이터를 빈 값이나 품질이 낮은 응답으로 덮어쓰지 않는다. 텍스트 변경 시 임베딩 상태를 `PENDING`으로 바꾼다.
- 설명 수집만으로 추천 가능 판정을 완료로 간주하지 않는다. 이미지 URL은 별도 검증 전 `UNVERIFIED`이며 이미지·좌표 품질도 각각 별도 기준으로 확인한다.
- API 응답이나 LLM으로 없는 설명을 지어내지 않는다. 원천에 없으면 `data_quality_issues`에 남긴다.
- `/tourapi-attraction-backfill` 또는 `$tourapi-attraction-backfill` 호출 자체가 **현재 개발 RDS의 관광지 설명 결측분 전체를 수집하고 반영하는 승인**이다. 별도로 “전체 실행 허가”를 묻지 않는다. 먼저 대상 수와 API 호출 상한을 산출하고, API key·응답 계약을 1건으로 확인한 뒤 전체 작업을 자동 실행한다. 단, 1건 진단이 API 오류·인증 실패·형식 불일치·응답 ID 불일치이면 적재하지 않고 즉시 멈춰 원인을 보고한다. 진단 성공 후 전체 누락 대상이 5건을 초과하면 첫 1~5건의 canary를 저장·검증하고 나머지를 계속 처리한다. 실행 상한 또는 확인 가능한 당일 잔여 한도를 초과하는 대상은 호출하지 말고 cursor를 보존해 재개 가능한 상태로 남긴다.
- 안전 조건: 접속 대상이 정확히 사용자 지정 개발 RDS `tripin_dev`이며 TLS `verify-full`일 것, TourAPI 키가 설정되어 있을 것, 동일 RDS 적재 writer lock을 획득했을 것, 진행 중인 충돌 작업이 없을 것. 어느 하나라도 충족되지 않으면 fail-closed로 중단한다. 운영/불명확 DB에는 이 스킬의 호출만으로 절대 쓰지 않는다.
- 별도 권한이 필요한 범위(스키마 변경, 운영 DB, 코스·이미지·좌표 데이터 적재)는 이 자동 승인의 범위가 아니다. `sync` 모드의 대화형 확인을 우회하거나 확인 문구를 입력하는 대신 승인 경계가 명확한 `dry-run` + plan hash `apply` 흐름을 사용한다.

## 완료 검증

- 실행 ID, cursor, 시작/종료 시각, 호출 수, 성공/empty/retry/failure 수를 출력한다.
- 대량 쓰기 실행자 식별을 위해 `INGESTION_ACTOR`를 설정하고, 종료/예외 시 advisory lock을 같은 DB 세션에서 해제한다. 프로세스가 비정상 종료되면 PostgreSQL이 세션 잠금을 자동 해제하며, 남은 `RUNNING` 이력은 다음 실행이 stale 상태로 구분해야 한다.
- 전후 전체/유형별 설명 보유 수와 결측 수, 업데이트·empty·실패 수를 비교한다. 이미지·좌표 수치는 변경이 없음을 확인할 수 있지만 본 작업으로 보강하지 않는다.
- 동일 범위를 재실행해 행 수와 값이 변하지 않는 멱등성을 확인한다.
- 실행 제한으로 전체 결측을 처리하지 못한 경우 남은 대상 수, 마지막 cursor, 다음 재개 명령을 함께 제시하고, quota reset 이후 이어서 수행한다.
- 로그와 git diff에서 API key, DB URL credential, 개인정보를 검색한다.
- 구현 변경이 있으면 통합 테스트와 관련 `docs/api`를 함께 갱신한다.

이 스킬이 직접 호출되면 위 제한 안에서 TourAPI 외부 호출과 개발 RDS DML 적재를 자동 수행한다. 스킬을 언급만 하거나 설명·구현·계획만 요청한 경우에는 실행하지 않는다. 저장소 전역 지침, 해당 도구의 안전 검증, 현재 실행 환경의 접근 권한은 계속 우선한다.
