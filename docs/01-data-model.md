# 01. 데이터 모델 (MVP)

> DB: MySQL 8.x / InnoDB / `utf8mb4_0900_ai_ci`
> 스키마 관리: Flyway (`ddl-auto=validate`) — 이 문서의 DDL이 `V1__init.sql`의 기준이 된다.
> PK 전략: 전 테이블 `BIGINT AUTO_INCREMENT` 대리키.

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

---

## 테이블 정의

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

- 인덱스: `idx_problem_domain_difficulty (domain, difficulty)` — 퀴즈 필터 조회용
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

- 연결 테이블(DocumentTag/ProblemTag)·Choice: `ON DELETE CASCADE`
- Submission → User/Problem: `ON DELETE RESTRICT` (제출 이력은 보존)
- 모든 FK는 명시적 인덱스 보유 (InnoDB는 FK에 인덱스 자동 생성).
