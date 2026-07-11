# csquiz — CS 지식베이스 & 퀴즈 플랫폼

CS(컴퓨터 과학) 개념을 문서로 읽고, 퀴즈로 풀고, **틀린 문제는 잊어버릴 때쯤 다시 만나는**(망각곡선 복습) 학습 사이트입니다.

사이트 자체가 목적이 아니라, **만들면서 백엔드 CS 개념을 하나씩 실증하며 배우는 학습용 포트폴리오**입니다.
그래서 기능마다 "왜 이렇게 만들었는지"를 코드 주석과 설계 문서(ADR)로 남깁니다.

## 기술 스택

Java 21 · Spring Boot 3.4 · Spring Security(JWT) · Spring Data JPA + QueryDSL ·
MySQL 8(InnoDB) · Redis 7 · Flyway · springdoc-openapi · JUnit 5 ·
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

- 첫 실행 시 Flyway가 테이블 생성 + 시드 데이터(문서·문제)를 자동으로 넣습니다 (V1~V4).
- 접속: **http://localhost:8080**

### 기본 계정

| 용도 | 이메일 | 비밀번호 |
|---|---|---|
| 관리자 (자동 생성) | `admin@csquiz.local` | `admin1234` |
| 일반 사용자 | 사이트에서 회원가입 | — |

> 로컬 개발용 기본값입니다. 실 배포 시 `ADMIN_EMAIL`/`ADMIN_PASSWORD`/`JWT_SECRET` 환경변수로 반드시 교체하세요.

### 테스트

```bash
./gradlew test
```
- 단위 테스트 + 통합 테스트(로컬 MySQL 필요). Redis가 꺼져 있으면 관련 테스트는 실패가 아니라 **건너뜁니다**.

---

## 2. 사이트 사용법 (학습 흐름)

권장 흐름: **문서 읽기 → 퀴즈 풀기 → 틀린 건 복습으로 자동 관리**

| 메뉴 | 주소 | 하는 일 |
|---|---|---|
| 홈 | `/` | 시작 화면. 오늘 복습할 문제가 있으면 주황 카드로 알려줌 |
| 퀴즈 | `/quiz.html` | 도메인·난이도·유형·문제 수를 골라 시작 → **한 문제씩** 풀고 즉시 채점·해설 → 점수판 + 오답 복기 |
| 복습 | `/review.html` | **오늘의 복습**(때가 된 문제만) + 문제별 복습 현황. 메뉴 배지로 오늘 할 일 개수 표시 |
| 오답노트 | `/wrong-answers.html` | 틀린 적 있는 문제를 문제당 1건으로 모아 내 답·정답·해설 확인 |
| 문서 | `/documents.html` | CS 개념 정리 문서 (도메인 필터, 마크다운 렌더링) |
| 관리자 | `/admin.html` | 문제/문서 CRUD + 대시보드(가입자·제출 수·문제별 정답률) — ADMIN 전용 |

- 문제 열람·문서 읽기는 **로그인 없이** 가능, 채점(제출)·오답노트·복습은 **로그인 필요**.
- 퀴즈 플레이어는 키보드 지원: `1`~`9` 보기 선택, `Enter` 제출/다음.

### 복습(망각곡선)은 어떻게 도나요?

- 문제를 **틀리면** 자동으로 복습 사다리에 올라갑니다. 별도 조작 불필요.
- 복습 간격: **1일 → 3일 → 7일 → 14일 → 30일**. 맞힐 때마다 다음 칸으로, 틀리면 처음으로.
- 5칸을 모두 통과하면 **졸업 🎓** — 더는 안 나옵니다(단, 나중에 또 틀리면 처음부터).
- "오늘의 복습"에는 **때가 된 문제만** 나오고, 오래 밀린 것부터 나옵니다.

## 3. API로 쓰기

- **Swagger UI**: http://localhost:8080/swagger-ui.html — 전체 API 명세와 실행 테스트
- 인증: `POST /api/auth/login` → 받은 accessToken을 `Authorization: Bearer <토큰>` 헤더로
- 모든 응답은 공통 봉투 `{ success, data, error }`, 목록은 페이징(`page`/`size`)
- 요청 제한(rate limit): 인증 API 분당 5회(IP당), 일반 API 분당 60회(사용자당) — 초과 시 `429` + `Retry-After`

## 4. 프로젝트 구조 · 부가 자료

```
src/main/java/.../          # 도메인별 패키지: auth, user, document, quiz, review, admin, global
src/main/resources/
  db/migration/             # Flyway 마이그레이션 (V1 스키마 ~ V4 복습 테이블)
  static/                   # 프론트엔드 (홈·퀴즈 플레이어·복습·오답노트·관리자)
db/perf/                    # 인덱스 효과 실측용 대용량 데이터 생성/정리 SQL (로드맵 1 실험)
src/test/java/              # 단위 + 통합 테스트
```

- 설계 문서(`docs/` — 데이터 모델, API 명세, ADR 등)는 로컬에서만 관리합니다(.gitignore).
- 학습 로드맵: ① 인덱스+QueryDSL ✅ → ② Redis 캐싱+refresh 토큰 ✅ → ③ 요청 제한 ✅ → ④ 복습 추천(망각곡선) ✅ → ⑤ CI/CD+배포 (예정)
