# 문서

백엔드의 제품 정책, API 계약, 설계, 규칙을 저장소 안에서 관리한다. 이 파일이 유일한 입구다.

## 읽는 순서

1. [제품 정책](product.md) — 무엇을 왜 만드는가. 사용자 흐름, 범위(MVP/추가 기능), 결정 기록
2. [API 명세](api/README.md) — 무엇을 호출하는가. 공통 규약, 호출 주체, 구현 체크리스트, 결정 필요 항목
3. [컨벤션](conventions/README.md) — 어떻게 코드를 쓰고 협업하는가
4. 프론트 담당자: [화면별 API 가이드](frontend-guide.md)(확정 디자인 기준). 디자이너: [전달 목록](design/designer-requests.md)
5. 필요할 때: [추천 설계](design/recommendation.md), [코스 변경 기록](design/course-changes.md), [여행 성향 16유형](design/travel-personas.md), [경로 API 조사](design/route-api-research.md), [지역 대표 사진 조사](design/region-photo-research.md), [한 줄 소개 검수](runbooks/summary-review.md), [지역 대표 사진 검수](runbooks/region-photo-review.md), [ADR](adr/README.md), [RDS 런북](runbooks/rds-postgresql-bootstrap-and-migration.md), [로컬 개발 스택](runbooks/local-dev-stack.md), [관광지 이미지 검증](runbooks/attraction-image-validation.md), [카카오 로그인 설정](runbooks/kakao-login.md)

작업은 GitHub 이슈(마일스톤 `MVP`, `추가 기능`)로 나누고, 각 이슈는 API 명세의 절을 링크한다. [archive](archive/README.md)는 대체된 이력이며 구현 근거로 쓰지 않는다.

코드와 문서가 달라지면 같은 PR에서 함께 고친다.
