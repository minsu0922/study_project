-- =====================================================================
-- V23__comment_under_post.sql — 댓글을 토론방이 아니라 게시글 아래에 단다
-- =====================================================================
-- V21의 댓글은 방(discussion)에 바로 달렸다. 이제 방 안에 글(post, V22)이 있고 댓글은 글에 달린다.
--
-- 옛 댓글은 옮길 글이 없어 지운다. 어느 글 아래에도 넣을 수 없는 행이라 post_id NOT NULL을
-- 걸 수 없다. 공개 전이라 실제 사용자의 글은 없다(2026-10-05 사용자 확인).
-- 글이 하나도 없는 방도 함께 지운다 — 방은 글이 쓰인 문제에만 있다.
-- =====================================================================

DELETE FROM comment;            -- comment_report는 외래키 CASCADE로 함께 지워진다
DELETE FROM discussion WHERE id NOT IN (SELECT discussion_id FROM post);

ALTER TABLE comment
    DROP FOREIGN KEY fk_comment_discussion,
    DROP KEY idx_comment_discussion_parent_created,
    DROP COLUMN discussion_id,
    ADD COLUMN post_id BIGINT NOT NULL AFTER id,
    -- 한 글의 댓글을 시간순으로 쪽 나눠 읽는다: 등치(post_id, parent_id) → 정렬(created_at).
    ADD KEY idx_comment_post_parent_created (post_id, parent_id, created_at),
    -- CASCADE: 글 행이 사라지면 댓글이 가리킬 대상이 없다. 화면의 "삭제"는 행을 남기므로 여기 오지 않는다.
    ADD CONSTRAINT fk_comment_post FOREIGN KEY (post_id) REFERENCES post (id) ON DELETE CASCADE;
