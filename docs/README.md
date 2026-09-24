# Backend Documentation

TriPin 백엔드의 요구사항, API 계약, 설계 결정, 개발 규칙을 저장소 안에서 관리합니다.

## 에이전트와 개발자 읽기 순서

1. [프로젝트 README](../README.md)
2. [문서화 규칙](conventions/문서화.md)
3. [컨벤션 인덱스](conventions/README.md)
4. 작업 대상의 [기능 명세](features/README.md)
5. 관련 [REST API 명세](api/README.md)
6. 관련 [ADR](adr/README.md)

## 문서 영역

- [MVP 구현 기준](mvp/README.md): 확정 제품 결정, 기능·API·데이터·PR별 작업 순서
- [개발 RDS 생성·데모 데이터 이관 런북](runbooks/rds-postgresql-bootstrap-and-migration.md): AWS 초보자용 PostgreSQL RDS 생성, H2 이관, 검증, 보강 절차
- [REST API](api/README.md): MVP 전체의 목표 API 계약과 구현 체크리스트
- [기능 명세](features/README.md): 작업 목표와 완료 기준
- [ADR](adr/README.md): 중요한 설계 결정과 trade-off
- [컨벤션](conventions/README.md): 팀 공통 개발 규칙

코드와 문서가 달라지면 코드만 고치지 말고 같은 변경에서 문서도 갱신합니다.
