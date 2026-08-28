# CS 지식베이스 & 문제풀이 플랫폼 — 설계 문서

CS(컴퓨터 과학) 개념을 정리해 두고 문제로 풀어 보는 학습 사이트를 만든다.
그런데 진짜 목적은 사이트 자체가 아니라, **이걸 만들면서 백엔드 CS 개념을 하나씩 직접 겪어 보는 것**이다.
그래서 기술적으로 뭔가를 정할 때마다 "왜 그렇게 정했는지"를 ADR(결정 기록)로 남긴다.
이 기록들은 나중에 **공부 노트이자 면접에서 말할 대본**이 된다.

## 이 폴더의 문서들

| 문서 | 한 줄 요약 |
|---|---|
| [01-data-model](01-data-model.md) | DB에 어떤 표(테이블)를 두고, 어떻게 연결하고, 채점은 어떤 규칙으로 하나 |
| [02-domain-enums](02-domain-enums.md) | 도메인(11)·난이도(3)·문제유형(4) 같은 "정해진 보기" 값들 |
| [03-api-spec](03-api-spec.md) | MVP API 7개의 요청/응답을 예시까지 상세히 |
| [04-response-format](04-response-format.md) | 성공/실패 응답을 항상 같은 모양으로 — 공통 응답 봉투 + 에러 코드 |
| [05-package-structure](05-package-structure.md) | 코드를 어떤 폴더 구조로 나눌지 |
| [06-security-jwt](06-security-jwt.md) | 로그인/인증을 실제로 어떻게 구현하나 (JWT·비밀번호·보호 경로) |
| [07-build-config](07-build-config.md) | 어떤 버전으로 깔고 어떤 설정값으로 돌리나 (재현용) |
| [09-rate-limiting](09-rate-limiting.md) | 요청 제한 — 토큰 버킷·429/Retry-After·fail-open (로드맵 3) |
| [10-review-recommendation](10-review-recommendation.md) | 복습 추천 — 망각곡선·간격 사다리·ReviewItem (로드맵 4) |
| [11-flyway-migrations](11-flyway-migrations.md) | Flyway로 DB 스키마 형상 관리 — V__/R__ 구분·멱등성 학습 노트 |
| [12-daily-quiz](12-daily-quiz.md) | 오늘의 퀴즈 — 데일리 세트 배합·지연 생성·스트릭 (로드맵 6) |
| [13-llm-problem-generation](13-llm-problem-generation.md) | LLM 문제 생성 — Claude 구조화 출력·초안 검수 (B안) |
| [14-llm-batch-automation](14-llm-batch-automation.md) | 일일 배치를 GitHub Actions로 — 왜 앱 안 `@Scheduled`를 버렸나 |
| [15-llm-concept-documents](15-llm-concept-documents.md) | 개념 문서 생성·검수, 그 문서를 근거로 한 문제 출제 |
| [16-llm-pipeline-operations](16-llm-pipeline-operations.md) | **LLM 파이프라인 운영 매뉴얼** — 설정·실행·점검·핵심 코드 지도 |
| [17-prompt-quality-and-eval](17-prompt-quality-and-eval.md) | **프롬프트 품질 개선과 평가 하네스** — 난이도 재정의, 검증기, `evalPrompt` |
| [GLOSSARY](GLOSSARY.md) | **용어집** — 이 저장소에서 쓰는 말과 쓰지 않는 말 (메우기·들여오기·현황 파일…) |
| [adr/](adr/README.md) | 기술 결정 기록 (Architecture Decision Records) |

> 💡 이 문서들만 보고도 프로젝트를 처음부터 다시 만들 수 있게 하는 걸 목표로 한다.
> "무엇을(01~03)", "어떤 규칙으로(04)", "어떻게 나눠서(05)", "어떻게 잠그고(06)", "어떤 버전·설정으로(07)".

## 기술 스택

Java 21 · Spring Boot 3.4.1 · MySQL 8(InnoDB) · Spring Data JPA + QueryDSL ·
Spring Security + JWT · Redis 7 · Flyway · springdoc-openapi · Docker Compose ·
JUnit5 + AssertJ + Mockito · GitHub Actions(CI/CD + 일일 생성 배치) · Anthropic Java SDK

> 정확한 버전과 설정값은 [07-build-config](07-build-config.md)에 정리돼 있다.

## MVP에서 만들 것 (여기까지만)

MVP = "일단 돌아가는 최소한". 욕심을 덜어내고 다음만 만든다.

- **로그인**: access 토큰만 쓴다. (자동 재로그인용 refresh 토큰은 로드맵2로 미룸 — [ADR-0001](adr/0001-access-token-only-for-mvp.md))
- **채점**: 객관식·OX·단답형 3종만 자동 채점. 서술형은 제외.
- **오답노트**: 따로 표를 만들지 않고, 제출 기록(Submission)을 조회해서 보여준다. ([ADR-0002](adr/0002-wrong-answers-query-based.md))
- **공통 응답 봉투 + 전역 예외처리**: 첫 API를 만들기 전에 먼저 고정.
- **Flyway로 DB 스키마 관리** (`ddl-auto=validate`).
- **목록 API는 페이징** (기본 20개씩).

## 로드맵 — **전부 완료** ✅

MVP가 돌아간 다음, 아래를 하나씩 붙이면서 그때마다 "왜 지금 필요한지"를 실감하며 배웠다.

| | 항목 | 배운 것 / 실측 | 문서 |
|---|---|---|---|
| 1 | 인덱스 + QueryDSL | **1.8초 → 16ms**, N+1 10방 → 2방 | [08](08-performance-experiments.md) |
| 2 | Redis 캐싱 + refresh 토큰 | 회전(GETDEL), fail-open | [06](06-security-jwt.md) |
| 3 | 요청 제한 | 토큰 버킷을 Lua로 직접 구현 | [09](09-rate-limiting.md) · [ADR-0003](adr/0003-token-bucket-rate-limiting.md) |
| 4 | 복습 추천(망각곡선) | 간격 사다리, 파생값은 저장 안 함 | [10](10-review-recommendation.md) · [ADR-0004](adr/0004-review-item-interval-ladder.md) |
| 5 | CI/CD | GitHub Actions → 빌드·테스트·GHCR push | [PROJECT_SUMMARY](PROJECT_SUMMARY.md) |
| 6 | 오늘의 퀴즈 | 세트 배합, 지연 생성, 스트릭 | [12](12-daily-quiz.md) · [ADR-0005](adr/0005-daily-quiz-set.md) |
| 7 | LLM 문제 생성 | 초안 격리, 구조화 출력, 이중 검증 | [13](13-llm-problem-generation.md) · [ADR-0006](adr/0006-llm-problem-generation.md) |
| 7+ | **일일 배치 → GitHub Actions** | `@Scheduled`가 한 번도 안 돈 것을 실측으로 발견 | [14](14-llm-batch-automation.md) |
| 7++ | **개념 문서 생성 + 문서 기반 출제** | 4일 주기, 자동 검증, 단일 경로 원칙 | [15](15-llm-concept-documents.md) · [16](16-llm-pipeline-operations.md) |
| 7+++ | **프롬프트 품질 · 평가 하네스** | 규칙 충돌 해소, 검증기 10종, 회귀 측정 | [17](17-prompt-quality-and-eval.md) |

### 지금 돌아가는 것

매일 06:17(KST)에 GitHub Actions가 개념 문서 또는 문제를 만들어 저장소에 커밋하고,
로컬 앱을 켜면 검수 대기함으로 들여온다. 승인해야만 사용자에게 나간다.
운영 방법은 [16-llm-pipeline-operations](16-llm-pipeline-operations.md).

## 만드는 순서 (제안)

1. `build.gradle` 정리 (MVP 의존성 + Flyway) — ✅ 완료
2. `docker-compose.yml`(MySQL 8) + `application.yml` — ✅ 완료
3. `global`: 공통 응답 봉투 + 전역 예외처리 + 공용 enum — ✅ 완료
4. `User` 엔티티 + Flyway `V1__init.sql`
5. 회원가입/로그인 API + Security/JWT ([06](06-security-jwt.md) 참고)
