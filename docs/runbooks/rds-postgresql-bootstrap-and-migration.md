# 개발 RDS PostgreSQL 생성·데모 데이터 이관 런북

이 문서는 AWS RDS를 처음 사용하는 개발자가 TriPin 개발 DB를 만들고, 데모 H2의 비개인 데이터를 새 Flyway 스키마로 안전하게 옮긴 뒤 TourAPI로 보강하는 순서다.

> **현재 상태:** WORK-00 구현으로 PostgreSQL/Flyway V1과 `ddl-auto=validate`가 준비됐다. 브랜치의 CI가 통과하고 팀 리뷰로 V1을 승인한 다음 로컬 이관 리허설을 시작한다. JPA가 임의로 만든 테이블을 기준 스키마로 삼지 않는다.

> **중요한 현실 확인:** 2026-09-20 현재 이 저장소에는 WORK-09 이관 실행기와 migration 전용 Gradle task가 아직 없다. `./gradlew flywayMigrate`는 현재 실행할 수 없는 명령이다. 지금 바로 가능한 것은 PostgreSQL/Flyway V1 검증과 Spring Boot 기동 시 migration 적용까지다. H2 이관은 이 문서의 **Phase C 구현 계약**을 먼저 코드로 완성한 뒤 실행한다.

## 0. 이 문서를 사용하는 방법

이 작업은 세 덩어리다. 순서를 섞지 않는다.

| 단계 | 누가 하는가 | 결과 |
|---|---|---|
| WORK-09A | 백엔드 개발자/에이전트 | H2를 읽어 로컬 PostgreSQL에 멱등 이관하는 실행기와 품질 리포트 |
| WORK-09B | 인프라 담당 개발자 | 개발 RDS, 역할 분리, TLS, snapshot, 최초 schema/data 적재 |
| WORK-09C | 백엔드 개발자 | 이후 Flyway 변경을 CI에서 검증하고 dev 배포 시 자동 반영하는 경로 |

완료 순서는 아래 하나뿐이다.

```text
09A 코드 구현
→ 로컬 PostgreSQL dry-run/apply/validate/apply 재실행
→ 팀이 품질 리포트 승인
→ 09B 개발 RDS 생성
→ 최초 Flyway 적용
→ 동일 원본으로 데이터 이관
→ snapshot과 검증 리포트
→ 09C dev 자동 migration 연결
→ TourAPI 보강
```

절대 먼저 하지 않는 일:

- RDS를 먼저 만들고 거기서 이관 코드를 디버깅하지 않는다.
- H2 dump SQL을 PostgreSQL에 직접 실행하지 않는다.
- 엔티티만 바꾸고 `ddl-auto=update`로 RDS를 맞추지 않는다.
- PR CI가 개발/운영 RDS에 접속하게 하지 않는다.
- V1처럼 이미 공유 DB에 적용된 migration을 수정하지 않는다.
- 운영 RDS에 WORK-09 명령을 실행하지 않는다.

### 0.1 사람과 에이전트의 작업 경계

이 프로젝트에서는 사람이 AWS 계정과 비용·접근 권한을 책임지고, 데이터 이관은 에이전트가 끝까지 수행한다.

사람이 직접 하는 일은 여섯 개뿐이다.

1. AWS Budget과 결제 알림을 만든다.
2. Phase E대로 `tripin-dev-postgres` RDS와 보안 그룹을 만든다.
3. 현재 개발 PC의 공인 IP `/32`만 5432 inbound에 등록한다.
4. `tripin_admin`, `tripin_migrator`, `tripin_app` 비밀번호를 팀 비밀번호 관리자에 보관한다.
5. Git에 포함되지 않는 `config/application-secret.yaml` 또는 현재 shell 환경변수에 접속값을 넣는다.
6. 에이전트에게 아래 handoff prompt를 전달한다.

그 뒤 에이전트가 맡는 일:

- 데모 서버 중지 여부와 H2 backup/checksum 확인
- WORK-09A 이관 실행기 구현과 테스트
- 로컬 PostgreSQL dry-run/apply/validate/reapply
- 품질 리포트 분석
- 개발 RDS endpoint/database/TLS/Flyway 상태의 read-only 사전 검사
- before snapshot 존재 확인
- 개발 RDS Flyway 적용과 데이터 이관
- RDS validate/reapply, 행 수·checksum·품질 대조
- after snapshot과 논리 dump 안내/생성 가능한 범위 수행
- secret 제거 확인과 최종 보고

에이전트에게 AWS master password, application password, API key를 채팅으로 보내지 않는다. 로컬 secret 파일이나 환경변수를 준비한 뒤 “준비됐다”고만 알린다. 에이전트는 secret 값을 출력하거나 `git diff`에 노출하지 않아야 한다.

### 0.2 RDS 설정 후 그대로 전달할 에이전트 prompt

아래에서 `<H2_BACKUP_ABSOLUTE_PATH>`만 실제 경로로 바꾼다. 비밀번호나 API key는 넣지 않는다.

```text
TARGET_WORK=WORK-09A+09B

AGENTS.md와 docs/runbooks/rds-postgresql-bootstrap-and-migration.md 전체,
docs/mvp/implementation-workpack.md의 WORK-09,
.agents/skills/flyway-rds-sync/SKILL.md를 먼저 읽고 그대로 수행해라.

개발 RDS와 local secret 설정은 준비되어 있다. 운영 RDS는 범위 밖이다.
원본 H2 backup은 <H2_BACKUP_ABSOLUTE_PATH>다.

1. git status와 현재 PostgreSQL/Flyway 구성을 검사하고 사용자 변경을 보존해라.
2. 원본 H2를 절대 수정하지 말고 SHA-256과 read-only open을 확인해라.
3. 문서 계약의 demoMigration dry-run/apply/resume/validate 실행기,
   품질 리포트, quarantine, Testcontainers 테스트를 구현해라.
4. RDS에 접속하기 전 로컬 PostgreSQL에서 dry-run→apply→validate→같은 apply를
   다시 실행하고 inserted=0, unexpected updated=0을 증명해라.
5. source count, 개인정보 제외, region/FK/좌표/설명/이미지/course stop 품질을
   리포트하고 치명적 오류가 있으면 RDS를 변경하지 말고 중단해라.
6. DB_URL의 host/database가 개발 RDS와 tripin_dev인지, TLS verify-full인지,
   Flyway history와 before snapshot 준비 여부를 확인해라. secret 값은 출력하지 마라.
7. 검증한 동일 git SHA와 source checksum으로 개발 RDS에 Flyway를 적용하고,
   dry-run UUID를 확인한 다음 데이터 이관을 실행해라.
8. RDS에서 validate와 reapply를 실행하고 table count, 중복, FK, 좌표,
   추천 가능 후보 수를 로컬 결과와 대조해라.
9. after snapshot/논리 dump를 만들 수 있으면 만들고, 콘솔 동작이 필요하면
   정확히 한 단계만 나에게 요청해라.
10. compileJava test와 PostgreSQL integration test를 실행하고 결과를 보고해라.

금지: 운영 host 접속, 0.0.0.0/0 개방, H2 raw dump 실행, ddl-auto update,
기적용 Flyway 수정/repair/clean, 개인정보 이관, secret 로그/커밋,
품질 오류를 임의 데이터로 채우기.

완료 보고에는 source SHA-256, git SHA, Flyway versions, run IDs,
테이블별 source/insert/update/skip/quarantine, 두 번째 apply 결과,
추천 가능 지역/관광지 수, snapshot 이름, 남은 품질 issue를 포함해라.
```

에이전트가 AWS 콘솔을 직접 조작할 수 없는 환경이면 snapshot 생성처럼 콘솔에서만 가능한 한 단계만 사용자에게 요청한다. 나머지 구현·명령·검증은 계속 수행해야 한다.

## 1. 이번 작업의 완료 상태

아래 네 결과가 모두 있어야 완료다.

- 서울 리전의 개발용 PostgreSQL RDS가 TLS와 제한된 보안 그룹으로 실행된다.
- 빈 DB에 Flyway가 처음부터 끝까지 성공하고 JPA는 `ddl-auto=validate`로만 검증한다.
- 데모 H2의 지역·관광지·이미지·상세·공식 코스만 새 스키마에 멱등 이관된다.
- 이관 전후 수량과 품질 리포트, RDS 스냅샷, 실패 행 목록이 남는다.

사용자, 비밀번호, 세션, 후기, 개인 일정과 업로드 파일은 이번 이관 대상이 아니다.

## 2. 권장 RDS 사양

| 항목 | 개발 RDS 권장값 | 이유 |
|---|---|---|
| Region | Asia Pacific (Seoul), `ap-northeast-2` | 한국 사용자와 향후 백엔드 배포 위치에 맞춤 |
| Engine | PostgreSQL 17, 콘솔이 제공하는 최신 17.x | 호환성이 안정적이고 RDS 표준 지원이 2030-02-28까지임 |
| Template | Dev/Test | Multi-AZ 같은 운영 비용 옵션을 피함 |
| Deployment | Single DB instance, Single-AZ | 개발 DB에는 장애 조치보다 비용 절감이 우선 |
| Instance | `db.t4g.small` (2 vCPU, 2 GiB) | 세 명의 개발과 이관·보강 배치를 동시에 버티기 위한 시작점 |
| 절약안 | `db.t4g.micro` (2 vCPU, 1 GiB) | 무료/최저 비용이 최우선일 때만; 메모리 부족과 느린 migration 가능 |
| Storage | General Purpose SSD `gp3`, 20 GiB | 현재 수만 건 이하 데이터에는 충분한 최소 시작점 |
| Autoscaling | 켬, 최대 50 GiB | 로그·인덱스 증가로 디스크가 꽉 차는 사고 방지 |
| Multi-AZ/read replica | 끔 | MVP 개발 단계에는 불필요 |
| Encryption | 켬, 기본 AWS KMS key | 저장 데이터 암호화 |
| Automated backup | 7일 | 실수 복구와 point-in-time restore용 |
| Deletion protection | 켬 | 실수 삭제 방지; 정리할 때 직접 해제 |
| Auto minor upgrade | 켬 | 지원되는 보안·버그 수정 반영 |
| DB name | `tripin_dev` | 환경이 이름에 드러남 |
| DB identifier | `tripin-dev-postgres` | AWS 콘솔에서 찾기 쉬움 |
| Master username | `tripin_admin` | 애플리케이션에서는 사용하지 않을 bootstrap 계정 |

`db.t4g.small`로 시작하고 CloudWatch에서 `FreeableMemory`, `CPUUtilization`, `DatabaseConnections`, `FreeStorageSpace`를 본다. 지속적으로 여유가 크면 micro로 낮추고, 메모리가 300 MiB 아래로 자주 내려가거나 swap/지연이 생기면 medium으로 올린다. 인스턴스 크기는 나중에 변경할 수 있다.

## 3. 전체 순서

```text
데모 H2 동결·백업
  → WORK-00 PostgreSQL/Flyway 구현
  → 로컬 PostgreSQL에 빈 스키마 생성
  → H2 추출·변환·로컬 적재 2회 리허설
  → RDS 생성·접속 역할 분리
  → RDS 수동 스냅샷
  → Flyway migrate
  → 동일 이관 배치 실행
  → 행 수·품질·멱등성 검증
  → 이관 완료 스냅샷
  → TourAPI 보강·이미지 검증
```

## 4. Phase A — 비용과 계정 안전장치

1. 개인 root 계정 대신 IAM 관리 사용자로 AWS 콘솔에 로그인한다. root 계정에는 MFA를 켠다.
2. 콘솔 오른쪽 위에서 리전을 **서울**로 바꾼다.
3. AWS Billing에서 월 예산 알림을 만든다.
   - Billing and Cost Management → Budgets → Create budget
   - Cost budget, Monthly
   - 예산은 팀이 감당할 금액으로 입력
   - 50%, 80%, 100% 이메일 알림 설정
4. RDS 생성 화면 마지막의 월 예상 비용을 확인한다. 무료 플랜 여부는 계정 생성 시점과 현재 정책에 따라 달라지므로 무료라고 가정하지 않는다.

RDS를 `Stopped`로 두어도 스토리지와 백업 비용은 남고, 장기간 정지된 DB는 AWS가 다시 시작할 수 있다. 비용을 완전히 멈추려면 최종 스냅샷을 만든 뒤 인스턴스를 삭제해야 한다.

## 5. Phase B — 데모 H2 동결과 원본 보존

### 5.1 데모 서버 중지

데모 서버를 실행한 터미널에서 `Ctrl+C`를 누른다. `sumeun.mv.db`를 사용하는 Java 프로세스가 없는지 확인한다. 실행 중 파일을 단순 복사하면 일관되지 않은 백업이 될 수 있다.

### 5.2 백업과 체크섬

PowerShell에서 실행한다.

```powershell
Set-Location 'C:\Users\ysj18\Downloads\hidden-travel\hidden-travel'
New-Item -ItemType Directory -Force -Path '.\migration-backups' | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backup = ".\migration-backups\sumeun-$stamp.mv.db"
Copy-Item -LiteralPath '.\data\sumeun.mv.db' -Destination $backup
Get-FileHash -Algorithm SHA256 -LiteralPath $backup
```

체크섬 결과를 이관 리포트에 복사한다. `migration-backups/`는 Git에 올리지 않는다. 백업 파일 하나는 작업 PC 외의 접근 제한 저장소에도 보관한다.

### 5.3 이관 범위 고정

| 데모 원천 | 새 스키마 | 처리 |
|---|---|---|
| `region` | `regions`, `region_contents` | 250개 코드·이름·좌표 이관, 기존 AI 요약은 승인 콘텐츠로 승격하지 않음 |
| `attraction` | `attractions`, `attraction_images` | TourAPI ID, 유형, 설명, 주소, 좌표, 상세 필드, 이미지 이관 |
| `travel_course` | `official_courses` | TourAPI 공식 코스만 이관 |
| `course_point` | `official_course_stops` | 원래 순서와 content ID 보존 |
| `api_call_usage` | 없음 | 새 `ingestion_runs`가 대체하므로 과거 일일 카운터는 이관하지 않음 |
| 계정·후기·일정·세션 | 없음 | 개인정보/데모 사용자 데이터이므로 제외 |
| 데모 음식점·착한가격업소·특산물 | `restaurants`, `restaurant_sources`, `region_food_themes` | 원천·갱신일·지역 연결을 검증할 수 있는 행만 DRAFT/후보로 이관; 승인·추천 상태로 자동 승격 금지 |

필드 매핑의 최종 기준은 Java 엔티티가 아니라 **병합된 Flyway SQL**이다.

## 6. Phase C — WORK-09A 이관 실행기 구현

WORK-00의 PostgreSQL/Flyway 기반은 이미 구현됐다. 이제 RDS를 만들기 전에 이관 실행기를 완성한다. 구현 에이전트에는 이 문서 전체와 `TARGET_WORK=WORK-09A`를 전달한다.

### 6.1 생성해야 하는 공개 실행 명령

Gradle task 이름은 아래로 고정한다. 팀원이 구현체 내부 클래스를 몰라도 실행할 수 있어야 한다.

```powershell
# 원본과 mapping만 검사한다. 대상 DB를 변경하지 않는다.
.\gradlew.bat demoMigration --args="--mode=dry-run --source=C:/absolute/path/sumeun.mv.db"

# PostgreSQL에 batch 단위로 적재한다.
.\gradlew.bat demoMigration --args="--mode=apply --source=C:/absolute/path/sumeun.mv.db"

# 특정 실패 실행을 이어서 처리한다.
.\gradlew.bat demoMigration --args="--mode=apply --source=C:/absolute/path/sumeun.mv.db --resume-run-id=<UUID>"

# 적재 결과와 품질을 읽기 전용으로 검증한다.
.\gradlew.bat demoMigration --args="--mode=validate --run-id=<UUID>"
```

`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`는 환경변수로 받는다. 명령 인자에 비밀번호를 넣지 않는다. source는 절대 경로만 허용하고 `.mv.db` 이외 파일은 거부한다.

### 6.2 구현 구조

```text
tools/demo-migration
├─ command       인자 파싱, mode 분기, exit code
├─ source/h2     read-only H2 조회와 source DTO
├─ mapping       명시적 field 변환과 검증
├─ target/pg     PostgreSQL upsert repository
├─ quality       대조·품질 검사와 JSON/Markdown report
└─ run           batch/cursor/ingestion_runs 상태 관리
```

H2 runtime dependency는 main 애플리케이션 classpath에 넣지 않고 전용 source set/configuration에만 둔다. source connection은 `ACCESS_MODE_DATA=r` 또는 동등한 read-only 설정을 사용한다. 이관 실행기에서 JPA entity를 source schema처럼 사용하지 말고 원본 컬럼을 source DTO로 명시한다.

### 6.3 고정 이관 순서와 key

외래키 때문에 아래 순서를 지킨다.

1. `region → regions`
2. `attraction → attractions`
3. 관광지 대표/상세 이미지 → `attraction_images`
4. `travel_course → official_courses`
5. `course_point → official_course_stops`

멱등 key:

- region: `sig_cd`
- attraction: `(source_system='TOUR_API', source_content_id)`
- official course: `(source_system='TOUR_API', source_content_id)`
- image: `(attraction_id, image_url)`
- stop: `(official_course_id, stop_order)`

성공적으로 적재된 nonblank 값을 이후 빈 source 값으로 덮어쓰지 않는다. source key가 없거나 region을 찾지 못하는 행은 억지로 insert하지 않고 `data_quality_issues`와 quarantine report에 남긴다.

### 6.4 batch, 재시작, exit code

- 기본 batch는 200행이며 한 batch가 한 transaction이다.
- commit 후에만 `ingestion_runs.cursor_value`를 갱신한다.
- 한 행의 mapping 오류는 quarantine하고 다음 행을 진행한다.
- DB 연결, schema 불일치, checksum 불일치는 즉시 중단한다.
- 동일 `--resume-run-id`는 source checksum과 application version이 같을 때만 허용한다.
- dry-run은 대상 DB에 `ingestion_runs`조차 쓰지 않는다.

| Exit code | 의미 |
|---:|---|
| 0 | 요청한 mode 성공 |
| 2 | CLI 인자/환경변수 오류 |
| 3 | source 파일/checksum/schema 오류 |
| 4 | target 연결/Flyway version 오류 |
| 5 | 일부 행 quarantine으로 PARTIAL |
| 6 | 실행 실패, 안전하게 재개 가능 |

### 6.5 리포트 계약

`build/reports/demo-migration/<run-id>/`에 다음을 만든다.

- `manifest.json`: source SHA-256, git SHA, 시작/종료, mode, batch size
- `counts.json`: source/insert/update/skip/quarantine 수
- `quality.json`: 설명·좌표·VALID 이미지 교집합과 지역별 후보 수
- `quarantine.csv`: entity type, source key, error code. 자유서술 전문과 개인정보는 넣지 않음
- `summary.md`: 사람이 리뷰하는 요약

수량식은 각 entity마다 `source = inserted + updated + skipped + quarantined`여야 한다. apply를 같은 원본으로 다시 실행했을 때 `inserted=0`, 내용 변화가 없으면 `updated=0`이어야 한다.

### 6.6 구현 필수 테스트

- 실제 구조를 축소한 H2 fixture → PostgreSQL 17 Testcontainers 전체 이관
- dry-run 전후 target checksum 동일
- 두 번째 apply 결과 불변
- batch 중간 실패 후 resume
- 알 수 없는 region, 중복 source ID, 빈 key quarantine
- 성공 description/image를 빈 source가 덮어쓰지 않음
- 사용자·비밀번호·refresh/session/review/trip 데이터가 target에 들어오지 않음
- production처럼 보이는 host 또는 `tripin_prod` DB는 명시적 allowlist가 없으면 거부

### 6.7 WORK-09A 완료 gate

- [ ] 위 네 Gradle 명령이 `--help`와 함께 동작한다.
- [ ] 로컬 PostgreSQL에서 dry-run/apply/validate/reapply를 완료했다.
- [ ] 모든 수량식이 맞고 quarantine 사유를 설명할 수 있다.
- [ ] PostgreSQL 17 Testcontainers 테스트가 CI에서 통과한다.
- [ ] RDS endpoint를 한 번도 사용하지 않고 09A를 완료했다.

## 7. Phase D — 로컬 PostgreSQL 리허설

Docker Desktop을 켜고 PowerShell에서 실행한다. 현재 Flyway는 Spring Boot가 기동되면서 먼저 적용되고, 그다음 Hibernate가 `validate`한다.

```powershell
Set-Location 'C:\Users\ysj18\Downloads\hidden-travel\backend'
docker compose up -d postgres
.\gradlew.bat compileJava test --no-daemon
.\gradlew.bat bootRun --args='--spring.profiles.active=local'
```

로그에서 Flyway V1 성공과 `Started BackendApplication`을 확인한 후 `Ctrl+C`로 서버를 멈춘다. 별도의 `flywayMigrate` task는 WORK-09C가 만들기 전까지 사용하지 않는다.

그 후 고정한 H2 백업을 source로 이관 도구를 실행한다.

```powershell
$env:DB_URL='jdbc:postgresql://localhost:5432/tripin_local?currentSchema=app'
$env:DB_USERNAME='tripin_local'
$env:DB_PASSWORD='tripin_local_dev_only'
$source='C:/absolute/path/migration-backups/sumeun-YYYYMMDD-HHMMSS.mv.db'

.\gradlew.bat demoMigration --args="--mode=dry-run --source=$source"
.\gradlew.bat demoMigration --args="--mode=apply --source=$source"
# 위 출력의 runId를 복사한다.
.\gradlew.bat demoMigration --args="--mode=validate --run-id=<RUN_ID>"
.\gradlew.bat demoMigration --args="--mode=apply --source=$source"
```

마지막 실행이 `inserted=0, updated=0`인지 확인한다. 확인 후 현재 PowerShell의 비밀값을 지운다.

```powershell
Remove-Item Env:DB_URL,Env:DB_USERNAME,Env:DB_PASSWORD -ErrorAction SilentlyContinue
```

다음을 반드시 리포트한다.

- H2 백업 경로 대신 파일 SHA-256
- 원천/대상 테이블별 행 수
- 중복된 `source_content_id`
- 유효하지 않은 SIG_CD와 고아 레코드
- 한국 범위를 벗어난 위도/경도
- 설명, 이미지, 좌표의 개별 보유 수와 세 필드 교집합
- 이미지 URL 검증 성공/실패/미검증 수
- 공식 코스와 stop 수, 관광지로 매핑된 stop 수
- 추천 가능 관광지 수와 지역별 분포
- 제외/격리된 행과 사유

## 8. Phase E — AWS 콘솔에서 개발 RDS 만들기

### 8.1 DB 생성 화면

1. [AWS RDS Console](https://console.aws.amazon.com/rds/)을 연다.
2. 오른쪽 위 리전이 **Asia Pacific (Seoul)**인지 확인한다.
3. 왼쪽 **Databases** → **Create database**를 누른다.
4. **Standard create**를 고른다. Easy create는 네트워크와 백업을 세밀하게 확인하기 어렵다.
5. Engine options:
   - Engine type: **PostgreSQL**
   - Engine version: 콘솔이 허용하는 최신 **17.x**
   - RDS Extended Support: 현재 17에는 필요 없음
6. Templates: **Dev/Test**
7. Availability and durability: **Single DB instance** 또는 **Single-AZ**
8. Settings:
   - DB instance identifier: `tripin-dev-postgres`
   - Master username: `tripin_admin`
   - Credentials management: AWS Secrets Manager를 팀이 이미 쓰면 선택한다. 아니라면 강한 임의 비밀번호를 생성해 팀 비밀번호 관리자에 저장한다.
   - 비밀번호를 채팅, Notion 공개 문서, Git, 명령 인자에 넣지 않는다.
9. Instance configuration:
   - Burstable classes
   - `db.t4g.small`
   - 비용이 최우선이고 무료 조건이 맞을 때만 `db.t4g.micro`
10. Storage:
    - General Purpose SSD (`gp3`)
    - Allocated storage: `20 GiB`
    - Storage autoscaling: 켬
    - Maximum storage threshold: `50 GiB`

### 8.2 연결 방식 선택

가장 안전한 최종 형태는 private RDS와 같은 VPC의 백엔드다. 하지만 지금은 개발자 PC에서 Flyway와 이관 배치를 실행해야 하고 VPN/bastion이 없으므로, **개발 RDS에 한해서만 임시 public access + 각 개발자 공인 IP `/32` 제한**을 사용한다.

1. Connectivity:
   - Compute resource: **Don't connect to an EC2 compute resource**
   - Network type: IPv4
   - VPC: 우선 default VPC
   - DB subnet group: default VPC의 subnet group
   - Public access: **Yes (개발 DB 한정)**
   - VPC security group: **Create new**
   - New VPC security group name: `tripin-dev-rds-sg`
   - Port: `5432`
2. 생성 직후 EC2 → Security Groups → `tripin-dev-rds-sg` → Inbound rules를 연다.
3. PostgreSQL/TCP 5432 규칙의 Source를 **My IP**, 즉 현재 공인 IP `/32`로 제한한다.
4. 팀원은 각자 자신의 `/32`만 추가한다.

절대로 `0.0.0.0/0` 또는 `::/0`로 5432를 열지 않는다. 집/카페 IP가 바뀌면 기존 규칙을 지우고 새 `/32`로 교체한다. 백엔드가 AWS에 배포되면 RDS를 private로 전환하고, 백엔드 보안 그룹을 source로 허용한다.

### 8.3 나머지 옵션

- Initial database name: `tripin_dev`
- DB parameter group: PostgreSQL 17 기본값. 15 이상은 RDS의 `rds.force_ssl` 기본값이 켜져 있지만 클라이언트도 `verify-full`로 검증한다.
- Backup retention: `7 days`
- Backup window: 팀이 거의 작업하지 않는 KST 새벽 시간
- Maintenance window: backup window와 겹치지 않는 KST 새벽 시간
- Log exports: PostgreSQL log와 upgrade log 활성화
- Performance/Database Insights: 기본 무료 범위로 시작
- Enhanced monitoring: 초기에는 끔; 장애 조사 때 켬
- Encryption: 켬
- Auto minor version upgrade: 켬
- Deletion protection: 켬

마지막 예상 비용을 확인하고 **Create database**를 누른다. 상태가 `Available`이 될 때까지 기다린다.

## 9. Phase F — TLS 접속과 DB 역할 분리

### 9.1 Endpoint 기록

RDS → Databases → `tripin-dev-postgres` → Connectivity & security에서 다음을 기록한다.

- Endpoint: 예시 `tripin-dev-postgres.xxxxxx.ap-northeast-2.rds.amazonaws.com`
- Port: `5432`
- DB name: `tripin_dev`

Endpoint는 비밀번호가 아니지만 환경별 설정으로 관리한다.

### 9.2 RDS CA bundle 받기

AWS 공식 global bundle을 프로젝트 밖 로컬 보안 폴더에 저장한다. 인증서는 공개 자료이지만 경로가 팀마다 다르므로 저장소에는 개인 절대경로를 커밋하지 않는다.

```powershell
New-Item -ItemType Directory -Force -Path "$env:USERPROFILE\.aws\rds" | Out-Null
Invoke-WebRequest `
  -Uri 'https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem' `
  -OutFile "$env:USERPROFILE\.aws\rds\global-bundle.pem"
```

PostgreSQL client의 `psql`이 없다면 PostgreSQL 17 client를 설치한 뒤 새 터미널을 연다.

### 9.3 master 계정으로 첫 접속

아래 `<RDS_ENDPOINT>`만 실제 값으로 바꾼다. 비밀번호는 명령에 넣지 말고 프롬프트에 입력한다.

```powershell
psql "host=<RDS_ENDPOINT> port=5432 dbname=tripin_dev user=tripin_admin sslmode=verify-full sslrootcert=$env:USERPROFILE/.aws/rds/global-bundle.pem"
```

접속 후 확인한다.

```sql
SELECT version();
SELECT current_database(), current_user;
SELECT ssl, version, cipher FROM pg_stat_ssl WHERE pid = pg_backend_pid();
```

`ssl`이 `true`여야 한다.

### 9.4 애플리케이션과 migration 역할 생성

master는 일상 실행에 사용하지 않는다. `psql` 안에서 실행한다.

```sql
CREATE ROLE tripin_migrator LOGIN;
\password tripin_migrator

CREATE ROLE tripin_app LOGIN;
\password tripin_app

GRANT CONNECT ON DATABASE tripin_dev TO tripin_migrator, tripin_app;
CREATE SCHEMA IF NOT EXISTS app AUTHORIZATION tripin_migrator;
GRANT USAGE ON SCHEMA app TO tripin_app;

ALTER ROLE tripin_migrator IN DATABASE tripin_dev SET search_path TO app, public;
ALTER ROLE tripin_app IN DATABASE tripin_dev SET search_path TO app, public;

ALTER DEFAULT PRIVILEGES FOR ROLE tripin_migrator IN SCHEMA app
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO tripin_app;
ALTER DEFAULT PRIVILEGES FOR ROLE tripin_migrator IN SCHEMA app
  GRANT USAGE, SELECT ON SEQUENCES TO tripin_app;
```

각 `\password`는 입력을 화면에 표시하지 않는다. 서로 다른 강한 비밀번호를 사용한다.

권한 원칙은 다음과 같다.

- `tripin_admin`: role/schema bootstrap, 비상 복구에만 사용
- `tripin_migrator`: Flyway DDL과 이관 배치에 사용
- `tripin_app`: 실행 중 CRUD만 사용, DDL 권한 없음

## 10. Phase G — RDS에 Flyway와 데이터 적용

### 10.1 빈 DB 스냅샷

RDS 콘솔에서 `tripin-dev-postgres` 선택 → Actions → **Take snapshot**:

```text
tripin-dev-before-initial-migration-YYYYMMDD
```

### 10.2 비밀 설정

실제 backend 설정은 WORK-00의 `dev-rds` profile 문서를 따른다. 다음 값은 Git에 커밋하지 않는 `config/application-secret.yaml` 또는 CI secret으로만 넣는다.

```yaml
spring:
  datasource:
    url: jdbc:postgresql://<RDS_ENDPOINT>:5432/tripin_dev?currentSchema=app&sslmode=verify-full&sslrootcert=<ABSOLUTE_CA_PATH>
    username: tripin_app
    password: <APP_PASSWORD>
  flyway:
    user: tripin_migrator
    password: <MIGRATOR_PASSWORD>
    schemas: app
```

Windows 경로는 `/`를 사용하거나 URL encoding한다. `application-secret.yaml`이 `.gitignore`인지 `git check-ignore -v config/application-secret.yaml`로 확인한다.

### 10.3 schema migration

최초 1회는 현재 구현된 Spring Boot Flyway 경로를 사용한다. 별도 migration task가 구현되기 전에는 존재하지 않는 `flywayMigrate`를 실행하지 않는다.

```powershell
Set-Location 'C:\Users\ysj18\Downloads\hidden-travel\backend'
$env:SPRING_PROFILES_ACTIVE='dev-rds'
$env:DB_URL='jdbc:postgresql://<RDS_ENDPOINT>:5432/tripin_dev?currentSchema=app&sslmode=verify-full&sslrootcert=C:/Users/<YOU>/.aws/rds/global-bundle.pem'
$env:DB_USERNAME='tripin_app'
$env:DB_PASSWORD='<APP_PASSWORD>'
$env:FLYWAY_USER='tripin_migrator'
$env:FLYWAY_PASSWORD='<MIGRATOR_PASSWORD>'
$env:JWT_SECRET='<AT_LEAST_32_RANDOM_BYTES>'
.\gradlew.bat bootRun
```

Flyway 성공, Hibernate validate 성공, `Started BackendApplication`을 확인한 뒤 `Ctrl+C`로 종료한다. 이어서 `psql`에서 확인한다.

```sql
SELECT installed_rank, version, description, type, success, installed_on
FROM app.flyway_schema_history
ORDER BY installed_rank;
```

V1이 `success=true`여야 한다. 같은 서버를 다시 기동했을 때 새 migration 없이 Flyway가 아무 SQL도 적용하지 않아야 한다. `flyway_schema_history`를 직접 수정하지 않는다.

현재 shell의 secret은 작업 후 제거한다.

```powershell
Remove-Item Env:SPRING_PROFILES_ACTIVE,Env:DB_URL,Env:DB_USERNAME,Env:DB_PASSWORD,Env:FLYWAY_USER,Env:FLYWAY_PASSWORD,Env:JWT_SECRET -ErrorAction SilentlyContinue
```

### 10.4 데이터 이관

로컬에서 검증한 **동일한 H2 백업 SHA-256과 동일한 이관 도구 버전**을 사용한다.

1. RDS 대상으로 `--dry-run` 실행
2. source count가 로컬 리허설과 같은지 확인
3. `--apply` 실행
4. 실패 시 성공 batch는 유지하고 cursor부터 재개
5. `--validate`/`--report` 실행
6. 같은 `--apply`를 다시 실행해 insert/update가 예상대로 0인지 확인

RDS에 H2 dump SQL을 직접 실행하거나 JPA `ddl-auto=update`로 구조를 맞추지 않는다. H2와 PostgreSQL의 타입, identity, 예약어, boolean 문법이 달라 안전하지 않다.

### 10.5 RDS 이관 실행의 안전장치

이관 실행기는 아래 값이 모두 일치할 때만 RDS apply를 허용한다.

```text
MIGRATION_TARGET_ENV=dev
MIGRATION_ALLOWED_DB=tripin_dev
MIGRATION_CONFIRM_RUN_ID=<dry-run에서 받은 UUID>
현재 DB 이름 = tripin_dev
현재 host suffix = .rds.amazonaws.com
현재 Flyway schema가 최신 성공 상태
source checksum = 승인된 로컬 리허설 checksum
```

하나라도 다르면 exit code 4로 종료한다. `prod`, `production`, `tripin_prod`가 host/database/profile에 포함되면 우회 flag 없이 무조건 거부한다.

## 11. Phase H — 이후 schema 변경을 dev RDS에 자동 반영

### 11.1 자동화의 정확한 의미

엔티티를 저장하면 RDS가 자동으로 바뀌는 구조가 아니다. 개발자가 **새 Flyway SQL을 작성하고 PR을 병합하면**, 검증된 backend가 dev 환경에 배포될 때 Spring Boot가 pending migration만 적용한다.

```text
Entity/Repository 변경
        ↓
V{N}__description.sql 추가
        ↓
PR: PostgreSQL 17 Testcontainers에서 V1→VN + JPA validate
        ↓ merge
dev backend 배포/재시작
        ↓
Flyway가 tripin_migrator로 pending migration 적용
        ↓
Hikari/JPA는 tripin_app으로 연결하고 ddl-auto=validate
        ↓
health check 성공 후 dev 배포 완료
```

PR CI는 외부 RDS를 변경하지 않는다. 운영 환경은 자동 대상이 아니며 별도 승인·snapshot·migration job이 필요하다.

### 11.2 WORK-09C에서 구현할 migration 전용 명령

애플리케이션 전체를 띄우지 않고 `info → validate → migrate → info`를 수행하는 `rdsMigrate` Gradle task를 추가한다. Flyway Gradle plugin 또는 전용 JavaExec 중 하나로 구현하되 공개 인터페이스는 아래로 고정한다.

```powershell
.\gradlew.bat rdsMigrationInfo
.\gradlew.bat rdsMigrationValidate
.\gradlew.bat rdsMigrate
```

필수 환경변수:

| 변수 | 예시/의미 |
|---|---|
| `MIGRATION_TARGET_ENV` | `dev`; 그 외 기본 거부 |
| `DB_URL` | `tripin_dev`와 `sslmode=verify-full` 포함 |
| `FLYWAY_USER` | `tripin_migrator` |
| `FLYWAY_PASSWORD` | secret store에서 주입 |
| `RDS_CA_PATH` | AWS RDS CA bundle 절대 경로 |

명령은 시작할 때 secret을 제외한 target host/database, 현재 version, pending version을 보여주고, 끝날 때 적용 version과 소요시간을 출력한다. `clean`, `repair`, `baselineOnMigrate=true`, out-of-order 적용은 제공하지 않는다.

### 11.3 migration 파일 작성 규칙

1. main 최신 상태에서 현재 최대 version을 확인한다.

   ```powershell
   Get-ChildItem src/main/resources/db/migration | Sort-Object Name
   ```

2. 팀 채널/PR에서 다음 번호를 예약한다. 동시에 같은 번호를 만들지 않는다.
3. `V2__add_refresh_rotation.sql`처럼 ASCII snake_case 이름으로 새 파일을 만든다.
4. 이전 migration은 수정·삭제·재번호화하지 않는다.
5. entity 변경과 SQL을 같은 PR에 넣는다.
6. 큰 table 변경은 nullable column 추가 → backfill → constraint 강화의 여러 migration으로 나눈다.
7. data backfill은 결정적이고 재현 가능해야 하며 외부 API를 migration 안에서 호출하지 않는다.
8. PostgreSQL transaction에서 실행할 수 없는 DDL은 별도 migration과 runbook을 작성한다.

예시:

```sql
-- V2__add_refresh_rotation.sql
ALTER TABLE app.refresh_tokens
    ADD COLUMN family_id UUID,
    ADD COLUMN revoked_at TIMESTAMP WITHOUT TIME ZONE,
    ADD COLUMN replaced_by_token_id BIGINT;

UPDATE app.refresh_tokens
SET family_id = gen_random_uuid()
WHERE family_id IS NULL;

ALTER TABLE app.refresh_tokens
    ALTER COLUMN family_id SET NOT NULL,
    ADD CONSTRAINT fk_refresh_replaced_by
        FOREIGN KEY (replaced_by_token_id)
        REFERENCES app.refresh_tokens(id)
        ON DELETE SET NULL;

CREATE INDEX idx_refresh_tokens_family_active
    ON app.refresh_tokens(family_id)
    WHERE revoked_at IS NULL;
```

실제 적용 전 extension 사용 가능 여부와 기존 데이터 호환성을 확인한다. 예시 SQL을 그대로 복사하는 것이 아니라 WORK별 확정 schema에 맞춘다.

### 11.4 PR에서 자동 검증할 항목

`.github/workflows/ci.yml`은 RDS 대신 PostgreSQL 17 Testcontainers에서 다음을 검증한다.

- 빈 DB에 V1부터 최신까지 migration
- 직전 release schema에서 최신까지 upgrade
- migration naming/checksum validation
- 두 번째 migrate가 no-op
- Hibernate `ddl-auto=validate`
- 새 NOT NULL/UNIQUE/FK/CHECK/index의 성공·실패 경계
- 이미 적용된 migration 파일 변경 탐지

PR에 migration이 있는데 entity/test/docs가 없거나, entity schema가 바뀌었는데 migration이 없으면 CI를 실패시킨다. GitHub branch protection에서 이 job을 required check로 지정한다.

### 11.5 dev 자동 적용 방식

MVP에서는 **backend dev 배포 시 Spring Boot Flyway 자동 적용**을 사용한다. `application.yml`의 `spring.flyway.enabled=true`, `ddl-auto=validate`, `application-dev-rds.yml`의 별도 `FLYWAY_USER`가 이미 이 모델의 기반이다.

배포 환경에는 아래를 secret으로 주입한다.

```text
SPRING_PROFILES_ACTIVE=dev-rds
DB_URL=jdbc:postgresql://.../tripin_dev?currentSchema=app&sslmode=verify-full&sslrootcert=...
DB_USERNAME=tripin_app
DB_PASSWORD=...
FLYWAY_USER=tripin_migrator
FLYWAY_PASSWORD=...
JWT_SECRET=...
```

애플리케이션 시작 순서는 Flyway migrate → Hibernate validate → HTTP ready다. migration 또는 validate가 실패하면 새 instance를 ready 상태로 만들지 않는다. 배포 플랫폼 health check가 실패해야 하며 기존 정상 instance는 유지한다.

dev RDS가 public인 동안에도 GitHub-hosted runner가 RDS에 직접 접속하도록 `0.0.0.0/0`를 열지 않는다. backend가 AWS에 배포되면 RDS는 private으로 바꾸고 backend security group만 5432 source로 허용한다. 별도 migration job이 필요해지면 RDS와 같은 VPC의 CodeBuild/ECS task를 사용한다.

### 11.6 첫 자동 migration 리허설

1. dev RDS 수동 snapshot을 만든다.
2. 무해한 테스트 migration이 아니라 다음 실제 기능 migration을 로컬/Testcontainers에서 검증한다.
3. dev 배포를 한 번 실행한다.
4. 배포 로그에서 적용 version을 확인한다.
5. `app.flyway_schema_history`와 새 column/index/constraint를 조회한다.
6. 서버를 다시 배포해 migration이 no-op인지 확인한다.
7. `tripin_app`으로 DDL이 거부되는지 재확인한다.

### 11.7 실패와 복구

| 실패 | 조치 |
|---|---|
| migration 시작 전 연결 실패 | 보안 그룹, TLS, secret을 고치고 재배포 |
| transactional migration 실패 | Flyway가 rollback했는지 확인하고 새 수정 migration을 작성 |
| migration 성공 후 JPA validate 실패 | 앱 배포를 중단하고 entity/SQL 불일치를 새 PR로 수정 |
| migration 성공 후 기능 장애 | 앱 코드만 이전 버전으로 rollback. schema는 forward-compatible하게 유지 |
| 대량 data 변경 오류 | 배치를 중지하고 snapshot을 새 instance로 복원해 대조 |
| checksum mismatch | 적용된 파일을 고치지 말고 원본 복원 후 새 version으로 보정 |

일반 배포 rollback이 schema downgrade를 뜻하지 않는다. 따라서 column/table 삭제와 rename은 최소 두 번의 배포로 나눈다. 먼저 신·구 코드가 같이 동작하도록 추가하고, 모든 instance 전환 뒤 별도 migration에서 오래된 구조를 제거한다.

### 11.8 자동화 완료 gate

- [ ] migration 없는 entity schema 변경을 CI가 잡는다.
- [ ] 모든 PR은 PostgreSQL 17에서 clean/upgrade/no-op을 검증한다.
- [ ] merge 후 dev 배포에서 pending migration이 한 번만 적용된다.
- [ ] migration 실패 시 새 instance가 ready가 되지 않는다.
- [ ] `tripin_app`은 DDL 권한이 없다.
- [ ] GitHub runner를 위해 RDS port를 전 세계에 열지 않았다.
- [ ] production은 자동 migration 대상이 아니다.

## 12. Phase I — 검증과 롤백

### 12.1 최소 SQL 검증

실제 테이블/컬럼명은 병합된 V1을 기준으로 조정한다.

```sql
SELECT version, description, success
FROM app.flyway_schema_history
ORDER BY installed_rank;

SELECT COUNT(*) FROM app.regions;
SELECT COUNT(*) FROM app.attractions;
SELECT COUNT(*) FROM app.attraction_images;
SELECT COUNT(*) FROM app.official_courses;
SELECT COUNT(*) FROM app.official_course_stops;

SELECT source_system, source_content_id, COUNT(*)
FROM app.attractions
GROUP BY source_system, source_content_id
HAVING COUNT(*) > 1;

SELECT COUNT(*)
FROM app.attractions
WHERE latitude NOT BETWEEN 33 AND 39
   OR longitude NOT BETWEEN 124 AND 132;
```

마지막 두 쿼리는 0행/0건이어야 한다. 지역 250건은 원천 실측과 일치해야 한다. 관광지와 코스 수는 필터·격리 때문에 원천보다 작을 수 있으나 차이는 모두 리포트 사유 합계와 맞아야 한다.

### 12.2 애플리케이션 검증

- `tripin_app` 계정으로 서버가 기동된다.
- `ddl-auto=validate`가 성공한다.
- `tripin_app`으로 `CREATE TABLE`을 시도하면 권한 오류가 난다.
- 지역/관광지 read API가 샘플 데이터를 반환한다.
- 빈 설명/이미지/좌표 관광지는 추천 후보에서 빠진다.

### 12.3 완료 스냅샷과 논리 백업

RDS 수동 스냅샷을 만든다.

```text
tripin-dev-after-demo-import-YYYYMMDD
```

추가로 schema/data 논리 백업을 만들 수 있다. 비밀번호는 프롬프트에 입력한다.

```powershell
pg_dump `
  "host=<RDS_ENDPOINT> port=5432 dbname=tripin_dev user=tripin_migrator sslmode=verify-full sslrootcert=$env:USERPROFILE/.aws/rds/global-bundle.pem" `
  --format=custom `
  --schema=app `
  --file="tripin-dev-after-demo-import.dump"
```

dump에는 데이터가 들어 있으므로 Git에 올리지 않고 접근 제한 저장소에 보관한다.

### 12.4 실패 시 롤백 기준

- Flyway가 빈 DB에서 실패: 데이터를 넣지 말고 V migration을 수정한다. 아직 공유 적용 전인 V1만 수정 가능하다.
- 일부 데이터 batch 실패: 스키마를 되돌리지 말고 실패 행을 격리한 뒤 cursor부터 재개한다.
- 잘못된 대량 update/delete: 즉시 배치를 중지하고 before-import snapshot으로 **새 RDS 인스턴스**를 복원해 대조한다.
- 공유된 Flyway migration 오류: 적용된 migration 파일을 고치지 않고 다음 version의 보정 migration을 추가한다.

## 13. Phase J — TourAPI 상세·이미지 보강

기본 이관과 스냅샷이 끝난 다음 `.agents/skills/tourapi-detail-backfill/SKILL.md`를 사용한다.

### 13.1 원칙

- 이관된 description/image/detail을 먼저 재사용한다.
- `source_content_id`가 있고 필요한 필드가 비어 있는 행만 호출한다.
- API key는 각 개발자의 `TOUR_API_KEY` secret으로만 넣고 공유하거나 합산해 쿼터를 우회하지 않는다.
- 작은 dry-run과 10~20건 canary 이후 batch 크기를 올린다.
- 429/일일 한도 도달 시 즉시 멈추고 cursor를 저장한다.
- 원천 응답이 빈 값이면 `SOURCE_EMPTY`로 기록하고 반복 호출하지 않는다.
- 성공 필드를 빈 응답으로 덮어쓰지 않는다.

### 13.2 보강 순서

```text
detailCommon: overview/homepage/대표 이미지 확인
  → contentType별 detailIntro: 이용시간/휴무/주차/안내
  → 필요 시 detailImage: 검증 가능한 이미지 후보
  → 음식점 contentTypeId=39: 대표메뉴/취급메뉴/영업시간/주차/이미지
  → 농가맛집·모범/향토음식점·착한가격업소 source adapter
  → 지역 음식·특산물 DRAFT와 근거 출처 생성
  → URL 검증
  → 추천 가능 상태 재계산
  → ingestion_runs/data_quality_issues 리포트
```

이미지가 없거나 설명·좌표가 없으면 MVP 추천 대상에서는 제외하되 원천 행은 삭제하지 않는다. 나중에 데이터가 보강되면 자동으로 다시 적격 판정을 받을 수 있어야 한다.

### 13.3 지역 소개 콘텐츠

TourAPI에는 시군구 단위 감성 소개문이 없다. 관광지 보강 후 다음 별도 배치를 수행한다.

1. 지역명, 행정구역, 검증된 역사/특성 사실, 대표 관광지와 출처를 묶는다.
2. LLM은 근거 안에서만 2~4문단 초안을 생성한다.
3. `region_contents.status=DRAFT`로 저장한다.
4. 사람이 사실·출처·문체와 hero image를 검토한다.
5. `APPROVED`가 된 콘텐츠만 지역 카드와 추첨에 사용한다.

AI 초안을 곧바로 `APPROVED`로 넣지 않는다.

## 14. 운영 체크리스트

### 생성 전

- [ ] AWS Budget 알림을 만들었다.
- [ ] 데모 서버를 중지하고 H2 백업과 SHA-256을 만들었다.
- [ ] WORK-00이 병합되고 local/Testcontainers가 통과했다.
- [ ] 로컬 이관을 두 번 실행해 멱등성을 확인했다.

### RDS 생성

- [ ] 서울 리전, PostgreSQL 17.x, Single-AZ, `db.t4g.small`, gp3 20 GiB다.
- [ ] 암호화, 7일 백업, 삭제 방지, storage autoscaling이 켜져 있다.
- [ ] public 개발 접속은 팀원 IP `/32`만 허용한다.
- [ ] TLS `verify-full` 접속에서 `pg_stat_ssl.ssl=true`다.
- [ ] admin/migrator/app 계정을 분리했다.

### 이관

- [ ] before snapshot을 만들었다.
- [ ] Flyway validate/migrate/info가 성공했다.
- [ ] 동일 H2 checksum과 이관 도구 버전을 사용했다.
- [ ] source/target/격리 수량 합계가 맞는다.
- [ ] 두 번째 apply에서 예상하지 않은 변경이 0이다.
- [ ] 추천 가능 지역/관광지 품질 리포트를 보관했다.
- [ ] after snapshot과 논리 dump를 만들었다.

### 보강

- [ ] 기존 상세를 재사용하고 누락 필드만 호출한다.
- [ ] canary 후 batch를 실행한다.
- [ ] 쿼터/cursor/성공/빈 응답/실패가 기록된다.
- [ ] 이미지 검증 후 추천 가능 상태를 재계산한다.
- [ ] 지역 소개는 DRAFT→사람 승인 절차를 거친다.

## 15. 자주 막히는 문제

### `connection timed out`

- RDS 상태가 `Available`인지 확인한다.
- endpoint/port/서울 리전을 확인한다.
- security group inbound가 현재 공인 IP `/32`인지 확인한다.
- public access를 선택했다면 subnet route에 internet gateway가 있는지 확인한다.
- 학교/회사망이 5432 outbound를 막으면 다른 네트워크나 private 접속 구성을 쓴다.

### `no pg_hba.conf entry ... no encryption`

RDS PostgreSQL 15+는 기본적으로 TLS를 요구한다. JDBC/psql에 `sslmode=verify-full`과 CA bundle을 지정한다.

### 인증서 hostname/chain 오류

- RDS endpoint DNS를 사용하고 IP 주소로 접속하지 않는다.
- AWS global CA bundle 경로가 실제 파일인지 확인한다.
- Windows 경로의 `\` 문제를 피하려면 `/` 또는 URL encoding을 사용한다.

### Flyway permission denied

- Flyway가 `tripin_app`이 아니라 `tripin_migrator`로 연결되는지 확인한다.
- `app` schema owner가 `tripin_migrator`인지 확인한다.
- migration에 `public` schema를 암묵적으로 가정한 SQL이 없는지 확인한다.

### JPA가 테이블을 바꿈

모든 PostgreSQL profile의 `ddl-auto`를 `validate`로 바꾼다. `update`, `create`, `create-drop`은 사용하지 않는다.

### H2 dump SQL이 PostgreSQL에서 실패

정상이다. raw dump를 넣는 방식이 아니라 이관 도구가 명시적으로 읽고 변환해야 한다. identity, boolean, CLOB/TEXT, 예약어와 제약이 DB마다 다르다.

## 16. 공식 참고

- [RDS DB instance 생성](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_CreateDBInstance.html)
- [Public/private access 선택](https://docs.aws.amazon.com/AmazonRDS/latest/gettingstartedguide/security-public-private.html)
- [RDS PostgreSQL TLS 연결](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/PostgreSQL.Concepts.General.SSL.html)
- [RDS CA certificate bundle](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/UsingWithRDS.SSL.html)
- [PostgreSQL 지원 일정](https://docs.aws.amazon.com/AmazonRDS/latest/PostgreSQLReleaseNotes/postgresql-release-calendar.html)
- [DB instance class 사양](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Concepts.DBInstanceClass.Summary.html)
- [RDS storage](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/CHAP_Storage.html)
- [자동 백업 보존 기간](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_WorkingWithAutomatedBackups.BackupRetention.html)
- [CodeBuild의 VPC 접근](https://docs.aws.amazon.com/codebuild/latest/userguide/enabling-vpc-access-in-projects.html)
- [GitHub Actions의 AWS OIDC 인증](https://docs.github.com/en/actions/how-tos/secure-your-work/security-harden-deployments/oidc-in-aws)

## Related

- [MVP 데이터 이관·추천 설계](../mvp/data-and-recommendation.md)
- [초기 세팅과 팀 실행 순서](../mvp/delivery.md)
- [MVP 구현 작업서 WORK-00/09](../mvp/implementation-workpack.md)
- [Flyway/RDS 저장소 스킬](../../.agents/skills/flyway-rds-sync/SKILL.md)
- [TourAPI 보강 저장소 스킬](../../.agents/skills/tourapi-detail-backfill/SKILL.md)
