-- =====================================================================
-- V1__init.sql — MVP 전체 스키마 초기 생성
-- =====================================================================
-- 이 파일은 Flyway가 앱 부팅 시 자동 실행한다(classpath:db/migration).
-- 한 번 적용되면 flyway_schema_history 에 기록되어 다시 실행되지 않는다.
--
-- 기준 문서: docs/01-data-model.md (컬럼/인덱스/FK 정책이 이 문서와 1:1로 일치해야 함)
-- 원칙:
--   - PK는 전 테이블 BIGINT AUTO_INCREMENT 대리키
--   - 문자셋/콜레이션: utf8mb4 / utf8mb4_0900_ai_ci (한글·이모지)
--   - enum 값은 VARCHAR로 저장(@Enumerated(EnumType.STRING)) — 순서 변경에 안전
--   - 부모 테이블을 먼저 만들고 자식(FK) 테이블을 나중에 만든다
-- =====================================================================

-- ---------------------------------------------------------------------
-- User : 회원. 로그인 ID는 email, 비밀번호는 BCrypt 해시만 저장.
-- ---------------------------------------------------------------------
CREATE TABLE `user` (


    id            BIGINT       NOT NULL AUTO_INCREMENT,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,                 -- BCrypt 해시 (원문 비번은 저장 안 함)
    role          VARCHAR(20)  NOT NULL,                 -- enum USER / ADMIN
    created_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_email (email)                     -- 로그인 ID 중복 방지 + 조회 인덱스 겸용
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- Document : CS 지식 문서(마크다운 본문 포함).
-- ---------------------------------------------------------------------
CREATE TABLE document (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    domain      VARCHAR(30)  NOT NULL,                   -- enum Domain
    title       VARCHAR(200) NOT NULL,
    slug        VARCHAR(150) NOT NULL,                   -- URL용 영문 식별자 (예 osi-7-layer)
    content_md  LONGTEXT     NOT NULL,                   -- 마크다운 본문
    source      VARCHAR(500) NULL,                       -- 출처(URL/서적), 선택
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_document_slug (slug),
    KEY idx_document_domain (domain)                     -- 도메인 필터 조회용
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- Tag : 문서/문제에 공통으로 붙는 태그.
-- ---------------------------------------------------------------------
CREATE TABLE tag (
    id   BIGINT      NOT NULL AUTO_INCREMENT,
    name VARCHAR(50) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tag_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- DocumentTag : Document ↔ Tag M:N 연결. 복합 PK.
-- ---------------------------------------------------------------------
CREATE TABLE document_tag (
    document_id BIGINT NOT NULL,
    tag_id      BIGINT NOT NULL,
    PRIMARY KEY (document_id, tag_id),
    KEY idx_documenttag_tag (tag_id),                    -- 태그→문서 역방향 조회용
    CONSTRAINT fk_documenttag_document FOREIGN KEY (document_id) REFERENCES document (id) ON DELETE CASCADE,
    CONSTRAINT fk_documenttag_tag      FOREIGN KEY (tag_id)      REFERENCES tag (id)      ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- Problem : 문제. 채점 기준값(answer)은 타입별 규칙이 다름(docs/01 참고).
-- ---------------------------------------------------------------------
CREATE TABLE problem (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    domain      VARCHAR(30) NOT NULL,                    -- enum Domain
    difficulty  VARCHAR(15) NOT NULL,                    -- enum Difficulty
    type        VARCHAR(20) NOT NULL,                    -- enum ProblemType
    question    TEXT        NOT NULL,
    answer      VARCHAR(500) NULL,                       -- 객관식=NULL, OX="O"/"X", 단답형="a|b"
    explanation TEXT        NULL,
    created_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_problem_domain_difficulty (domain, difficulty)  -- 퀴즈 필터 조회용 복합 인덱스
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- Choice : 객관식 보기. Problem에 종속(삭제 시 함께 삭제).
--   `text`는 MySQL에서 데이터타입 키워드라 백틱으로 감싼다.
-- ---------------------------------------------------------------------
CREATE TABLE choice (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    problem_id BIGINT       NOT NULL,
    `text`     VARCHAR(500) NOT NULL,                    -- 보기 내용
    is_correct BOOLEAN      NOT NULL,                    -- 정답 여부(MVP는 정답 1개 가정)
    seq        INT          NOT NULL,                    -- 보기 순서(1..N)
    PRIMARY KEY (id),
    KEY idx_choice_problem (problem_id),
    CONSTRAINT fk_choice_problem FOREIGN KEY (problem_id) REFERENCES problem (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- ProblemTag : Problem ↔ Tag M:N 연결. 복합 PK.
-- ---------------------------------------------------------------------
CREATE TABLE problem_tag (
    problem_id BIGINT NOT NULL,
    tag_id     BIGINT NOT NULL,
    PRIMARY KEY (problem_id, tag_id),
    KEY idx_problemtag_tag (tag_id),
    CONSTRAINT fk_problemtag_problem FOREIGN KEY (problem_id) REFERENCES problem (id) ON DELETE CASCADE,
    CONSTRAINT fk_problemtag_tag     FOREIGN KEY (tag_id)     REFERENCES tag (id)     ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- Submission : 답안 제출 이력 = 오답노트의 데이터 소스(ADR-0002).
--   제출 이력은 보존해야 하므로 User/Problem FK는 RESTRICT(부모 삭제 차단).
-- ---------------------------------------------------------------------
CREATE TABLE submission (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    user_id      BIGINT       NOT NULL,
    problem_id   BIGINT       NOT NULL,
    user_answer  VARCHAR(500) NOT NULL,                  -- 객관식=choiceId, OX="O"/"X", 단답형=원문
    is_correct   BOOLEAN      NOT NULL,                  -- 채점 결과
    submitted_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_submission_user_correct (user_id, is_correct, submitted_at),  -- 오답노트 조회 최적화
    CONSTRAINT fk_submission_user    FOREIGN KEY (user_id)    REFERENCES `user` (id)  ON DELETE RESTRICT,
    CONSTRAINT fk_submission_problem FOREIGN KEY (problem_id) REFERENCES problem (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
