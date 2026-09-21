# 공통 오류 응답 계약 통일

- 상태: done
- 담당 범위: shared, auth security
- 작성일: 2026-09-21
- 갱신일: 2026-09-21
- 관련 API: [공통 오류 응답](../api/common-errors.md)

## 목표

Controller, validation, Security 단계의 실패 응답을 `ApiErrorResponse` 하나로 통일해 클라이언트가 상태·코드·경로·필드 오류를 일관되게 처리하게 한다.

## 포함

- `ErrorCode` 기반 도메인 오류 코드
- `GlobalExceptionHandler`의 공통 JSON 오류 변환
- Security 401/403 handler 연결
- 오류 응답 계약 테스트와 규약 문서 갱신

## 제외

- 성공 응답 wrapper 도입
- CORS, 감사 엔티티, OpenAPI 설정

## 완료 기준

- [x] validation, 도메인 오류, 401, 403이 `ApiErrorResponse` 형식을 반환한다.
- [x] 500은 내부 오류 메시지와 stack trace를 응답에 노출하지 않는다.
- [x] 구형 `ErrorResponse`와 JWT 전용 오류 핸들러를 제거했다.
- [x] 오류 코드와 JSON 필드를 검증하는 자동화 테스트가 있다.
- [x] API·예외 처리 규약이 구현과 일치한다.
