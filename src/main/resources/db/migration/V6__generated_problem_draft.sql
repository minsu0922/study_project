-- =====================================================================
-- V6__generated_problem_draft.sql — LLM 문제 생성(B안): 검수 대기 초안
-- =====================================================================
-- 기준 문서: docs/13-llm-problem-generation.md, 결정 기록: ADR-0006
--
-- Claude API가 생성한 문제는 곧바로 problem 테이블에 넣지 않고 이 테이블에
-- "초안(PENDING)"으로 저장한다. 관리자가 승인해야만 정식 문제로 등록된다.
-- LLM은 그럴듯하지만 틀린 문제(환각)를 만들 수 있으므로, 사람 검수를
-- 데이터 구조 차원에서 강제하는 것이 이 테이블의 존재 이유다(ADR-0006).
-- =====================================================================

CREATE TABLE generated_problem_draft (
    id             BIGINT       NOT NULL AUTO_INCREMENT,

    -- 문제 규격은 problem 테이블과 동일한 enum 문자열(docs/02) — 승인 시 그대로 이관
    domain         VARCHAR(30)  NOT NULL,
    difficulty     VARCHAR(15)  NOT NULL,
    type           VARCHAR(20)  NOT NULL,
    question       TEXT         NOT NULL,
    answer         VARCHAR(500) NULL,                -- 타입별 규칙(객관식=NULL 등)은 docs/01과 동일
    explanation    TEXT         NULL,

    -- 객관식 보기를 [{"text":..,"correct":..}] JSON 문자열로 저장한다.
    -- problem처럼 choice 자식 테이블로 정규화하지 않는 이유: 초안은 풀이/채점에
    -- 쓰이지 않고 "검수 화면에 보여주고 승인 시 변환"만 하면 되는 임시 데이터라,
    -- 테이블을 쪼개는 비용(조인·매핑) 대비 얻는 게 없다. 승인 순간 정규화된
    -- problem/choice로 옮겨지고 초안은 이력으로만 남는다.
    choices_json   TEXT         NULL,

    -- PENDING(검수 대기) / APPROVED(승인됨) / REJECTED(거절됨)
    status         VARCHAR(10)  NOT NULL,
    model          VARCHAR(50)  NOT NULL,            -- 생성에 사용한 모델명(비용·품질 추적용)
    reject_reason  VARCHAR(500) NULL,                -- 거절 사유(선택) — 프롬프트 개선 힌트로 활용

    -- 승인으로 만들어진 problem.id. FK를 걸지 않은 이유: 초안은 "무엇이 생성됐고
    -- 어떻게 처리됐나"의 이력(로그)이라, 나중에 관리자가 그 문제를 삭제하더라도
    -- 이력이 삭제를 막거나(RESTRICT) 함께 지워지면(CASCADE) 안 된다. 참조값만 기록.
    approved_problem_id BIGINT  NULL,

    created_at     DATETIME(6)  NOT NULL,
    reviewed_at    DATETIME(6)  NULL,                -- 승인/거절 처리 시각. NULL = 미처리
    PRIMARY KEY (id),

    -- 검수 화면의 기본 조회가 "PENDING을 오래된 순으로" — 상태 선두 복합 인덱스 하나로 커버
    KEY idx_draft_status_created (status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
