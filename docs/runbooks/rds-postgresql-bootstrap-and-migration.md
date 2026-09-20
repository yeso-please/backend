# 개발 RDS PostgreSQL 생성·데모 데이터 이관 런북

이 문서는 AWS RDS를 처음 사용하는 개발자가 TriPin 개발 DB를 만들고, 데모 H2의 비개인 데이터를 새 Flyway 스키마로 안전하게 옮긴 뒤 TourAPI로 보강하는 순서다.

> **현재 상태:** WORK-00 구현으로 PostgreSQL/Flyway V1과 `ddl-auto=validate`가 준비됐다. 브랜치의 CI가 통과하고 팀 리뷰로 V1을 승인한 다음 로컬 이관 리허설을 시작한다. JPA가 임의로 만든 테이블을 기준 스키마로 삼지 않는다.

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
| 음식점·착한가격업소·특산물 | MVP core 아님 | 이번 1차 이관에서 제외하고 별도 결정 후 추가 |

필드 매핑의 최종 기준은 Java 엔티티가 아니라 **병합된 Flyway SQL**이다.

## 6. Phase C — WORK-00을 먼저 구현

backend에서 다음이 생기기 전에는 Phase D 이후로 넘어가지 않는다.

- PostgreSQL JDBC driver
- Flyway core와 PostgreSQL 지원 모듈
- 로컬 PostgreSQL Docker Compose
- `src/main/resources/db/migration/V1__baseline.sql`
- local/test/dev-rds profile 분리
- 모든 환경의 `spring.jpa.hibernate.ddl-auto=validate`
- Testcontainers PostgreSQL 통합 테스트
- H2를 읽고 새 스키마로 변환하는 재시작 가능 이관 도구

에이전트에는 다음 프롬프트를 그대로 전달할 수 있다.

```text
docs/mvp/README.md, decisions.md, data-and-recommendation.md,
implementation-workpack.md의 WORK-00/09와
.agents/skills/flyway-rds-sync/SKILL.md를 먼저 읽어라.

현재 backend의 SQLite + ddl-auto=update를 PostgreSQL/Flyway 기반으로 바꿔라.
로컬 Docker Compose, Flyway V1, ddl-auto=validate, Testcontainers를 포함하라.
docs/mvp의 전체 핵심 테이블을 V1에 만들고 엔티티와 일치시켜라.

그다음 데모 H2 파일을 읽어 지역/관광지/관광지 이미지/공식 코스/코스 stop만
대상 PostgreSQL로 옮기는 one-off migration runner를 구현하라.
source_system + source_content_id를 멱등 키로 사용하고 dry-run/apply/resume,
batch transaction, 실패 행 quarantine, 실행 manifest와 품질 리포트를 지원하라.
사용자·후기·일정·세션·비밀번호는 절대 이관하지 마라.

첫 검증 대상은 로컬 PostgreSQL이다. RDS에 접속하거나 외부 API를 호출하지 마라.
두 번 apply했을 때 행 수와 내용 checksum이 동일한 통합 테스트를 추가하라.
완료 시 정확한 실행 명령, 환경 변수, 생성 파일, 롤백 방법을 문서화하라.
```

### WORK-00 통과 확인

아래가 모두 참이어야 한다.

- 새 clone에서 한 명령으로 PostgreSQL 기동과 Flyway migration이 된다.
- 빈 DB와 기존 V1 적용 DB 모두 테스트가 통과한다.
- Hibernate가 테이블을 생성/변경하지 않는다.
- 이관 도구의 `--dry-run`은 원천을 바꾸지 않고 예상 행 수만 출력한다.
- 이관 도구를 로컬 DB에 두 번 실행해도 중복 행이 없다.

## 7. Phase D — 로컬 PostgreSQL 리허설

실제 명령 이름은 WORK-00 PR에 작성된 실행 문서를 따른다. 일반적인 실행 흐름은 다음과 같다.

```powershell
Set-Location 'C:\Users\ysj18\Downloads\hidden-travel\backend'
docker compose up -d postgres
.\gradlew.bat flywayMigrate
.\gradlew.bat test
```

그 후 고정한 H2 백업을 source로 이관 도구를 실행한다.

```text
1차: dry-run → source count와 매핑/제외/실패 수 확인
2차: apply → 로컬 PostgreSQL 적재
3차: validate/report → 테이블별 수량과 품질 리포트 생성
4차: 같은 apply 재실행 → inserted=0, unexpected updated=0 확인
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

1. `flywayInfo`로 대상 endpoint와 pending migration을 확인한다.
2. `flywayValidate`가 성공해야 한다.
3. `flywayMigrate`를 한 번 실행한다.
4. `flywayInfo`에서 모든 migration이 `Success`인지 확인한다.
5. `flyway_schema_history`를 직접 수정하지 않는다.

정확한 Gradle task 이름은 WORK-00이 정한 명령을 사용한다. 실행 로그에는 URL의 host/database와 migration version만 남기고 사용자명/비밀번호는 남기지 않는다.

### 10.4 데이터 이관

로컬에서 검증한 **동일한 H2 백업 SHA-256과 동일한 이관 도구 버전**을 사용한다.

1. RDS 대상으로 `--dry-run` 실행
2. source count가 로컬 리허설과 같은지 확인
3. `--apply` 실행
4. 실패 시 성공 batch는 유지하고 cursor부터 재개
5. `--validate`/`--report` 실행
6. 같은 `--apply`를 다시 실행해 insert/update가 예상대로 0인지 확인

RDS에 H2 dump SQL을 직접 실행하거나 JPA `ddl-auto=update`로 구조를 맞추지 않는다. H2와 PostgreSQL의 타입, identity, 예약어, boolean 문법이 달라 안전하지 않다.

## 11. Phase H — 검증과 롤백

### 11.1 최소 SQL 검증

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

### 11.2 애플리케이션 검증

- `tripin_app` 계정으로 서버가 기동된다.
- `ddl-auto=validate`가 성공한다.
- `tripin_app`으로 `CREATE TABLE`을 시도하면 권한 오류가 난다.
- 지역/관광지 read API가 샘플 데이터를 반환한다.
- 빈 설명/이미지/좌표 관광지는 추천 후보에서 빠진다.

### 11.3 완료 스냅샷과 논리 백업

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

### 11.4 실패 시 롤백 기준

- Flyway가 빈 DB에서 실패: 데이터를 넣지 말고 V migration을 수정한다. 아직 공유 적용 전인 V1만 수정 가능하다.
- 일부 데이터 batch 실패: 스키마를 되돌리지 말고 실패 행을 격리한 뒤 cursor부터 재개한다.
- 잘못된 대량 update/delete: 즉시 배치를 중지하고 before-import snapshot으로 **새 RDS 인스턴스**를 복원해 대조한다.
- 공유된 Flyway migration 오류: 적용된 migration 파일을 고치지 않고 다음 version의 보정 migration을 추가한다.

## 12. Phase I — TourAPI 상세·이미지 보강

기본 이관과 스냅샷이 끝난 다음 `.agents/skills/tourapi-detail-backfill/SKILL.md`를 사용한다.

### 12.1 원칙

- 이관된 description/image/detail을 먼저 재사용한다.
- `source_content_id`가 있고 필요한 필드가 비어 있는 행만 호출한다.
- API key는 각 개발자의 `TOUR_API_KEY` secret으로만 넣고 공유하거나 합산해 쿼터를 우회하지 않는다.
- 작은 dry-run과 10~20건 canary 이후 batch 크기를 올린다.
- 429/일일 한도 도달 시 즉시 멈추고 cursor를 저장한다.
- 원천 응답이 빈 값이면 `SOURCE_EMPTY`로 기록하고 반복 호출하지 않는다.
- 성공 필드를 빈 응답으로 덮어쓰지 않는다.

### 12.2 보강 순서

```text
detailCommon: overview/homepage/대표 이미지 확인
  → contentType별 detailIntro: 이용시간/휴무/주차/안내
  → 필요 시 detailImage: 검증 가능한 이미지 후보
  → URL 검증
  → 추천 가능 상태 재계산
  → ingestion_runs/data_quality_issues 리포트
```

이미지가 없거나 설명·좌표가 없으면 MVP 추천 대상에서는 제외하되 원천 행은 삭제하지 않는다. 나중에 데이터가 보강되면 자동으로 다시 적격 판정을 받을 수 있어야 한다.

### 12.3 지역 소개 콘텐츠

TourAPI에는 시군구 단위 감성 소개문이 없다. 관광지 보강 후 다음 별도 배치를 수행한다.

1. 지역명, 행정구역, 검증된 역사/특성 사실, 대표 관광지와 출처를 묶는다.
2. LLM은 근거 안에서만 2~4문단 초안을 생성한다.
3. `region_contents.status=DRAFT`로 저장한다.
4. 사람이 사실·출처·문체와 hero image를 검토한다.
5. `APPROVED`가 된 콘텐츠만 지역 카드와 추첨에 사용한다.

AI 초안을 곧바로 `APPROVED`로 넣지 않는다.

## 13. 운영 체크리스트

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

## 14. 자주 막히는 문제

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

## 15. AWS 공식 참고

- [RDS DB instance 생성](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_CreateDBInstance.html)
- [Public/private access 선택](https://docs.aws.amazon.com/AmazonRDS/latest/gettingstartedguide/security-public-private.html)
- [RDS PostgreSQL TLS 연결](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/PostgreSQL.Concepts.General.SSL.html)
- [RDS CA certificate bundle](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/UsingWithRDS.SSL.html)
- [PostgreSQL 지원 일정](https://docs.aws.amazon.com/AmazonRDS/latest/PostgreSQLReleaseNotes/postgresql-release-calendar.html)
- [DB instance class 사양](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Concepts.DBInstanceClass.Summary.html)
- [RDS storage](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/CHAP_Storage.html)
- [자동 백업 보존 기간](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_WorkingWithAutomatedBackups.BackupRetention.html)

## Related

- [MVP 데이터 이관·추천 설계](../mvp/data-and-recommendation.md)
- [초기 세팅과 팀 실행 순서](../mvp/delivery.md)
- [MVP 구현 작업서 WORK-00/09](../mvp/implementation-workpack.md)
- [Flyway/RDS 저장소 스킬](../../.agents/skills/flyway-rds-sync/SKILL.md)
- [TourAPI 보강 저장소 스킬](../../.agents/skills/tourapi-detail-backfill/SKILL.md)
