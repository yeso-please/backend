# Backend Conventions

TriPin 백엔드의 공통 개발 규칙입니다. 새 기능은 아래 순서와 책임을 따릅니다.

```text
presentation → application → domain
infrastructure → application/domain
shared는 특정 도메인을 참조하지 않음
```

## 문서 목록

- [모듈·의존성](모듈-의존성.md)
- [API 응답 형식](API-응답-형식.md)
- [예외 처리](예외-처리.md)
- [DTO](DTO.md)
- [ID 타입](ID-타입.md)
- [사용자 정보 주입](유저-정보-주입.md)
- [테스트](테스트.md)
- [브랜치·커밋](브랜치-커밋.md)
- [CI](CI.md)
- [문서화](문서화.md)
- [REST API 명세](API-명세.md)
- [작업 명세](작업-명세.md)
- [ADR](ADR.md)
- [에이전트 컨텍스트](에이전트-컨텍스트.md)

성공 응답은 HTTP 상태 코드와 DTO를 사용하고, 실패 응답은 공통 형식으로 반환합니다. 도메인 규칙은 서비스가 아니라 도메인과 애플리케이션 계층에 두며, Controller가 인증 사용자 정보를 받아 서비스에 필요한 식별자만 전달합니다.

작업 전에는 `docs/README.md`, 관련 작업 명세, 관련 API 명세, ADR 순서로 확인합니다. 작업이 끝나면 코드와 함께 문서를 갱신합니다.
