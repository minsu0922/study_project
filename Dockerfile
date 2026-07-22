# 멀티스테이지 빌드: "빌드용 큰 상자"와 "실행용 작은 상자"를 분리한다.
# 이유(트레이드오프): gradle/JDK 풀 이미지는 빌드엔 필요하지만 실행 시엔 불필요한 용량만
# 차지한다 → 최종 이미지에는 JRE(실행기)만 남기고 빌드 도구는 통째로 버려서 이미지를 가볍게 만든다.

# ── 1단계: 빌드 ──────────────────────────────
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# 의존성 정의 파일만 먼저 복사 → 소스 코드만 바뀌고 build.gradle이 그대로면
# 도커 레이어 캐시가 이 시점까지 재사용되어 매번 의존성을 새로 안 받는다.
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew

COPY src src
# 이미지 안에서는 굳이 다시 테스트를 돌리지 않는다: CI의 build-and-test 잡이 이미
# 같은 커밋에 대해 테스트를 통과시킨 뒤에만 이 이미지 빌드 잡이 실행되기 때문(-x test로 중복 방지).
RUN ./gradlew bootJar --no-daemon -x test

# ── 2단계: 실행 ──────────────────────────────
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
