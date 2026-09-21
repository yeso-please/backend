# 감사 시각·CORS 공통 기반

- 상태: done
- 담당 범위: shared, auth security, JPA entities
- 작성일: 2026-09-21
- 갱신일: 2026-09-21
- 관련 API: [CORS 정책](../api/cors.md)

## 목표

변경 이력을 위한 생성·수정 시각을 애플리케이션 서버 시각 기준으로 일관되게 기록하고, 브라우저 API 호출은 허용된 프론트엔드 Origin에서만 가능하게 한다.

## 포함

- `BaseTimeEntity`의 `createdAt`/`updatedAt` JPA Auditing
- 기존 시각 컬럼을 가진 `User`, `TripPlan`, `Attraction`의 공통 엔티티 적용
- 기존 단일 시각 컬럼의 생성·수정 시각도 JPA Auditing으로 관리
- 자격 증명을 포함하는 명시 Origin allowlist CORS
- 감사 시각과 CORS preflight 계약 테스트

## 제외

- 사용자별 변경 주체(createdBy/updatedBy) 기록
- API 사용량·접속 로그 테이블
- 운영 도메인 또는 production RDS 설정 변경

## 완료 기준

- [x] 신규 저장 시 생성 시각이 자동 기록된다.
- [x] 변경 저장 시 수정 시각이 자동 갱신된다.
- [x] 허용 Origin만 credentials CORS 응답을 받는다.
- [x] 허용되지 않은 Origin은 `Access-Control-Allow-Origin`을 받지 않는다.
