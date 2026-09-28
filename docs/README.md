 an# 문서

백엔드의 제품 정책, API 계약, 설계, 규칙을 저장소 안에서 관리한다. 이 파일이 유일한 입구다.

## 읽는 순서

1. [제품 정책](product.md) — 무엇을 왜 만드는가. 사용자 흐름, 범위(MVP/추가 기능), 결정 기록
2. [API 명세](api/README.md) — 무엇을 호출하는가. 공통 규약, 호출 주체, 구현 체크리스트, 결정 필요 항목
3. [컨벤션](conventions/README.md) — 어떻게 코드를 쓰고 협업하는가
4. 필요할 때: [추천 설계](design/recommendation.md), [ADR](adr/README.md), [RDS 런북](runbooks/rds-postgresql-bootstrap-and-migration.md)

작업은 GitHub 이슈(마일스톤 `MVP`, `추가 기능`)로 나누고, 각 이슈는 API 명세의 절을 링크한다. [archive](archive/README.md)는 대체된 이력이며 구현 근거로 쓰지 않는다.

코드와 문서가 달라지면 같은 PR에서 함께 고친다.
