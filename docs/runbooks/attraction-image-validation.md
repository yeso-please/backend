# 관광지 이미지 검증

추천 가능 관광지는 `attraction_images.validation_status = 'VALID'`인 이미지가 1장 이상 있어야 한다([recommendation.md](../design/recommendation.md) 1절, `RegionQualityRepository.RECOMMENDABLE`). 새로 적재한 이미지는 `PENDING`으로 들어오므로 이 절차로 검증한다.

대상은 **개발 RDS**다. 운영 RDS에는 쓰지 않는다. DB 쓰기(3단계)는 공용 데이터 변경이므로 팀에 알리고 진행한다.

## 판정

`scripts/validate_attraction_images.py`가 URL마다 앞부분 128KB를 받아 판정한다. 동시 요청은 12개다.

| 결과 | 기준 | DB 반영 |
|---|---|---|
| `VALID` | HTTP 200, `image/*`, Pillow가 형식·크기를 읽음, 가로·세로 100px 이상 | `VALID`, `validated_at = now()` |
| `INVALID` | 403/404/410, 이미지 아님, 판독 불가, 너무 작음 | 결과를 보고 사람이 판단한다(자동 반영하지 않음) |
| `ERROR` | 타임아웃·5xx 등 일시 오류 | 반영하지 않음(PENDING 유지, 다음에 다시) |

## 절차

준비: Docker Desktop, Python 3.11+와 `pip install requests Pillow`, `backend/.env`의 DB 접속값, RDS CA(`https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem`)를 작업 폴더에 `ca.pem`으로 저장.

비밀번호는 `PGPASSWORD` 환경변수로만 넘기고 출력하거나 파일로 남기지 않는다. 아래 `$W`는 작업 폴더(git 밖)다.

```powershell
$env:PGHOST = '<RDS host>'; $env:PGDATABASE = 'tripin_dev'; $env:PGUSER = 'tripin_app'; $env:PGPASSWORD = '<.env의 DB_PASSWORD>'
$psql = "docker run --rm -e PGHOST -e PGDATABASE -e PGUSER -e PGPASSWORD -e PGOPTIONS -e PGSSLMODE=verify-full -e PGSSLROOTCERT=/w/ca.pem -v ${W}:/w postgres:17-alpine psql"
```

1. **PENDING 이미지 export (읽기 전용)**

   ```powershell
   $env:PGOPTIONS = '-c default_transaction_read_only=on'
   Invoke-Expression "$psql -c `"\copy (select id, image_url from app.attraction_images where validation_status='PENDING' order by id) to '/w/images.csv' csv header`""
   ```

2. **검증 (DB에 쓰지 않음)**. 먼저 `--limit 200`으로 샘플을 보고 전체를 돌린다. 1만 건에 약 6분.

   ```powershell
   python scripts/validate_attraction_images.py $W/images.csv $W/results.csv $W/valid_ids.csv
   ```

   결과 집계를 팀에 공유한다. INVALID가 많으면 원인(원본 삭제·URL 변경)을 먼저 본다.

3. **반영 (개발 RDS 쓰기)**. `$W/apply.sql`:

   ```sql
   \set ON_ERROR_STOP on
   begin;
   create temp table valid_ids(id bigint primary key) on commit drop;
   \copy valid_ids from '/w/valid_ids.csv' csv
   update app.attraction_images i set validation_status = 'VALID', validated_at = now()
   from valid_ids v where i.id = v.id and i.validation_status = 'PENDING';
   -- 출력된 UPDATE 건수가 valid_ids.csv 줄 수와 다르면 commit 대신 rollback 한다
   commit;
   ```

   ```powershell
   $env:PGOPTIONS = $null
   Invoke-Expression "$psql -f /w/apply.sql"
   ```

4. **확인 후 정리**

   ```sql
   select validation_status, count(*) from app.attraction_images group by 1;
   ```

   `$env:PGPASSWORD = $null`로 지우고, 작업 폴더의 CSV는 보관하지 않아도 된다.

## 실행 기록

| 날짜 | 대상 | 결과 |
|---|---|---|
| 2026-10-01 | 개발 RDS PENDING 10,933건 | VALID 10,933(JPEG 10,700·PNG 163·BMP 70), INVALID 0, ERROR 0. 추천 가능 관광지 0곳에서 3,982곳으로 늘었다 |
