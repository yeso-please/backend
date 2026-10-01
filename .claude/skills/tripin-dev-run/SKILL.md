---
name: tripin-dev-run
description: TriPin 개발 스택(Spring backend, Python AI 임베딩 서버, backend/test_frontend 테스트 프론트)을 Docker Compose로 로컬 컴퓨터에서 함께 실행하고 상태를 확인한다. "백엔드·AI·테스트 프론트 띄워줘", "로컬 개발 서버 실행", "dev-rds로 backend 실행" 같은 요청에 사용한다. 스키마 변경·migration 작성·RDS 데이터 적재에는 사용하지 않는다($db-man, $flyway-rds-sync, $tourapi-attraction-backfill 사용).
---

# tripin-dev-run (Claude Code 진입점)

본문은 Codex와 같이 쓰는 `.agents/skills/tripin-dev-run/SKILL.md` 한 곳에서 관리한다. 저장소 루트 기준으로 그 파일을 읽고 그대로 따른다. 이 파일에는 절차를 복사하지 않는다.
