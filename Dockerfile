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

# curl 하나만 더 얹는다 — 아래 HEALTHCHECK가 쓸 도구다.
# JRE 이미지에는 HTTP를 부를 수단이 없다(curl·wget 모두 없음). 자바로 대신 부르면 점검
# 한 번마다 JVM이 새로 뜨는데, 30초마다 그 비용을 내는 것보다 몇 MB가 싸다.
# --no-install-recommends와 목록 삭제는 그 몇 MB를 더 줄이기 위한 관용구다.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080

# 상태 점검 — 도커(또는 오케스트레이터)가 "이 컨테이너 살았니?"를 주기적으로 묻는다.
# 없으면 프로세스가 떠 있기만 하고 요청은 못 받는 상태(부팅 중·DB 못 잡음)를 밖에서
# 구분할 수 없어, 죽은 컨테이너로 트래픽이 계속 들어간다.
#
# essential 그룹을 보는 이유(중요): 기본 /actuator/health는 Redis가 죽으면 DOWN을 준다.
# 그런데 이 앱은 Redis 없이도 <일부러> 계속 서비스한다(fail-open). 그 값을 여기 물리면
# 멀쩡한 앱이 30초마다 unhealthy 판정을 받고 재시작 대상이 된다 — 감시 장치가 설계를
# 무너뜨리는 셈이다. 그래서 "죽으면 진짜 못 파는 것"만 담은 그룹을 본다(application.yml).
#
# start-period: 로컬 실측 기동이 약 13초(Flyway 포함)라 60초면 넉넉하다. 이 시간 안의
# 실패는 재시도 횟수에 세지 않는다 — 부팅 중인 앱을 죽이지 않기 위한 유예다.
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/actuator/health/essential || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
