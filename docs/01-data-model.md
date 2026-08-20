# 01. 데이터 모델

> DB: MySQL 8.x / InnoDB / `utf8mb4_0900_ai_ci`
> 스키마 관리: Flyway (`ddl-auto=validate`) — 마이그레이션 이력은 [11-flyway-migrations](11-flyway-migrations.md).
> PK 전략: 전 테이블 `BIGINT AUTO_INCREMENT` 대리키(단 하나 예외 — `imported_draft_file`).
>
> **구성**: MVP 8개(`V1`) + 로드맵에서 추가된 6개(`V4`~`V9`) + 통계 뷰 1개(`R__`) = **표 14개 + 뷰 1개**

## 쉽게 말하면

데이터를 저장할 **표(테이블)를 어떻게 만들지** 정하는 문서다. 📊
엑셀 시트를 여러 개 만든다고 생각하면 된다 — 회원 시트(User), 문제 시트(Problem), 제출 기록 시트(Submission) 등.
그리고 시트끼리 "이 제출은 누가 한 건지" 같은 연결선(관계)을 긋는다. 아래 규칙 두 가지만 미리 알아 두자:

- **대리키(surrogate key)**: 모든 표는 사람이 정한 값 대신 `id`(자동 증가 숫자)를 대표 번호로 쓴다. 이메일처럼 바뀔 수 있는 값을 대표로 쓰면 나중에 곤란해서다.
- **인덱스(index)**: 자주 찾는 열에 붙이는 "책 뒤 색인" 같은 것. 있으면 검색이 빠르다. 어디에 왜 붙였는지가 이 문서의 핵심 중 하나.

---

## ERD (논리)

```
                ┌──────────────┐
                │     User     │
                └──────┬───────┘
                       │ 1
                       │
                       │ N
                ┌──────┴───────┐        ┌──────────────┐
                │  Submission  │─N────1─│   Problem    │
                └──────────────┘        └──┬────────┬──┘
                                       1 │        │ 1
                                         │        │
                                       N │        │ N
                                  ┌──────┴──┐  ┌──┴────────┐
                                  │ Choice  │  │ProblemTag │
                                  └─────────┘  └────┬──────┘
                                                    │ N
                                                    │
                        ┌──────────────┐            │ 1
                        │   Document   │       ┌────┴───┐
                        └──────┬───────┘       │  Tag   │
                             1 │               └────┬───┘
                               │ N              1 │
                        ┌──────┴───────┐    N     │
                        │ DocumentTag  │─N────────┘
                        └──────────────┘
```

- `Tag`는 `Document`·`Problem` 양쪽과 M:N → 태그로 "이 문서와 연결된 문제" 탐색 가능.
- `Submission`은 오답노트의 데이터 소스 (별도 오답 테이블 없음, ADR-0002 참고).

### 로드맵에서 붙은 것들

```
   User ─1──N─ ReviewItem ──N──1─ Problem       복습 사다리 (V4)
   User ─1──N─ DailyQuiz ─1─N─ DailyQuizItem    오늘의 퀴즈 (V5)
                                  └──1─ Submission

   [AI 생성 — 정식 테이블과 FK로 연결되지 않는다]
   GeneratedProblemDraft   ┐  승인되면 Problem / Document로 복사된다.
   GeneratedDocumentDraft  ┤  approved_*_id는 FK가 아니라 "이력"이다.
   ImportedDraftFile       ┘  (V6·V7·V8)

   Problem.document_slug ┄┄> Document.slug   느슨한 연결 (V9, FK 아님)
```

**초안 테이블이 정식 테이블과 FK로 안 묶인 이유**가 이 설계의 핵심이다 → [아래](#ai-생성-초안-v6v8).

---

## 테이블 정의 (MVP · `V1__init.sql`)

### User
| 컬럼 | 타입 | 제약 | 비고 |
|---|---|---|---|
| id | BIGINT | PK, AUTO_INCREMENT | |
| email | VARCHAR(255) | UNIQUE, NOT NULL | 로그인 ID |
| password_hash | VARCHAR(255) | NOT NULL | BCrypt |
| role | VARCHAR(20) | NOT NULL | enum `USER`/`ADMIN`, 기본 `USER` |
| created_at | DATETIME(6) | NOT NULL | |

- 인덱스: `uk_user_email (email)`
- 비밀번호 정책(검증): 8자 이상, 영문+숫자 포함 (Validation 계층에서 검사, DB엔 해시만).

### Document
| 컬럼 | 타입 | 제약 | 비고 |
|---|---|---|---|
| id | BIGINT | PK | |
| domain | VARCHAR(30) | NOT NULL | enum, `02-domain-enums` 참고 |
| title | VARCHAR(200) | NOT NULL | |
| slug | VARCHAR(150) | UNIQUE, NOT NULL | 영문 수동 입력 (예 `osi-7-layer`) |
| content_md | LONGTEXT | NOT NULL | 마크다운 본문 |
| source | VARCHAR(500) | NULL | 출처 URL/서적 |
| created_at | DATETIME(6) | NOT NULL | |
| updated_at | DATETIME(6) | NOT NULL | |

- 인덱스: `uk_document_slug (slug)`, `idx_document_domain (domain)`
- 한글 풀텍스트(`FULLTEXT ... WITH PARSER ngram`)는 검색 레이어에서 도입 → ADR 대상, MVP 제외.

### Tag
| 컬럼 | 타입 | 제약 |
|---|---|---|
| id | BIGINT | PK |
| name | VARCHAR(50) | UNIQUE, NOT NULL |

- 인덱스: `uk_tag_name (name)`

### DocumentTag (M:N 연결)
| 컬럼 | 타입 | 제약 |
|---|---|---|
| document_id | BIGINT | FK → Document(id), NOT NULL |
| tag_id | BIGINT | FK → Tag(id), NOT NULL |

- PK: 복합 `(document_id, tag_id)`
- 인덱스: `idx_documenttag_tag (tag_id)` — 태그→문서 역방향 조회용
- FK: `ON DELETE CASCADE`

### Problem
| 컬럼 | 타입 | 제약 | 비고 |
|---|---|---|---|
| id | BIGINT | PK | |
| domain | VARCHAR(30) | NOT NULL | enum |
| difficulty | VARCHAR(15) | NOT NULL | enum `BEGINNER`/`INTERMEDIATE`/`ADVANCED` |
| type | VARCHAR(20) | NOT NULL | enum `MULTIPLE_CHOICE`/`OX`/`SHORT_ANSWER`/`ESSAY` |
| question | TEXT | NOT NULL | |
| answer | VARCHAR(500) | NULL | 채점 기준값 (타입별 규칙 아래) |
| explanation | TEXT | NULL | 해설 |
| created_at | DATETIME(6) | NOT NULL | |
| document_slug | VARCHAR(150) | NULL | **V9 추가** — 이 문제의 근거 개념 문서. FK 아님 |

- 인덱스: `idx_problem_domain_difficulty (domain, difficulty)` — 퀴즈 필터 조회용
- **`document_slug`를 FK로 걸지 않은 이유**: 문제 초안이 **문서 승인보다 먼저** 존재할 수 있다.
  AI가 문서와 문제를 따로 만들고 사람이 각각 검수하므로, 문제를 먼저 승인하는 날이 생긴다.
  FK면 그 순간 저장이 실패한다. 또 문서를 지워도 문제는 남아야 한다(링크만 사라지면 된다) —
  그래서 조회할 때 서버가 문서 존재를 확인하고, 없으면 링크를 아예 안 내려보낸다.
- 인덱스를 안 붙인 것도 의도적이다. 읽는 방향이 **문제 → 문서** 한쪽뿐이고, 그 조회는
  이미 확보한 slug로 `document.slug`(UNIQUE)를 찾는 것이라 이 컬럼에는 인덱스가 필요 없다.
- **`answer` 컬럼 타입별 규칙**:
  - `MULTIPLE_CHOICE` → `NULL` (정답은 Choice.is_correct로 판정)
  - `OX` → `"O"` 또는 `"X"`
  - `SHORT_ANSWER` → 복수 정답을 `|`로 구분 (예 `"tcp|transmission control protocol"`)
  - `ESSAY` → MVP 미사용 (enum엔 존재하나 시드/채점 제외)

### Choice (객관식 보기)
| 컬럼 | 타입 | 제약 | 비고 |
|---|---|---|---|
| id | BIGINT | PK | |
| problem_id | BIGINT | FK → Problem(id), NOT NULL | |
| text | VARCHAR(500) | NOT NULL | 보기 내용 |
| is_correct | BOOLEAN | NOT NULL | 정답 여부 |
| seq | INT | NOT NULL | 보기 순서 (1..N) |

- 인덱스: `idx_choice_problem (problem_id)`
- FK: `ON DELETE CASCADE`
- MVP는 단일 정답(정답 Choice 1개) 가정.

### ProblemTag (M:N 연결)
| 컬럼 | 타입 | 제약 |
|---|---|---|
| problem_id | BIGINT | FK → Problem(id), NOT NULL |
| tag_id | BIGINT | FK → Tag(id), NOT NULL |

- PK: 복합 `(problem_id, tag_id)`
- 인덱스: `idx_problemtag_tag (tag_id)`
- FK: `ON DELETE CASCADE`

### Submission (답안 제출 = 오답노트 데이터 소스)
| 컬럼 | 타입 | 제약 | 비고 |
|---|---|---|---|
| id | BIGINT | PK | |
| user_id | BIGINT | FK → User(id), NOT NULL | |
| problem_id | BIGINT | FK → Problem(id), NOT NULL | |
| user_answer | VARCHAR(500) | NOT NULL | 제출값 (객관식=선택 choice id, OX="O/X", 단답형=원문) |
| is_correct | BOOLEAN | NOT NULL | 채점 결과 |
| submitted_at | DATETIME(6) | NOT NULL | |

- 인덱스: `idx_submission_user_correct (user_id, is_correct, submitted_at)` — 오답노트 조회 최적화 (로드맵 1에서 효과 측정)
- 재제출 허용 (같은 문제 여러 Submission 행 생성) — MVP는 이력 누적 방식.

---

## 로드맵에서 추가된 테이블

### ReviewItem — 복습 사다리 (V4, 로드맵 4)

틀린 문제를 **언제 다시 낼지**를 들고 있는 표. 설계는 [10-review-recommendation](10-review-recommendation.md).

| 컬럼 | 타입 | 비고 |
|---|---|---|
| id | BIGINT | PK |
| user_id / problem_id | BIGINT | FK, `ON DELETE CASCADE` |
| stage | INT | 사다리 칸(0..4). 맞히면 +1, 틀리면 0으로 |
| status | VARCHAR(15) | `LEARNING` / `GRADUATED` — **2가지뿐** |
| review_count | INT | 사다리에 오른 뒤 푼 횟수(통계용) |
| next_review_at | DATETIME(6) | 다음 복습 예정 시각 |

- `uk_reviewitem_user_problem (user_id, problem_id)` — 한 사람이 한 문제에 한 줄
- `idx_reviewitem_user_due (user_id, status, next_review_at)` — "오늘 복습할 것" 조회용

**`status`에 `DUE`가 없는 이유**: "복습할 때가 됐다"는 저장하는 값이 아니라
`next_review_at <= now()`로 **계산되는 값**이다. 컬럼으로 두면 시간이 흐를 때마다
누군가 상태를 바꿔 줘야 하고(배치 필요), 그 배치가 안 돌면 조용히 틀린 값이 남는다.

**V4는 데이터도 옮긴다.** 기존 `submission`에서 "가장 최근 제출이 오답인" 조합을 찾아
사다리 0칸으로 넣는다(`ROW_NUMBER() OVER (PARTITION BY ...)`). 기능을 켠 날부터가 아니라
**그동안 쌓인 오답도 복습 대상**이 되게 하려는 것 — 스키마 변경과 데이터 이행이 한 마이그레이션에 있는 예.

### DailyQuiz / DailyQuizItem — 오늘의 퀴즈 (V5, 로드맵 6)

하루치 10문제 세트. 배합 규칙은 [12-daily-quiz](12-daily-quiz.md).

**DailyQuiz**

| 컬럼 | 타입 | 비고 |
|---|---|---|
| id | BIGINT | PK |
| user_id | BIGINT | FK, CASCADE |
| quiz_date | DATE | 이 세트가 속한 날 |
| completed_at | DATETIME(6) NULL | 다 푼 시각. **NULL = 미완료** |

- `uk_dailyquiz_user_date (user_id, quiz_date)` — **하루에 한 세트**를 DB가 보장한다.
  동시에 두 번 요청해도 두 번째는 UNIQUE 위반으로 막힌다(애플리케이션 검사만 믿지 않는다).

**DailyQuizItem**

| 컬럼 | 타입 | 비고 |
|---|---|---|
| daily_quiz_id / problem_id | BIGINT | FK |
| seq | INT | 세트 안 표시 순서(1..N) |
| source | VARCHAR(10) | `REVIEW`/`WEAK`/`NEW`/`GENERAL` — **어느 배합 칸으로 뽑혔나** |
| submission_id | BIGINT NULL | 이 세트에서 푼 제출. NULL = 아직 안 풂 |

- `uk_dailyquizitem_quiz_problem` — 한 세트에 같은 문제 두 번 금지
- `problem`·`submission` 쪽 FK는 `RESTRICT` — 세트에 들어간 문제·제출은 함부로 못 지운다

**`source`를 기록해 두는 이유**: 나중에 "복습 칸으로 나온 문제의 정답률"처럼
**배합 규칙 자체를 평가**할 수 있다. 안 남기면 세트가 만들어진 순간 그 정보가 사라진다.

### AI 생성 초안 (V6~V8)

[13](13-llm-problem-generation.md)·[15](15-llm-concept-documents.md) 참고.

**GeneratedProblemDraft (V6)** / **GeneratedDocumentDraft (V8)** — 구조가 거의 같다.

| 컬럼 | 비고 |
|---|---|
| 내용 컬럼 | 문제: `domain`·`difficulty`·`type`·`question`·`answer`·`explanation`·`choices_json`·`document_slug`(V9)<br>문서: `domain`·`title`·`slug`·`content_md`·`tags_json` |
| status | `PENDING` → `APPROVED` \| `REJECTED` (단방향) |
| model | 생성에 쓴 모델명 — **모델별 승인율 비교용** |
| reject_reason | 거절 사유 — 다음 생성 프롬프트에 되먹인다 |
| approved_problem_id / approved_document_id | **FK 아님**. 이력이라 원본을 지워도 남아야 한다 |

- `idx_draft_status_created (status, created_at)` — 검수 화면이 "PENDING을 오래된 순"으로 읽는다

**왜 `problem`에 `status` 컬럼을 얹지 않고 표를 따로 뒀나** (ADR-0006)

얹었다면 기존 **모든 조회에 `WHERE status='APPROVED'`가 번진다.** 퀴즈 출제, 데일리 세트,
복습, 통계… 한 군데만 빠뜨려도 **미검수 문제가 사용자에게 나간다.** 표를 나누면
"검수 안 된 문제는 `problem`에 아예 존재하지 않는" 구조가 되어, 빠뜨릴 곳 자체가 없다.

- `choices_json`·`tags_json`을 정규화하지 않은 이유: 초안은 채점에 안 쓰이므로 정규화 이득이 없고,
  문서 태그는 **아직 없는 태그**일 수 있어 정규화하면 승인도 안 한 문서 때문에 `tag` 표가 오염된다.
- `slug`에 UNIQUE를 안 건 이유: 초안 단계에서 막으면 같은 주제를 다시 뽑아 비교해 볼 수 없다.
  진짜 유일성은 `document.slug`가 지킨다.

**ImportedDraftFile (V7)** — "이 파일은 이미 가져왔다" 도장

| 컬럼 | 타입 | 비고 |
|---|---|---|
| filename | VARCHAR(100) | **PK** — 이 프로젝트에서 유일한 자연키 PK |
| imported_at | DATETIME(6) | |
| draft_count | INT | 그 파일에서 건진 초안 수 |

- **파일명이 곧 그 배치의 신원**이라 자연키를 PK로 썼다. 대리키 + UNIQUE도 가능하지만
  그러면 이 표의 유일한 목적(중복 방지)이 PK가 아닌 부가 제약에 걸린다.
  자연키 PK면 애플리케이션 검사가 빠져나가도 **INSERT가 실패한다**(이중 안전장치).
- **초안이 0건이어도 행을 남긴다.** 모델이 규약을 다 어겨 한 문제도 못 건진 파일을
  매 부팅마다 다시 읽고 다시 버리는 낭비를 막는다.
- 문서 파일은 키에 폴더를 붙인다(`documents/2026-08-13.json`) — 문제 파일과 이름이 같아
  한쪽이 **조용히 건너뛰어지는** 사고를 막기 위해서다(docs/15).

### domain_stats — 통계 뷰 (`R__domain_stats_view.sql`)

도메인별 정답률. **테이블이 아니라 뷰**라 PK가 없고 수정도 안 된다.

- 집계(JOIN·GROUP BY·정답률 계산)를 전부 뷰가 갖는다 → 앱·DB 콘솔·관리도구 어디서 봐도 **같은 값**
- `R__`은 반복 실행 마이그레이션이라 파일이 바뀌면 자동으로 다시 만들어진다
- 제출 0건인 도메인은 INNER JOIN이라 **행 자체가 없다** — "0%"와 "데이터 없음"이 자연히 구분된다
- JPA 엔티티로 매핑하지 않고 `JdbcClient`로 직접 읽는다(읽기 전용 SQL → DTO가 정직하다)

---

## 채점 로직 요약 (type 분기)

| type | 사용자 제출값 | 채점 방식 |
|---|---|---|
| MULTIPLE_CHOICE | 선택한 `choice_id` | 해당 Choice의 `is_correct == true` |
| OX | `"O"` / `"X"` | `Problem.answer` 와 대소문자 무시 비교 |
| SHORT_ANSWER | 자유 텍스트 | `answer.split("|")` 중 하나와 `trim().toLowerCase()` 후 일치 |
| ESSAY | — | MVP 미지원 |

상세는 `03-api-spec.md`의 `POST /api/quiz/submit` 참고.

---

## 참조 무결성 정책

| 관계 | 정책 | 왜 |
|---|---|---|
| DocumentTag·ProblemTag·Choice | `CASCADE` | 부모 없이 의미가 없는 부속물 |
| ReviewItem → User·Problem | `CASCADE` | 문제가 사라지면 복습할 것도 없다 |
| DailyQuiz → User | `CASCADE` | |
| Submission → User·Problem | `RESTRICT` | 제출 이력은 보존 |
| DailyQuizItem → Problem·Submission | `RESTRICT` | 세트에 들어간 것은 함부로 못 지운다 |
| 초안 → problem·document | **FK 없음** | 이력이라 원본이 사라져도 남아야 한다 |
| `problem.document_slug` → `document.slug` | **FK 없음** | 문제가 문서보다 먼저 승인될 수 있다 |

- 모든 FK는 명시적 인덱스 보유 (InnoDB는 FK에 인덱스 자동 생성).
- **덤으로 알게 된 것**: 로드맵 1에서 성능 실험을 하며 인덱스를 지웠더니
  FK 제약이 그 인덱스에 기대고 있어 삭제가 거부됐다. "인덱스는 조회 최적화 수단"이라고만
  알고 있었는데 **참조 무결성의 버팀목이기도 하다**는 것을 실물로 확인한 사례(docs/08).
