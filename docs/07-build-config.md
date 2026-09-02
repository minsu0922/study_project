# 07. 빌드 & 설정 — 이 프로젝트를 처음부터 세우려면

> 이 문서는 "무엇을, 어떤 버전으로 깔고, 어떤 설정값으로 돌리는지"를 적어 둔 곳이다.
> 설계 문서(01~06)는 "무엇을 만들지"를 말하지만, 실제로 재현하려면 **정확한 버전과 설정값**이 필요하다.
> 이 문서 하나면 `build.gradle` / `application.yml` / `docker-compose.yml`을 그대로 다시 만들 수 있게 하는 게 목표.

---

## 아주 쉽게: 세 파일의 역할

집을 짓는다고 치면 —

- `build.gradle` = **자재 주문서**. 어떤 라이브러리(벽돌·창문)를 어떤 버전으로 쓸지 적는다.
- `application.yml` = **집 설정값**. 전기(DB 주소), 보일러 온도(토큰 만료 시간) 같은 운영 옵션.
- `docker-compose.yml` = **터 다지기 장비**. 개발용 데이터베이스(MySQL)를 명령 한 줄로 띄운다.

---

## 기술 스택 & 버전 (자재 주문서)

| 항목 | 버전 | 비고 |
|---|---|---|
| Java | 21 | LTS. `build.gradle`의 toolchain으로 고정. |
| Spring Boot | 3.4.1 | 4.x에서 다운그레이드한 안정 버전. 대부분의 라이브러리 버전은 Boot BOM이 관리. |
| Gradle | wrapper 사용 | `./gradlew`로 실행 → 팀원마다 Gradle 버전이 달라도 동일하게 빌드. |
| MySQL | 8.0 | InnoDB, `utf8mb4_0900_ai_ci`. |
| Spring Data JPA | (Boot BOM) | ORM. QueryDSL은 로드맵1에서 추가. |
| Spring Security | (Boot BOM) | 인증/인가 → [06-security-jwt](06-security-jwt.md). |
| Flyway | (Boot BOM) | `flyway-core` + `flyway-mysql`(MySQL 8은 이 모듈 필요). |
| jjwt | **0.12.6** | JWT 라이브러리. Boot BOM이 관리 안 하므로 **버전 명시 필수**. api/impl/jackson 3개로 분리. |
| springdoc-openapi | **2.7.0** | Swagger UI. Boot 3.x는 2.x 계열. |
| Lombok | (Boot BOM) | 보일러플레이트 축소. |
| Redis | 7.x | refresh 토큰 저장 + 캐시 + 요청 제한 카운터 (로드맵 2·3). |
| QueryDSL | 5.x | 동적 쿼리·fetch join (로드맵 1) → [08](08-performance-experiments.md). |
| Anthropic Java SDK | — | Claude 호출. 구조화 출력(스키마 강제) 사용 → [13](13-llm-problem-generation.md). |
| snakeyaml | (Boot BOM) | **Spring 없이** `application.yml`을 읽는 데 쓴다(배치 CLI). |
| Actuator | (Boot BOM) | 상태 점검 창구. `health`·`info`만 열고 상세는 ADMIN만 (2026-09-02) → 아래 `management` 절. |

### build.gradle 핵심 규칙

- **버전을 직접 적어야 하는 것**은 `jjwt`(0.12.6)와 `springdoc`(2.7.0)뿐. 나머지는 Boot BOM(`io.spring.dependency-management`)이 알아서 맞춘다 → 버전 충돌 방지.
- jjwt는 3개로 나눠 넣는다: `jjwt-api`(컴파일), `jjwt-impl`·`jjwt-jackson`(런타임). API만 코드에서 쓰고 구현체는 실행 시점에만 필요해서 이렇게 나눈다.
- QueryDSL / Redis / Actuator / Testcontainers는 **주석으로 넣어 두고 쓰게 될 때 해제**한다. 아직 안 쓰는 의존성을 미리 넣으면 빌드만 무거워지므로.
  - 해제된 것: QueryDSL(로드맵 1) · Redis(로드맵 2) · **Actuator(2026-09-02)**.
  - **Testcontainers는 해제하지 않았고, 앞으로도 계획이 없다.** 이 항목은 "아직 안 켠 것"이 아니라 **검토 후 안 쓰기로 한 것**이다 — 로컬은 docker-compose, CI는 서비스 컨테이너로 진짜 MySQL·Redis를 띄워 목적을 이미 달성한다. 주석을 남겨 둔 이유는 그 검토가 있었다는 흔적이라, 지우면 "몰라서 안 썼다"와 구분되지 않는다.

---

## application.yml — 설정값과 그 이유

DB 접속·JPA·Flyway·JWT·로깅을 담는다. 값마다 "왜 이 값인지"가 중요하다.

| 설정 | 값 | 왜 이 값인가 |
|---|---|---|
| `spring.application.name` | `csquiz` | 앱 이름(로그·모니터링 식별용). |
| `datasource.url` | `jdbc:mysql://localhost:3306/csquiz?...` | 로컬 MySQL. 옵션: `serverTimezone=Asia/Seoul`(시간대), `characterEncoding=UTF-8`(한글), `rewriteBatchedStatements=true`(배치 INSERT 성능). |
| `datasource.username/password` | 환경변수 우선(`${DB_USERNAME:csquiz}`) | 기본값은 로컬용. 실제 비밀번호는 환경변수로 덮어쓴다 — 코드에 진짜 비번을 박지 않기 위함. |
| `jpa.hibernate.ddl-auto` | **`validate`** | JPA가 테이블을 만들지 않는다. **스키마의 주인은 Flyway**. 엔티티와 실제 테이블이 다르면 부팅이 실패해서, 불일치를 조기에 잡는다. |
| `jpa.properties.hibernate.default_batch_fetch_size` | `100` | N+1 문제를 어느 정도 완화(연관 데이터를 100개씩 묶어 조회). 본격 해결은 로드맵1(QueryDSL+fetch join). |
| `jpa.open-in-view` | **`false`** | 지연 로딩 경계를 서비스 계층으로 명확히. 기본값(true)은 컨트롤러/뷰까지 DB 커넥션을 붙잡아 커넥션 고갈 위험이 있어 끈다. |
| `jpa.show-sql` / `format_sql` | true | 개발 중 쿼리 확인용. |
| `flyway.enabled` | true | 스키마 마이그레이션 활성화. |
| `flyway.baseline-on-migrate` | true | 이미 존재하는 DB에도 안전하게 첫 적용. |
| `springdoc.swagger-ui.path` | `/swagger-ui.html` | Swagger 문서 주소. |
| `jwt.secret` | 환경변수 우선, 기본은 로컬용 긴 문자열 | HS256용 비밀키. **256비트(32바이트) 이상 필수**. 운영에서는 반드시 환경변수로 교체 → [06](06-security-jwt.md). |
| `jwt.access-token-validity-seconds` | `3600` | access 토큰 1시간 만료 → [ADR-0001](adr/0001-access-token-only-for-mvp.md). |
| `jwt.refresh-token-validity-seconds` | `1209600` | 14일. Redis TTL로 자동 청소 → [06](06-security-jwt.md). |
| `spring.data.redis.host/port` | `${REDIS_HOST:127.0.0.1}` / `6379` | **WSL의 Redis가 Windows에서 안 잡히는 일이 있어** 환경변수로 뺐다. |
| `spring.data.redis.timeout` / `connect-timeout` | `2000ms` | 기본 60초를 그대로 두면 Redis가 어중간하게 죽었을 때 요청이 **분 단위로 매달린다**(실측). 캐시는 "빨리 실패"가 원칙 — 2초 안에 포기하고 DB로 간다. |
| `ratelimit.enabled` | `true` | 요청 제한 on/off. **테스트는 이 값을 false로 끈다**(연속 호출이 429에 걸리므로) → [09](09-rate-limiting.md). |
| `ratelimit.*` | auth 5/분, api 60/분 | 경로별 정책. |
| `admin.email` / `admin.password` | 환경변수 우선 | 첫 기동 시 관리자 계정 자동 생성(`AdminAccountInitializer`). 이미 있으면 아무것도 안 한다(멱등). |
| `llm.generation.model` | `claude-opus-5` | **배치 CLI도 이 값을 읽는다** — 설정의 단일 출처. |
| `llm.generation.batch-enabled` | `true` | 생성 중단 스위치 → [16 §5](16-llm-pipeline-operations.md). |
| `llm.generation.batch-type` | `auto` | `auto`\|`problem`\|`document` — 한쪽만 돌리기. |
| `llm.generation.batch-count` / `batch-domains` | `5` / 8개 | 회당 문제 수, 순환 후보 분야. |
| `llm.import.enabled` / `dir` | `true` / `generated` | 기동 시 생성 파일 들여오기. **`dir`는 GitHub Actions가 커밋하는 위치와 반드시 같아야 한다.** |
| `management.endpoints.web.exposure.include` | `health,info` | **적은 것만 열린다.** 기본 묶음에는 `env`(환경변수 전체)·`beans`처럼 설정과 비밀이 그대로 비치는 자리가 있어, 노출 목록을 유일한 관문으로 둔다. |
| `management.endpoint.health.show-details` / `roles` | `when-authorized` / `ADMIN` | 비로그인에게는 `{"status":"UP"}` 한 줄만. 어느 부품이 죽었는지는 정보이자 공격 단서다(DB가 내려간 순간을 밖에서 알 수 있다). |
| `management.endpoint.health.group.essential` | `db,ping` | **fail-open과의 충돌을 푸는 자리.** 기본 집계는 Redis가 죽으면 전체를 DOWN으로 내리는데, 이 앱은 Redis 없이도 일부러 서비스한다. 그 값을 도커 HEALTHCHECK에 물리면 멀쩡한 앱이 재시작 대상이 된다 → "죽으면 진짜 못 파는 것"만 담은 그룹을 기계가 본다(Dockerfile). Redis 상태는 기본 `/actuator/health`에 그대로 남는다 — 감춘 것이 아니라 판정에서 뺐다. |
| `logging.level.org.hibernate.SQL` | debug | 실행 SQL 로그. |
| `logging.level.org.hibernate.orm.jdbc.bind` | trace | 바인딩 파라미터 값까지 출력(개발용). 운영에서는 낮춘다(민감정보 로그 방지). |

> 핵심 조합 하나만 기억: **`ddl-auto=validate` + Flyway**. "JPA는 검사만, 스키마 변경은 Flyway가"라는 역할 분리다.

---

## docker-compose.yml — 개발용 MySQL 한 방에 띄우기

로컬에 MySQL을 직접 설치하지 않아도 `docker compose up -d` 한 줄이면 뜬다.

| 설정 | 값 | 비고 |
|---|---|---|
| image | `mysql:8.0` | |
| ports | `3306:3306` | 호스트 3306 ↔ 컨테이너 3306. |
| MYSQL_DATABASE | `csquiz` | 초기 DB 자동 생성. |
| MYSQL_USER / PASSWORD | `csquiz` / `csquiz1234` | 앱 접속 계정. application.yml 기본값과 일치. |
| MYSQL_ROOT_PASSWORD | `rootpw` | 관리자용. |
| command | `--character-set-server=utf8mb4 --collation-server=utf8mb4_0900_ai_ci` | 한글·이모지 저장. 데이터 모델(01)과 동일 콜레이션. |
| volumes | `mysql-data:/var/lib/mysql` | 컨테이너를 지워도 데이터 유지. |
| healthcheck | `mysqladmin ping` | DB가 완전히 준비됐는지 확인. |
| TZ | `Asia/Seoul` | 시간대. |

- Redis(로드맵2)는 주석으로 준비돼 있고, 캐싱 레이어 진입 시 해제한다.
- Docker를 쓰지 않고 로컬 MySQL을 직접 설치했다면 `db/local-init.sql`로 동일한 DB·계정을 만들 수 있다(값이 docker-compose와 같게 맞춰져 있어 application.yml을 그대로 쓴다).

---

## 처음부터 실행하는 순서 (한 번에 따라 하기)

```bash
# 1. 개발용 DB 띄우기
docker compose up -d          # MySQL 8 기동 (healthy 될 때까지 몇 초)

# 2. (선택) Redis — 없어도 앱은 뜬다
#    없으면: refreshToken이 null로 나가고, 요청 제한은 통과시킨다(둘 다 fail-open).
#    WSL에서: redis-server --daemonize yes

# 3. 빌드 & 실행
./gradlew build               # 컴파일 + 테스트
./gradlew bootRun             # 앱 실행 (기본 포트 8080)

# 4. 기동할 때 벌어지는 일 (순서대로)
#    Flyway 마이그레이션 적용 → JPA가 엔티티↔테이블 일치 검증(validate, 불일치면 부팅 실패)
#    → AdminAccountInitializer: 관리자 계정 없으면 생성
#    → DraftImportRunner(@Order 10): generated/*.json을 검수 대기함으로 들여오기
#    → 스냅샷 내보내기 3종(@Order 20·30·40): DB → generated/_*.json

# 5. 확인
# http://localhost:8080/            홈
# http://localhost:8080/admin.html  관리자(로그인 필요)
# http://localhost:8080/swagger-ui.html
```

**Windows PowerShell에서는** `./gradlew` 대신 `.\gradlew.bat`, 출력이 깨지면 `--console=plain`.

- 운영/공유 환경에서는 `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, `ADMIN_PASSWORD`를
  **환경변수로** 넣어 기본값을 덮어쓴다.
- `ANTHROPIC_API_KEY`는 **로컬에 두지 않는다.** GitHub 암호화 시크릿에만 있고, 배치가 도는
  순간에만 러너에 풀린다. 키가 없어도 앱은 정상 기동하고 생성 기능만 `LLM_004`로 안내된다.
  (윈도우 사용자 환경변수는 레지스트리에 **평문**으로 저장돼 내 계정으로 도는 아무 프로그램이나 읽는다.)

### 기동 순서가 왜 중요한가

들여오기(`@Order 10`)가 내보내기(20·30·40)보다 **먼저** 돌아야 한다. 순서를 안 정하면 스프링이
`ApplicationRunner`를 **맨 뒤로** 돌려서, 오늘 들어온 초안이 오늘 스냅샷에 안 담기고
**하루 늦게 반영**된다. 실제로 그렇게 돼 있었고 `@Order`를 붙여 고쳤다(docs/16 §6).

---

## 트러블슈팅: 한글 사용자 경로(`C:\Users\홍길동\...`)

Windows 사용자명이 한글이면 `./gradlew test`가 코드와 무관하게 실패한다.
Gradle 테스트 워커 JVM이 클래스패스의 비ASCII 경로를 디코딩하지 못해서다.
증상은 두 단계로 나타난다:

1. `Could not find or load main class ...GradleWorkerMain` — 워커 부트스트랩용 `gradle-worker.jar`가 한글 경로(`GRADLE_USER_HOME`) 아래에 있어 발생.
2. (1을 고치면) `ClassNotFoundException: ...StudyProjectApplicationTests` — 컴파일된 테스트 클래스가 한글 프로젝트 경로(`build/`) 아래에 있어 발생.

**해결(두 경로를 모두 ASCII로):**

| 대상 | 조치 | 위치 |
|---|---|---|
| 의존성·Gradle 배포판 | `GRADLE_USER_HOME`을 영문 경로로 | 사용자 환경변수 `GRADLE_USER_HOME=C:\Users\Public\gradle-home` |
| 컴파일된 클래스·리소스 | 빌드 출력 폴더를 영문 경로로 | `build.gradle`에서 프로젝트 경로가 비ASCII일 때만 `layout.buildDirectory`를 `C:/Users/Public/gradle-builds/<name>`으로 이동 |

- `GRADLE_USER_HOME`은 이미 사용자 환경변수로 저장돼 있다(`[Environment]::SetEnvironmentVariable('GRADLE_USER_HOME','C:\Users\Public\gradle-home','User')`). 새 터미널·IntelliJ에 자동 적용된다.
- `build.gradle`의 우회 블록은 **비ASCII 경로일 때만** 동작하므로, 영문 경로 환경(다른 PC/CI)에서는 아무 영향이 없다.
- 근본 해결을 원하면 프로젝트를 영문 경로(예 `C:\dev\study_project`)로 옮기는 방법도 있다. 그럼 이 우회가 전부 불필요해진다.

## 면접 대본 요약

"스키마 관리는 `ddl-auto=validate`와 Flyway를 조합했습니다. JPA가 테이블을 자동 생성하면 편하지만
운영에서 통제가 안 되기 때문에, 스키마의 주인은 Flyway로 두고 JPA는 엔티티와 테이블이 일치하는지 검증만
하도록 했습니다. `open-in-view`는 false로 꺼서 영속성 컨텍스트 경계를 서비스 계층으로 명확히 했고,
커넥션을 뷰 렌더링까지 붙잡지 않게 했습니다. 비밀번호·JWT 시크릿 같은 민감값은 코드에 박지 않고
환경변수로 주입하며, 기본값은 로컬 개발용만 남겨 뒀습니다."
