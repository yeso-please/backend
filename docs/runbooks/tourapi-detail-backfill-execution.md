# TourAPI 관광지·코스 상세 설명 수집기

이 문서는 기존 소개글 보강 스킬과 별도로 제공되는 `tourApiDetailBackfill` Gradle 실행기의 사용법을 설명한다. TourAPI 원천 ID가 연결되어 있고 소개글이 비어 있는 관광지와 공식 코스만 조회한다. 이미지, 좌표, 유형별 이용정보는 대상이 아니다.

## 준비

저장소 루트의 `.env.example`을 참고해 로컬 `.env`를 작성한다. RDS 쓰기에는 DML 전용 계정과 `sslmode=verify-full` 연결을 사용한다. 관리자나 Flyway 계정 암호는 이 도구에 넣지 않는다. `.env`는 Git에 커밋하지 않는다.

RDS에 쓰기 전에 환경을 `MIGRATION_TARGET_ENV=dev`로 지정하고 `MIGRATION_BEFORE_SNAPSHOT`에 팀에서 관리하는 사전 스냅샷 이름을 입력한다. 실행기는 스냅샷 이름을 확인 게이트로 사용하며 AWS에서 스냅샷 상태를 검사하지 않는다. 작업자는 복구 가능한 스냅샷을 준비한 뒤 실행해야 한다.

## 실행

먼저 키 없이 대상과 계획 hash를 확인한다. `--max-calls`는 당일 남은 TourAPI 할당량에 맞춰 설정한다.

```powershell
.\gradlew.bat tourApiDetailBackfill --args="--mode=dry-run --scope=attractions --max-calls=100"
```

관광지와 공식 코스를 모두 확인하려면 `--scope=all`을 사용한다. 관광지는 추천 후보 유형(12, 14, 15, 28)만 포함하고 숙박·쇼핑 유형은 제외한다.

dry-run 출력의 `planSha256`를 그대로 넣어 적용한다. hash가 다르거나 대상 계획이 변경되면 실행기가 적용을 거부한다.

```powershell
.\gradlew.bat tourApiDetailBackfill --args="--mode=apply --scope=attractions --max-calls=100 --confirm-plan=<DRY_RUN_PLAN_SHA256>"
```

`--mode=sync`는 관광지 대상의 계획 확인과 실행을 한 프로세스 안에서 진행하며, 안내된 `APPLY tripin_dev`를 직접 입력해야 TourAPI 호출을 시작한다.

## 중단 후 재개

실행은 같은 PostgreSQL advisory lock을 사용하는 다른 지원 적재 작업과 겹치지 않도록 직렬화된다. 완료되지 않은 실행이 있으면 실행 ID와 cursor를 확인한다. 이전 프로세스가 완전히 종료된 것을 확인한 뒤에만 아래와 같이 재개한다.

```powershell
.\gradlew.bat tourApiDetailBackfill --args="--mode=apply --resume-run-id=<RUN_ID> --confirm-stopped-run-id=<SAME_RUN_ID> --max-calls=100"
```

기존 설명은 덮어쓰지 않는다. 원천 응답에 소개글이 없으면 품질 이력에 기록해 자동 재호출을 막는다. 실행 결과와 API 사용량을 확인하고, 임베딩 갱신 등 후속 처리는 별도로 진행한다.
