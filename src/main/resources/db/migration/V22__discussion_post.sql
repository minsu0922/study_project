-- =====================================================================
-- V22__discussion_post.sql — 토론방 게시글
-- =====================================================================
-- 토론방(discussion)은 문제마다 하나이고 첫 글이 쓰일 때 생긴다(V21). 이제 방 안에
-- 제목과 본문이 있는 글을 올린다. 댓글(comment)을 글 아래로 옮기는 일은 다음 마이그레이션이 한다.
-- =====================================================================

CREATE TABLE post (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    discussion_id BIGINT        NOT NULL,
    user_id       BIGINT        NULL,                 -- 탈퇴하면 NULL. 글은 남긴다(comment와 같은 판단)
    title         VARCHAR(100)  NOT NULL,
    body          VARCHAR(5000) NOT NULL,             -- 서식 없는 일반 글. 줄바꿈만 살린다
    status        VARCHAR(10)   NOT NULL,             -- enum CommentStatus: VISIBLE / HIDDEN / DELETED
    created_at    DATETIME(6)   NOT NULL,
    edited_at     DATETIME(6)   NULL,
    PRIMARY KEY (id),
    -- 한 방의 글을 새 글부터 쪽 나눠 읽는다: 등치(discussion_id) → 정렬(created_at).
    KEY idx_post_discussion_created (discussion_id, created_at),
    CONSTRAINT fk_post_discussion FOREIGN KEY (discussion_id) REFERENCES discussion (id) ON DELETE CASCADE,
    CONSTRAINT fk_post_user       FOREIGN KEY (user_id)       REFERENCES `user` (id)     ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
