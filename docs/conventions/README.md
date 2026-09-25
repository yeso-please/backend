# 컨벤션

| 문서 | 내용 |
|---|---|
| [모듈·의존성](모듈-의존성.md) | 패키지 구조(BC)와 의존 방향 |
| [코드](코드.md) | API 응답 형식, 예외 처리, DTO, ID 타입, 감사 시각, 엔티티 변경, 현재 시각(Clock), 사용자 정보 주입 |
| [테스트](테스트.md) | 테스트 종류와 작성 규칙 |
| [협업](협업.md) | 브랜치·커밋, 이슈 라벨, CI |
| [문서화](문서화.md) | 문서 위치, 이슈 작성, API 명세 작성, 에이전트 작업 |
| [설정](설정.md) | CORS, OpenAPI·Swagger |

```text
presentation → application → domain
infrastructure → application/domain
shared는 특정 도메인을 참조하지 않음
```
