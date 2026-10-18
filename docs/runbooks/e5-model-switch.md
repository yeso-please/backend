# 개발 RDS E5 연결과 배포

## 현재 상태와 반영 순서

2026-10-17 개발 RDS의 추천 가능한 관광지 **8,287곳**을
`e5-base-rating-2022-2023-t4-all-v2`, 768차원, 템플릿3으로 변환했습니다.
교체 전 관광지 벡터9,451개와 회원 벡터·상태는 비공개
`ai/data/interim/deployment/e5-rds-20261017`에 압축 CSV와 SHA256 manifest로 백업했습니다.
스키마 변경이나 추가 학습은 수행하지 않았습니다.

1. backend와 ai의 E5 PR을 **둘 다 반영**합니다.
2. E5 `final` 전체 폴더를 `python scripts/fetch_e5_model.py`로 받습니다([체크포인트 준비](#체크포인트-준비)). 가중치는 Git에 없습니다.
3. 모델을 마운트하고 AI·backend를 함께 재빌드/재시작합니다.
4. AI 상태, 회원 벡터 READY, 실제 `MY_TASTE` 추천을 확인합니다.

**PR 병합은 실행 중인 서버와 모델 파일을 자동 갱신하지 않습니다.**
이전 MiniLM 서버를 같은 개발 RDS에 붙이면 일치하는 관광지 벡터가 없어 개인화가 적용되지 않습니다.
구형 서버의 관광지 재임베딩 배치도 실행하지 않습니다. 관광지당 벡터는 한 행이어서 다시 덮어쓰게 됩니다.

## 체크포인트 준비

### 받기 (2026-10-18)

체크포인트는 비공개 S3에 있습니다: `s3://tripin-dev-models-545769669608/e5/e5-base-rating-2022-2023-t4-all-v2.zip`
(서울 리전, 퍼블릭 액세스 차단·암호화·버전 관리). zip SHA256은 `0bff9eff843ae88ffeecfb2274ba15aaa5acf63c9bb8c0cf7b2da8c1c78b9a39`이고 같은 경로의 `.sha256`에도 있습니다.

```powershell
# backend 폴더. 버킷 읽기 권한이 있는 AWS CLI 자격 증명이 있을 때
python scripts/fetch_e5_model.py
# 자격 증명이 없으면 팀에서 받은 기간 한정 링크(최대 7일)로
python scripts/fetch_e5_model.py --url "<링크>"
```

스크립트는 zip과 가중치 SHA256을 확인하고 아래 기본 위치에 풉니다. 이미 같은 가중치가 있으면 아무것도 하지 않습니다.
링크 만들기(버킷 권한이 있는 사람): `aws s3 presign s3://tripin-dev-models-545769669608/e5/e5-base-rating-2022-2023-t4-all-v2.zip --expires-in 604800`.
AI Hub 학습 파생물이라 **팀 밖으로 전달하지 않습니다.** 공개 저장소(GitHub Release, 공개 Hugging Face 등)에 올리지 않고, 링크를 공개 채널에 붙이지 않습니다.

기본 위치: `../ai/data/interim/models/e5-base-rating-2022-2023-t4-all-v2/final`.
다른 위치라면 `TRIPIN_E5_MODEL_DIR`을 지정합니다. 기존 `TRIPIN_MODEL_DIR`을 사용하던 환경은
MiniLM 경로가 남아 있는지 확인하고 E5 폴더로 바꾸거나 제거합니다.

`model.safetensors`(약 1.1GB) 외에 `training_info.json`, `modules.json`,
`config.json`, 토크나이저 파일, `1_Pooling/config.json` 등 **final 전체 폴더**가 필요합니다.
모델 파일이나 AI Hub 원본·파생 데이터는 Git에 올리지 않습니다.

가중치 SHA256: `d5168644835c8d052efc3a3d37dd4c35a7754b72fa7eb9b2cd447f1318f7c187`.

```powershell
Get-FileHash ../ai/data/interim/models/e5-base-rating-2022-2023-t4-all-v2/final/model.safetensors -Algorithm SHA256
```

AI 저장소에서 `python scripts/check_e5_serving.py`를 실행하면 실제 모델의
query/passage 입력, 차원, 정규화, 학습 입력 일치를 검사합니다. DB 쓰기나 학습은 없습니다.
이 모델은 2022·2023 TL로 학습한 기존 단일 체크포인트이며 5겹 앙상블이 아닙니다.

## 개발 스택 시작

개발 RDS 대상·TLS·Flyway 상태는 [로컬 개발 런북](local-dev-stack.md)과
저장소의 `tripin-dev-run` 절차로 먼저 확인합니다. 서버 시작은 공유 RDS migration 승인을 대신하지 않습니다.
이 변경에는 새로운 migration이 없습니다.

```powershell
# backend 폴더
# 개발 RDS의 migration 상태를 확인한 뒤 실행
docker compose -f compose.dev.yaml up -d --build
```

`compose.dev.yaml`은 E5를 기본 사용합니다. 기존 `compose.e5.yaml` override도 유지합니다.
AI가 UP·768차원·정확한 모델 버전·템플릿3을 보고해야 backend를 시작합니다.
모델이 없거나 MiniLM을 마운트하면 healthcheck가 실패하므로 모델 경로를 수정합니다.

공유 RDS를 사용하지 않는 빈 로컬 DB에서는 다음 명령을 사용합니다.

```powershell
docker compose -f compose.dev.yaml -f compose.dev.local-db.yaml up -d --build
```

직접 `dev-rds` 프로필로 실행하는 backend도 E5 기본값을 사용합니다.
Docker를 쓰지 않는 AI는 ai README의 E5 환경변수를 모두 지정해야 합니다.

## 입력 계약

| 항목 | AI | backend |
|---|---|---|
| 버전 | `MODEL_VERSION=e5-base-rating-2022-2023-t4-all-v2` | `EMBEDDING_MODEL_VERSION` 동일 |
| 모델 | `MODEL_NAME=/models/final` | AI URL 지정 |
| 형식 | `MODEL_INPUT_FORMAT=e5` | float32 LE base64 |
| 차원 | 768 | `EMBEDDING_EXPECTED_DIMENSION=768` |
| 템플릿 | `TEMPLATE_VERSIONS=3` | 프로필·관광지 모두3 |
| 배치·타임아웃 | `MAX_BATCH_ITEMS=8` | 배치8, 120초 |

회원 입력은 스타일1·3·5·6과 동기만 문장화하여 `query: `를 붙입니다.
관광지는 이름·유형·분류·주소의 시군구·설명 앞300자를 문장화하여 `passage: `를 붙입니다.
최대256토큰과 L2정규화는 학습과 같습니다. 선호 지역·MBTI·제외 조건·밀도는 임베딩에 넣지 않습니다.
설문 API와 표시용 결과는 유지되며 설문 `questionVersion`과 임베딩 템플릿 번호는 다릅니다.
범용 MiniLM/템플릿2 경로는 보존하지만 공유 개발 RDS는 E5 설정을 사용해야 합니다.

## 상태와 회원 확인

```powershell
Invoke-RestMethod http://127.0.0.1:8000/health
# status UP, dimension 768, templateVersions [3], modelVersion 정확히 일치
```

실제 AI Hub 응답이 있는 기존 회원2명은 변환했습니다. 구형 MBTI 설문3명은 취향 재검사가 필요합니다.
신규 설문/재제출은 템플릿3 벡터를 만들며, 모델·템플릿·차원이 모두 일치해야 추천에 사용합니다.
기존 벡터의 차원 숫자만 수정해서 재사용하지 않습니다.

전환 직후 검증에서 관광지 전체의 바이트 길이·유한값·정규화와 실제 `MY_TASTE` 적용을 확인했습니다.
서버 갱신 후에는 자신의 온보딩 READY와 지역 추첨 응답의 취향 적용 여부를 다시 확인합니다.
기존 소개글 course index는 모델 버전이 달라 로드하지 않고 규칙/LLM 경로를 사용합니다.

## 관광지 추가 재생성과 복구

현재 대상은 모두 변환돼 있으므로 팀원별 재생성은 필요하지 않습니다.
추가 재생성은 대상 DB·범위·백업을 확인하고 별도로 승인된 경우에만 실행합니다.
배치는 같은 모델·템플릿·차원으로 완료된 행을 건너뛰며, 시작만으로 자동 실행되지 않습니다.

되돌릴 때는 서버 설정과 관광지/회원 벡터·상태를 **함께** 복원해야 합니다.
먼저 관련 서버를 중지하고 백업 이후 신규 데이터와의 충돌을 검토합니다.
설정만 MiniLM으로 바꿔서는 덮어쓴 벡터가 복구되지 않습니다. 백업 복원은 이 PR이 자동 실행하지 않습니다.
