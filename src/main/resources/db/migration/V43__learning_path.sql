-- =====================================================================
-- V43__learning_path.sql — 학습 로드맵(관리자가 고른 코스)과 "내가 시작한 로드맵"
-- =====================================================================
-- 설계는 docs/26. 로드맵은 두 종류다.
--   자동 코스  — 분야별 코스, 자바 코드 코스. 규칙으로 요청 때 만든다. 이 테이블에 없다
--   관리자 코스 — 분야를 넘나드는 순서(예: 입문자 코스). 아래 두 테이블에 저장한다
--
-- user_learning_path는 두 종류를 함께 가리켜야 해서 외래 키 대신 코스 키 문자열을 쓴다.
--   domain:NETWORK / code:java / custom:12
-- 관리자 코스가 지워져도 이 행은 남는다. 화면은 없는 코스 키를 건너뛴다.
-- =====================================================================

CREATE TABLE learning_path (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    title       VARCHAR(100) NOT NULL,
    description VARCHAR(500) NOT NULL,
    -- 로드맵 목록에서의 순서. 작을수록 위
    sort_order  INT          NOT NULL DEFAULT 0,
    -- 만드는 중인 코스는 사용자에게 보이지 않는다
    published   TINYINT(1)   NOT NULL DEFAULT 0,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 단계 하나 = 개념 문서 한 편 + 그 문서로 만든 문제
CREATE TABLE learning_path_step (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    path_id     BIGINT NOT NULL,
    -- 1부터. 순서를 바꾸면 코스의 단계를 통째로 다시 쓴다(LearningPath.replaceSteps)
    seq         INT    NOT NULL,
    document_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    -- 같은 문서를 한 코스에 두 번 넣지 못한다
    UNIQUE KEY uk_path_step_document (path_id, document_id),
    CONSTRAINT fk_path_step_path FOREIGN KEY (path_id) REFERENCES learning_path (id) ON DELETE CASCADE,
    -- 문서를 지우면 그 단계만 빠지고 코스는 남는다
    CONSTRAINT fk_path_step_document FOREIGN KEY (document_id) REFERENCES document (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE user_learning_path (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    user_id         BIGINT      NOT NULL,
    path_key        VARCHAR(40) NOT NULL,
    started_at      DATETIME(6) NOT NULL,
    -- 홈의 "이어서 하기"가 가장 최근에 본 코스를 고른다
    last_studied_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_path (user_id, path_key),
    -- 홈 카드 조회(내 코스 중 최근 것)를 받친다
    KEY idx_user_path_recent (user_id, last_studied_at),
    CONSTRAINT fk_user_path_user FOREIGN KEY (user_id) REFERENCES user (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
