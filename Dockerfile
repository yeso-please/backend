# TriPin Spring backend — 로컬 개발 스택(compose.dev.yaml)용 이미지.
# 비밀값은 이미지에 넣지 않는다. DB·JWT 값은 실행 시 환경변수로 받는다.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon -q dependencies > /dev/null

COPY src src
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar -x test \
    && cp "$(ls build/libs/*.jar | grep -v -- '-plain\.jar$')" /src/app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app

# 개발 RDS TLS(verify-full)용 AWS 공개 CA bundle. entrypoint가 DB_URL의 sslrootcert를 이 경로로 바꾼다.
ADD --chmod=644 https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem /certs/rds-global-bundle.pem
COPY docker/entrypoint.sh /entrypoint.sh
RUN sed -i 's/\r$//' /entrypoint.sh && chmod +x /entrypoint.sh
COPY --from=build /src/app.jar /app/app.jar

ENV TZ=Asia/Seoul
EXPOSE 8080
ENTRYPOINT ["/entrypoint.sh"]
