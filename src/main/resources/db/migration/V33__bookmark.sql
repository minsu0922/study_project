-- =====================================================================
-- V33__bookmark.sql — 다시 볼 문제·문서를 담아 두는 북마크
-- =====================================================================
-- 다시 보고 싶은 문제나 문서를 표시해 둘 방법이 없어 매번 검색으로 다시 찾아야 했다.
--
-- 대상 칸을 둘(problem_id, document_id)로 둔 이유: 칸 하나에 종류와 id를 함께 적으면
-- 외래 키를 걸 수 없어, 지워진 문제를 가리키는 북마크가 남는다.
-- 한 행은 둘 중 정확히 하나만 채운다. CHECK로 못 박지 않은 이유: MySQL은 ON DELETE 동작이
-- 걸린 외래 키 칸을 CHECK에 쓰지 못하게 한다(오류 3823). 넣는 문장이 둘뿐이라 거기서 지킨다.
-- =====================================================================

CREATE TABLE bookmark (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     BIGINT      NOT NULL,
    problem_id  BIGINT      NULL,
    document_id BIGINT      NULL,
    created_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    -- 같은 것을 두 번 담지 못하게 한다. NULL은 서로 겹치지 않으므로 두 제약이 서로를 막지 않는다.
    UNIQUE KEY uk_bookmark_user_problem (user_id, problem_id),
    UNIQUE KEY uk_bookmark_user_document (user_id, document_id),
    CONSTRAINT fk_bookmark_user FOREIGN KEY (user_id) REFERENCES user (id) ON DELETE CASCADE,
    CONSTRAINT fk_bookmark_problem FOREIGN KEY (problem_id) REFERENCES problem (id) ON DELETE CASCADE,
    CONSTRAINT fk_bookmark_document FOREIGN KEY (document_id) REFERENCES document (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
