# csquiz — CS 지식베이스 & 퀴즈 플랫폼

CS(컴퓨터 과학) 개념을 문서로 읽고, 퀴즈로 풀고, **틀린 문제는 잊어버릴 때쯤 다시 만나는**(망각곡선 복습) 학습 사이트입니다.

사이트 자체가 목적이 아니라, **만들면서 백엔드 CS 개념을 하나씩 실증하며 배우는 학습용 포트폴리오**입니다.
그래서 기능마다 "왜 이렇게 만들었는지"를 코드 주석과 설계 문서(ADR)로 남깁니다.

## 기술 스택

Java 21 · Spring Boot 3.4 · Spring Security(JWT) · Spring Data JPA + QueryDSL ·
MySQL 8(InnoDB) · Redis 7 · Flyway · springdoc-openapi · JUnit 5 ·
Anthropic Java SDK(문제·문서 생성) · GitHub Actions(CI/CD + 일일 생성 배치) · Docker ·
프론트: 순수 HTML/CSS/JS (빌드 도구 없이 Spring static 서빙)

---

## 1. 실행 방법

### 사전 준비 — MySQL과 Redis

둘 중 편한 방법으로 준비합니다. (앱은 `localhost:3306` MySQL, `localhost:6379` Redis에 붙습니다)

**방법 A — Docker (권장)**
```bash
docker compose up -d        # MySQL 8 + Redis 7 컨테이너 기동
```

**방법 B — 로컬 설치** (Docker가 없는 환경)
- MySQL 8을 설치하고 DB/계정을 만듭니다: DB `csquiz`, 사용자 `csquiz` / 비밀번호 `csquiz1234`
- Redis는 WSL에 설치해도 됩니다:
  ```bash
  wsl -u root -e sh -c "apt-get install -y redis-server"
  wsl -u root -e service redis-server start
  ```
  > WSL의 localhost 포트 중계가 불안정하면 `REDIS_HOST` 환경변수에 WSL IP를 넣어 실행하세요.
  > (Redis가 죽어 있어도 앱은 뜹니다 — 캐시는 2초 안에 포기하고 DB로 가는 fail-open 설계)

### 앱 실행

```bash
./gradlew bootRun           # Windows는 gradlew.bat bootRun
```

- 첫 실행 시 Flyway가 테이블을 만듭니다 (V1~V15). **스키마만 만들고 문서·문제는 넣지 않습니다** —
  콘텐츠 시드는 2026-08-12에 전부 걷어냈습니다(마이그레이션은 표의 모양만 관리한다는 규칙).
  문서와 문제는 관리 콘솔에서 직접 등록하거나 AI 생성 파이프라인으로 채웁니다.
- 접속: **http://localhost:8080**

### 기본 계정

| 용도 | 아이디 | 비밀번호 |
|---|---|---|
| 관리자 (자동 생성) | `admin` | `admin1234` (로컬 `bootRun` 한정) |
| 일반 사용자 | 사이트에서 회원가입 | — |

- 로그인은 **아이디**로 합니다(V12에서 이메일 → username으로 바꿨습니다).
- `admin1234`는 `build.gradle`이 **`bootRun`에만** 넣어 주는 개발용 값입니다.
  `java -jar`로 띄우면 `ADMIN_PASSWORD`·`JWT_SECRET` 환경변수가 없을 때 **부팅이 실패합니다** —
  공개 저장소에 적힌 값으로 관리자 로그인이 뚫리지 않도록 일부러 그렇게 뒀습니다(2026-08-20 보안 점검).

### 테스트

```bash
./gradlew test
```
- 단위 테스트 + 통합 테스트(로컬 MySQL 필요). Redis가 꺼져 있으면 관련 테스트는 실패가 아니라 **건너뜁니다**.
- Testcontainers를 쓰지 않고 **실제로 떠 있는 MySQL·Redis에 붙습니다.** CI도 같은 방식이라
  GitHub Actions에서 서비스 컨테이너로 둘을 띄웁니다(`.github/workflows/ci-cd.yml`).

---

## 2. 사이트 사용법 (학습 흐름)

권장 흐름: **첫 화면이 시키는 것부터 → 개념이 헷갈리면 문서 → 틀린 건 복습으로 자동 관리**

메뉴는 넷입니다(2026-08-29에 여섯에서 줄였습니다 — [docs/19](docs/19-ui-refresh.md)).

| 메뉴 | 주소 | 하는 일 |
|---|---|---|
| 오늘 | `/` · `/daily.html` | **첫 화면.** 오늘 할 일(복습·오늘의 퀴즈)만 줄로 띄우고, 다 했으면 조용해집니다. 첫날 안내·분야 추천·최근 개념 문서도 여기 |
| 문제 | `/problems.html` | 분야·난이도·상태로 걸러 **다음에 풀 문제 하나**를 고릅니다. "무작위로 10문제"(`/quiz.html`)도 이 안에서 시작 |
| 복습 | `/review.html` | **오늘의 복습**(때가 된 문제만) + 복습 현황, 화면 안 탭으로 **오답노트**(`/wrong-answers.html`)와 오갑니다. 메뉴 배지로 오늘 할 일 개수 표시 |
| 개념 문서 | `/documents.html` | CS 개념 정리 문서 (분야 필터, 마크다운 렌더링) |
| 관리 콘솔 ↗ | `/admin/index.html` | 문제·문서 CRUD, AI 검수, 주제 대기열, 대시보드 — ADMIN 전용(쿠키 게이트로 파일 자체를 막습니다) |

- **로그인 없이**: 첫 화면 소개, 개념 문서 읽기, 무작위 퀴즈 풀어 보기.
- **로그인 필요**: 채점(제출), 문제 목록, 복습, 오답노트, 오늘의 퀴즈.
  문제 목록이 로그인 전용인 이유는 줄마다 "맞혔나·언제 풀었나·복습할 때인가"가 붙기 때문입니다.
- 퀴즈 플레이어는 키보드 지원: `1`~`9` 보기 선택, `Enter` 제출/다음.
- 스트릭(연속 일수)은 없습니다. 하루 쉬면 0이 되는 지표는 1인 학습 도구에서 동기가 아니라
  이탈 사유라, "이번 주 몇 문제"로 바꿨습니다.

### 복습(망각곡선)은 어떻게 도나요?

- 문제를 **틀리면** 자동으로 복습 사다리에 올라갑니다. 별도 조작 불필요.
- 복습 간격: **1일 → 3일 → 7일 → 14일 → 30일**. 맞힐 때마다 다음 칸으로, 틀리면 처음으로.
- 5칸을 모두 통과하면 **졸업 🎓** — 더는 안 나옵니다(단, 나중에 또 틀리면 처음부터).
- "오늘의 복습"에는 **때가 된 문제만** 나오고, 오래 밀린 것부터 나옵니다.

## 3. 문제와 문서는 어디서 오나 (AI 생성 파이프라인)

이 사이트의 문제·개념 문서는 **GitHub Actions가 Claude로 만들어 저장소에 커밋**합니다.
앱을 켜면 그 파일을 검수 대기함으로 들여오고, **관리자가 승인한 것만** 사용자에게 나갑니다.

> ⏸ **지금 예약 배치는 꺼져 있습니다**(`llm.batch-enabled: false`, 2026-08-25부터).
> 중급 문제가 너무 어렵다는 판단으로 코퍼스를 다시 짓는 중이고, 옛 기준의 결과물이 섞이면
> 새 기준의 난이도를 잴 수 없기 때문입니다. 그동안 한 편씩 뽑는 것은 수동 실행의 `force=true`로 합니다.

- 예약은 매일 06:17(KST) `.github/workflows/llm-daily.yml` → 결과물은 `generated/YYYY-MM-DD.json`
- 앱 안 `@Scheduled`는 버렸습니다 — 개인 PC에서 도는 앱이라 그 시각에 PC가 꺼져 있으면
  그날 배치가 통째로 사라졌습니다(실제로 겪음 — [docs/14](docs/14-llm-batch-automation.md)).
- 손으로 한 편 뽑아 보기: `./gradlew generateDrafts -PdraftArgs="--domain=NETWORK --count=1"`
- 프롬프트를 고친 뒤 나아졌는지 재기: `./gradlew evalPrompt -PevalArgs="--label=after"`
- **둘 다 실제 API를 부릅니다(요금 발생).** `build`/`check`에 엮여 있지 않은 이유입니다.
- API 키는 저장소에 두지 않습니다. `<GRADLE_USER_HOME>\gradle.properties`의 `anthropicApiKey=`
  또는 `ANTHROPIC_API_KEY` 환경변수로만 넣습니다. 없으면 앱은 정상적으로 뜨고 생성만 503이 됩니다.

운영 방법 한 편으로 정리: [docs/16-llm-pipeline-operations](docs/16-llm-pipeline-operations.md)

## 4. API로 쓰기

- **Swagger UI**: http://localhost:8080/swagger-ui.html — 전체 API 명세와 실행 테스트
- 인증: `POST /api/auth/login`(아이디·비밀번호) → 받은 accessToken을 `Authorization: Bearer <토큰>` 헤더로
- 모든 응답은 공통 봉투 `{ success, data, error }`, 목록은 페이징(`page`/`size`)
- 요청 제한(rate limit): 인증 API 분당 5회(IP당), 일반 API 분당 60회(사용자당) — 초과 시 `429` + `Retry-After`
- 엔드포인트는 48개(사용자 15 · 관리자 33). 상세는 [docs/03-api-spec](docs/03-api-spec.md)

## 5. 프로젝트 구조 · 부가 자료

```
src/main/java/.../          # 도메인별 패키지: auth, user, document, quiz, review, dailyquiz,
                            #                  llm, tag, admin, global
src/main/resources/
  db/migration/             # Flyway 마이그레이션 (V1 스키마 ~ V15). 스키마만, 콘텐츠 시드 없음
  static/                   # 프론트엔드 (오늘·문제 목록·퀴즈 플레이어·복습·오답노트·문서·관리 콘솔)
db/perf/                    # 인덱스 효과 실측용 대용량 데이터 생성/정리 SQL (로드맵 1 실험)
generated/                  # 일일 배치가 만든 문제·문서 초안 (앱이 켜질 때 검수 대기함으로 흡수)
docs/                       # 설계 문서 · ADR · 개선 기록 · 면접 대본
.github/workflows/          # ci-cd.yml(빌드·테스트·GHCR push) · llm-daily.yml(일일 생성)
src/test/java/              # 단위 + 통합 테스트
```

- 설계 문서는 [docs/README](docs/README.md)에서 시작하세요 — 문서마다 "왜 그렇게 정했는지"가 함께 있습니다.
- 학습 로드맵: ① 인덱스+QueryDSL ✅ → ② Redis 캐싱+refresh 토큰 ✅ → ③ 요청 제한 ✅ →
  ④ 복습 추천(망각곡선) ✅ → ⑤ CI/CD ✅ → ⑥ 오늘의 퀴즈 ✅ → ⑦ LLM 생성 파이프라인 ✅
- 그 뒤로는 화면 쪽을 손봤습니다: 문제 목록 화면([docs/18](docs/18-problem-list-ui.md)),
  디자인 토큰·메뉴 개편([docs/19](docs/19-ui-refresh.md)).
