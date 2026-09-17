# CI

모든 PR은 다음 검사를 통과해야 합니다.

```text
./gradlew compileJava
./gradlew test
```

포매터를 도입한 뒤에는 로컬에서 수정 명령을 실행하고 CI에서는 검사만 실행합니다.

```text
./gradlew spotlessApply
./gradlew spotlessCheck
```

CI는 컴파일 실패와 테스트 실패를 머지 차단 조건으로 사용합니다. PR 본문에는 실제 실행한 검증 명령과 결과를 적습니다.

현재 프로젝트에 GitHub Actions를 추가할 때는 우선 Java 21과 Gradle Wrapper를 기준으로 구성하고, SQLite 기반 통합 테스트가 재현되도록 합니다. 운영 DB와 Flyway 도입은 데이터베이스 선택이 확정된 뒤 별도 작업으로 진행합니다.
