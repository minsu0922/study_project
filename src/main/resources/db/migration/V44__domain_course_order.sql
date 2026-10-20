-- =====================================================================
-- V44__domain_course_order.sql — 분야별 코스의 단계 순서를 사람이 정한다(docs/26)
-- =====================================================================
-- 분야별 코스는 여전히 저장하지 않고 요청 때 만든다. 여기에는 "이 문서는 몇 번째"만 적는다.
-- 순번이 없는 문서는 순번 있는 문서 뒤에 기본 규칙(입문편 → 심화편, 먼저 만든 주제 순)으로 붙는다.
-- 그래서 새로 공개된 문서는 사람이 손대지 않아도 코스 맨 뒤에 나타난다.
--
-- 분야 칸을 두지 않은 이유: 분야는 문서에 이미 있다. 문서의 분야를 바꾸면 순번이 그대로
-- 따라가는데, 새 분야에서도 "순번 있는 문서가 앞"이라 코스에서 빠지지 않는다.
-- =====================================================================

CREATE TABLE domain_course_order (
    document_id BIGINT NOT NULL,
    seq         INT    NOT NULL,
    PRIMARY KEY (document_id),
    CONSTRAINT fk_domain_course_order_document FOREIGN KEY (document_id) REFERENCES document (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
