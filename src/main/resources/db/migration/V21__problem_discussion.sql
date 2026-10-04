-- =====================================================================
-- V21__problem_discussion.sql — 문제별 토론(커뮤니티 1단계)
-- =====================================================================
-- 설계: docs/superpowers/specs/2026-10-03-problem-discussion-design.md
--
-- 댓글은 문제가 아니라 <토론방>에 묶인다. 뒤 단계에서 게시판이 생기면 discussion에
-- post_id를 더해 "게시글마다 방 하나"를 만들 뿐이고, comment와 comment_report는 그대로 쓴다.
-- =====================================================================

-- 글쓴이 표시 이름. 로그인 아이디를 그대로 보여 주면 남이 그 아이디로 로그인을 시도할 수 있다.
-- NULL 허용: 첫 댓글을 쓸 때 정한다. 기본 콜레이션(ai_ci)이라 대소문자만 다른 이름도 겹친다.
ALTER TABLE `user`
    ADD COLUMN nickname VARCHAR(12) NULL AFTER username,
    ADD CONSTRAINT uk_user_nickname UNIQUE (nickname);

CREATE TABLE discussion (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    -- NULL 허용: 3단계의 게시글 방을 위한 자리다. 유일 제약은 NULL을 여럿 허용한다.
    problem_id BIGINT      NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_discussion_problem (problem_id),
    -- CASCADE: 문제가 사라지면 토론이 가리킬 대상이 없다(V17의 제보와 같은 판단).
    CONSTRAINT fk_discussion_problem FOREIGN KEY (problem_id) REFERENCES problem (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE comment (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    discussion_id BIGINT        NOT NULL,
    user_id       BIGINT        NULL,                 -- 탈퇴하면 NULL. 토론의 흐름은 남긴다
    parent_id     BIGINT        NULL,                 -- NULL이면 댓글, 값이 있으면 답글(한 단계까지만)
    body          VARCHAR(1000) NOT NULL,
    status        VARCHAR(10)   NOT NULL,             -- enum CommentStatus: VISIBLE / HIDDEN / DELETED
    created_at    DATETIME(6)   NOT NULL,
    edited_at     DATETIME(6)   NULL,
    PRIMARY KEY (id),
    -- 한 방의 댓글을 시간순으로 쪽 나눠 읽는다: 등치(discussion_id, parent_id) → 정렬(created_at).
    KEY idx_comment_discussion_parent_created (discussion_id, parent_id, created_at),
    CONSTRAINT fk_comment_discussion FOREIGN KEY (discussion_id) REFERENCES discussion (id) ON DELETE CASCADE,
    CONSTRAINT fk_comment_user       FOREIGN KEY (user_id)       REFERENCES `user` (id)     ON DELETE SET NULL,
    CONSTRAINT fk_comment_parent     FOREIGN KEY (parent_id)     REFERENCES comment (id)    ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE comment_report (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    comment_id  BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL,
    reason      VARCHAR(30)  NOT NULL,               -- enum CommentReportReason
    detail      VARCHAR(500) NULL,
    status      VARCHAR(15)  NOT NULL,               -- enum ReportStatus: PENDING / ACCEPTED / DISMISSED
    admin_note  VARCHAR(500) NULL,
    created_at  DATETIME(6)  NOT NULL,
    resolved_at DATETIME(6)  NULL,
    PRIMARY KEY (id),
    -- 한 사람이 같은 글을 한 번만 신고한다. 동시 클릭도 DB가 막는다(V17과 같은 패턴).
    UNIQUE KEY uk_comment_report (comment_id, user_id),
    KEY idx_comment_report_status_created (status, created_at),
    CONSTRAINT fk_comment_report_comment FOREIGN KEY (comment_id) REFERENCES comment (id) ON DELETE CASCADE,
    CONSTRAINT fk_comment_report_user    FOREIGN KEY (user_id)    REFERENCES `user` (id)  ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
