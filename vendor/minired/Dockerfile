# B10: gradle로 빌드 → JRE만 담아 실행
FROM gradle:8.10.2-jdk21 AS build
WORKDIR /src
COPY . .
RUN gradle --no-daemon -q jar

FROM eclipse-temurin:21-jre
COPY --from=build /src/build/libs/minired.jar /app/minired.jar
EXPOSE 6379
# exec 형식: java가 PID 1이라 SIGTERM을 직접 받는다 (B9)
ENTRYPOINT ["java", "-jar", "/app/minired.jar"]
