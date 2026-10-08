-- =====================================================================
-- V34__post_like.sql — 토론 글 추천
-- =====================================================================
-- 토론방의 글은 최신순과 댓글 수로만 줄을 세웠다. 좋은 풀이가 댓글 없이 묻히면
-- 위로 올릴 방법이 없었다.
--
-- 글에 추천 수 칸을 두지 않고 행으로 센다. 칸으로 두면 추천과 취소가 겹칠 때 수가 어긋나고,
-- "누가 눌렀는지"를 어차피 따로 적어야 같은 사람이 두 번 누르는 것을 막는다.
-- =====================================================================

CREATE TABLE post_like (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    post_id    BIGINT      NOT NULL,
    user_id    BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    -- 한 사람이 한 글을 한 번만 추천한다. post_id가 앞이라 글별 수 세기도 이 인덱스가 받는다.
    UNIQUE KEY uk_post_like (post_id, user_id),
    CONSTRAINT fk_post_like_post FOREIGN KEY (post_id) REFERENCES post (id) ON DELETE CASCADE,
    -- 탈퇴하면 추천도 거둔다. 글과 달리 남길 내용이 없다.
    CONSTRAINT fk_post_like_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
