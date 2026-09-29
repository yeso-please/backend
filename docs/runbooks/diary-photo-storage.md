# 여행기 사진 S3 저장소 설정

여행기 사진 저장은 로컬·테스트에서는 비활성화하고, 실제 사진 API를 제공하는 배포 환경에서만 비공개 S3 버킷으로 켠다. 공개 접근은 허용하지 않으며 앱은 인증·권한을 확인한 다음 5분짜리 presigned GET URL을 응답한다.

## 애플리케이션 설정

배포 환경에 다음 설정을 제공한다.

| 변수 | 값 |
|---|---|
| `DIARY_PHOTOS_S3_ENABLED` | `true` |
| `DIARY_PHOTOS_S3_BUCKET` | 전용 사진 버킷 이름 |
| `AWS_REGION` | 버킷 리전(예: `ap-northeast-2`) |

AWS SDK 기본 자격 증명 공급자를 사용한다. AWS 배포에서는 EC2 instance profile 또는 ECS task role에 권한을 부여하고, 장기 access key를 설정 파일·환경 변수·저장소에 넣지 않는다. 로컬에서 개발할 때는 개인 AWS profile/SSO를 사용한다.

## 버킷 및 IAM

- S3 Block Public Access 네 항목을 모두 켜고 bucket policy로 공개 읽기/쓰기를 허용하지 않는다.
- 기본 암호화를 켠다. 사진 key는 `diaries/{diaryId}/{randomId}/original` 및 `thumbnail.jpg` 형태이며 사용자가 준 파일명은 key에 쓰지 않는다.
- 앱 role에는 해당 버킷의 `diaries/*` prefix에만 `s3:PutObject`, `s3:GetObject`, `s3:DeleteObject`를 허용한다. presigned URL은 앱 role의 GET 권한으로 서명된다.
- 현재 단순 동기 저장소 삭제는 S3 호출과 PostgreSQL 트랜잭션을 원자화하지 않는다. 삭제 API의 실패/부분 실패를 모니터링하고, 운영 트래픽을 받기 전에는 삭제 outbox·재시도 작업을 별도 운영 개선으로 추가한다.
- 사용자 사진은 민감한 데이터다. 접근 로그·애플리케이션 로그에 presigned URL이나 업로드 이미지 본문을 남기지 않는다.

## 확인

1. 버킷 public access 차단과 암호화를 확인한다.
2. 앱 role이 지정 prefix에 객체를 넣고 읽고 삭제할 수 있는지 확인한다.
3. 미인증 사용자의 API 접근이 거부되고, 권한 확인 후 받은 URL은 최대 5분 뒤 만료되는지 확인한다.
4. 로컬 기본 설정(`DIARY_PHOTOS_S3_ENABLED=false`)에서도 애플리케이션과 테스트가 S3 자격 증명 없이 시작되는지 확인한다.
