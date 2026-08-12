-- =====================================================================
-- V8__generated_document_draft.sql — LLM 개념 문서 생성(1단계-B): 검수 대기 초안
-- =====================================================================
-- 기준 문서: docs/15-llm-concept-documents.md
--
-- V6(generated_problem_draft)와 같은 이유로 존재하는 테이블이다. LLM이 쓴 글은
-- 그럴듯하지만 틀릴 수 있으므로, 사람이 승인해야만 정식 document 테이블로
-- 넘어가게 한다. "검수를 잊었다"가 불가능하도록 데이터 구조로 강제하는 것이 핵심.
--
-- 문제 초안과 테이블을 합치지 않은 이유:
--   합치려면 question/choices_json과 title/slug/content_md가 한 테이블에 섞이고
--   서로 상대편 칸을 전부 NULL로 두게 된다. 그러면 "이 행에서 어떤 칸이 필수인가"를
--   DB가 표현할 수 없어 NOT NULL 제약을 하나도 못 건다. 종류가 다른 것은 방을 나눈다.
-- =====================================================================

CREATE TABLE generated_document_draft (
    id             BIGINT       NOT NULL AUTO_INCREMENT,

    -- 문서 규격은 document 테이블(V1)의 컬럼과 1:1 — 승인 시 그대로 이관한다
    domain         VARCHAR(30)  NOT NULL,
    title          VARCHAR(200) NOT NULL,
    slug           VARCHAR(150) NOT NULL,
    content_md     LONGTEXT     NOT NULL,

    -- 태그를 ["security","auth"] JSON 문자열로 저장한다.
    -- document처럼 tag 조인 테이블로 정규화하지 않는 이유는 V6의 choices_json과 같다:
    -- 초안은 검색/필터의 대상이 아니라 "화면에 보여 주고 승인 시 변환"만 하는 임시
    -- 데이터라, 테이블을 쪼개 얻는 것(태그별 조회)이 애초에 필요 없다.
    -- 게다가 초안 단계의 태그는 아직 존재하지 않는 태그일 수 있어(모델이 새로 지어냄)
    -- 정규화하면 "승인도 안 한 문서 때문에 tag 테이블이 오염되는" 부작용이 생긴다.
    tags_json      TEXT         NULL,

    -- slug 중복 검사는 하지 않는다(UNIQUE 없음). 초안 단계에서 막으면 같은 주제를
    -- 다시 뽑아 비교해 보는 것 자체가 불가능해진다. 진짜 유일성은 document.slug의
    -- UNIQUE가 지키고, 초안은 승인 시점에 DOC_002(409)로 걸러진다.

    -- PENDING(검수 대기) / APPROVED(승인됨) / REJECTED(거절됨) — 문제 초안과 같은 enum
    status         VARCHAR(10)  NOT NULL,
    model          VARCHAR(50)  NOT NULL,            -- 생성에 사용한 모델명(비용·품질 추적용)
    reject_reason  VARCHAR(500) NULL,                -- 거절 사유 — 프롬프트 개선 힌트

    -- 승인으로 만들어진 document.id. V6와 같은 이유로 FK를 걸지 않는다:
    -- 이 행은 "무엇이 생성됐고 어떻게 처리됐나"의 이력이라, 나중에 그 문서를 지웠다고
    -- 이력이 삭제를 막거나(RESTRICT) 함께 사라지면(CASCADE) 안 된다. 참조값만 기록.
    approved_document_id BIGINT NULL,

    created_at     DATETIME(6)  NOT NULL,
    reviewed_at    DATETIME(6)  NULL,                -- 승인/거절 처리 시각. NULL = 미처리
    PRIMARY KEY (id),

    -- 검수 화면의 기본 조회가 "PENDING을 오래된 순으로" — V6와 같은 복합 인덱스 하나로 커버
    KEY idx_doc_draft_status_created (status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
