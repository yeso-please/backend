# 한 줄 소개·태그 초안 생성과 검수 (#89)

지역 태그라인과 관광지 한 줄 소개·태그를 ai 서버(Gemini)로 초안 생성 → CSV로 검수 → 승인분만 API에 노출한다. 정책은 [attraction.md "한 줄 소개와 태그"](../api/attraction.md#한-줄-소개와-태그-89).

## 준비

- V21 migration이 적용된 DB. 개발 RDS 적용은 `$flyway-rds-sync` 절차로, 요청이 있을 때만 한다.
- ai 서버가 `GEMINI_API_KEY`(또는 `GEMINI_API_KEY_FILE`)로 떠 있어야 한다. 키가 없으면 ai가 `llmError=NO_LLM`을 주고 배치는 아무것도 저장하지 않고 멈춘다.
- `.env`(커밋 금지): `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `EMBEDDING_SERVICE_BASE_URL`(ai 서버 주소). 원격 쓰기는 `MIGRATION_TARGET_ENV=dev`, `tripin_dev`, `sslmode=verify-full`, `MIGRATION_BEFORE_SNAPSHOT`(쓰기 전 스냅샷 이름)이 있어야 한다.

## 1. 계획 확인 (dry-run, 쓰기 없음)

```bash
./gradlew summaryBackfill --args="--mode=dry-run --scope=attractions --max-calls=100"
```

`targets`(생성할 곳), `이름·분류만`(승인 시 특히 주의할 곳), `calls`(LLM 호출 수, 관광지는 10곳당 1회), `planSha256`을 확인한다. 시험은 `--region=51150`처럼 한 지역으로 한다.

## 2. 초안 생성 (apply)

```bash
./gradlew summaryBackfill --args="--mode=apply --scope=attractions --max-calls=100 --confirm-plan=<dry-run의 planSha256>"
./gradlew summaryBackfill --args="--mode=dry-run --scope=regions"   # 지역도 같은 순서
```

- 호출 사이에 기본 4.5초를 쉰다(`--pause-millis`, Gemini 무료 등급 분당 한도). 분당 한도에 걸리면 65초 쉬고 최대 3번 다시 시도하고, 그래도 걸리면 일일 한도로 보고 `QUOTA_EXHAUSTED`로 멈춘다. Gemini 일시 오류(5xx·네트워크)는 그 대상만 건너뛴다.
- 같은 쓰기 잠금(`IngestionWriterLock`)을 쓰므로 다른 적재와 동시에 돌지 않는다. 실행 기록은 `ingestion_runs`(`job_type = SUMMARY_DRAFTS`).
- 멱등: 근거(이름·분류·설명, 지역은 대표 관광지)와 프롬프트 버전 해시가 같으면 다시 만들지 않는다. 남은 대상은 다음 실행에서 이어서 만든다. 한도 초과(`QUOTA_EXHAUSTED`)면 다음 날 같은 명령을 다시 실행한다.
- 검증(길이·과장·근거 없는 숫자·다른 장소 이름)에 걸린 문장은 저장하지 않고 다음 실행에서 다시 시도한다.
- 근거가 바뀐 곳은 다시 만들고 `DRAFT`로 돌린다(승인 해제).

## 3. 검수 CSV 내보내기

```bash
./gradlew summaryBackfill --args="--mode=export --scope=attractions --out=drafts-attractions.csv"
```

UTF-8(BOM) CSV라 엑셀에서 바로 열린다. `NAME_CATEGORY`(설명 없이 쓴 초안)가 먼저 나온다. **이 파일은 커밋하지 않는다.**

| 열 | 검수자가 할 일 |
|---|---|
| `text`, `tags`, `source_excerpt` | 원천 설명(`source_excerpt`)과 대조한다. 근거에 없는 사실·과장이 있으면 고치거나 반려 |
| `decision` | `APPROVE` 또는 `REJECT`. 비우면 그대로 `DRAFT` |
| `edited_text`, `edited_tags` | 고칠 때만. 태그는 공백·쉼표로 구분, 사전 21개만 |
| `note` | 반려 사유 등 |

`summary_id`, `source_hash`는 고치지 않는다.

## 4. 검수 결과 반영

```bash
./gradlew summaryBackfill --args="--mode=import --scope=attractions --in=reviewed.csv --reviewer=이름"
```

- 모든 줄을 먼저 검사한다. 사전 밖 태그·길이 초과(관광지 50자, 지역 30자)가 하나라도 있으면 **아무것도 반영하지 않고** 줄 번호를 알려 준다.
- `DRAFT`이고 `source_hash`가 같은 행만 바꾼다. 내보낸 뒤 다시 생성된 행은 `staleOrNotDraft`로 세고 건너뛴다.
- 반영 즉시 `APPROVED`가 7-2·7-4에 나온다. 승인을 취소하는 도구는 아직 없다(범위 밖). 필요하면 운영자가 확인한 뒤 처리한다.
