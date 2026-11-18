# syntax=docker/dockerfile:1
# 데모 서버: gradle로 단위 테스트 + 설치본 → JRE만 담아 실행. 빌드 컨텍스트 = 저장소 루트
FROM gradle:8.10.2-jdk21 AS build
WORKDIR /src
COPY demo/settings.gradle.kts demo/build.gradle.kts ./
COPY demo/src ./src
RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle --no-daemon --console=plain test installDist

FROM eclipse-temurin:21-jre
COPY --from=build /src/build/install/demo /app
COPY demo/web /app/web
COPY vendor/mini-react /app/vendor/mini-react
ENV WEB_DIR=/app/web VENDOR_DIR=/app/vendor
EXPOSE 8080
ENTRYPOINT ["/app/bin/demo"]
