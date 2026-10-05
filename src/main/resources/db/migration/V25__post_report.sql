-- =====================================================================
-- V25__post_report.sql — 글도 신고할 수 있게 한다
-- =====================================================================
-- 신고함은 하나다. 글 신고용 표를 따로 만들면 관리자가 두 목록을 오가야 하고 판정 코드도 둘이 된다.
-- 그래서 comment_report가 댓글과 글 가운데 하나를 가리키게 한다 — comment_id와 post_id 중 하나만 채운다.
--
-- "하나만 채운다"를 CHECK로 걸지 않는다. MySQL은 외래키 참조 동작(ON DELETE CASCADE)이 걸린
-- 칸을 CHECK에 쓰지 못한다. 규칙은 CommentReport의 생성 메서드 둘이 지킨다.
-- =====================================================================

ALTER TABLE comment_report
    MODIFY comment_id BIGINT NULL,
    ADD COLUMN post_id BIGINT NULL AFTER comment_id,
    -- 한 사람이 같은 글을 한 번만 신고한다(uk_comment_report와 같은 규칙). NULL은 겹치지 않는다.
    ADD UNIQUE KEY uk_post_report (post_id, user_id),
    ADD CONSTRAINT fk_comment_report_post FOREIGN KEY (post_id) REFERENCES post (id) ON DELETE CASCADE;
