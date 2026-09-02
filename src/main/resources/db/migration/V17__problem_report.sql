-- =====================================================================
-- V17__problem_report.sql — 학습자의 문제 오류 제보
-- =====================================================================
-- 왜 만드나: 문제를 만드는 쪽(LLM 생성 → 사람 검수)에는 되먹임 통로가 있는데
-- (거절 사유 → generated/_rejection-notes.json → 다음 배치 프롬프트, docs/14),
-- <푸는 쪽>에는 없었다. 검수를 통과하고 실제로 출제된 뒤에야 드러나는 결함 —
-- 정답 표시가 틀렸다든지 해설이 문제와 안 맞는다든지 — 을 알릴 길이 없다는 뜻이다.
-- 그 결함이야말로 검수가 놓친 것이라 되먹임 값이 가장 크다.
--
-- 상태 전이: PENDING → ACCEPTED(인정) 또는 DISMISSED(기각). 되돌리는 경로는 두지 않았다 —
-- 잘못 판정해도 같은 문제를 고치는 일은 문제 관리 화면에서 하면 되고, 제보 행 자체를
-- 되살릴 이유가 없다(초안 복구와 다른 점: 초안은 되살리면 다시 검수 대상이 된다).
-- =====================================================================

CREATE TABLE problem_report (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    problem_id  BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL,
    reason      VARCHAR(30)  NOT NULL,               -- enum ReportReason (자유 입력이 아니라 코드 — 아래 주석)
    detail      VARCHAR(500) NULL,                   -- 제보자가 덧붙이는 한 줄(선택)
    status      VARCHAR(15)  NOT NULL,               -- enum ReportStatus: PENDING / ACCEPTED / DISMISSED
    admin_note  VARCHAR(500) NULL,                   -- 인정·기각할 때 남기는 메모(선택)
    created_at  DATETIME(6)  NOT NULL,
    resolved_at DATETIME(6)  NULL,                   -- 처리 시각. PENDING인 동안은 NULL
    PRIMARY KEY (id),

    -- 사유를 자유 텍스트가 아니라 코드로 받는 이유는 이 값이 <다음 생성 프롬프트로 간다>는 데 있다.
    -- 같은 지적이 매번 다른 문장으로 쌓이면 되먹임이 흩어져 "이 실수가 반복된다"는 신호가 안 된다.
    -- (검수 화면의 거절 사유 칩을 만든 것과 같은 판단 — admin/llm.html의 REJECT_REASONS 주석.)
    -- 그때그때의 사정은 detail이 받는다.

    -- 한 사람이 같은 문제를 두 번 제보하지 못한다. 코드가 아니라 DB가 막는다 —
    -- review_item·daily_quiz에 이미 두 번 적용한 패턴이다(동시 클릭으로 두 행이 생기는 것도 함께 막힌다).
    -- 부작용: 기각된 뒤 다시 제보할 수 없다. 1인 서비스라 감수한다 —
    -- 재제보를 열려면 (problem_id, user_id, status)로 넓혀야 하는데 그러면
    -- "PENDING 두 건"이 다시 가능해져 막으려던 것이 도로 풀린다.
    UNIQUE KEY uk_report_problem_user (problem_id, user_id),

    -- 제보함이 읽는 축: WHERE status = 'PENDING' ORDER BY created_at.
    -- 등치(status) → 정렬(created_at) 순서라 이 인덱스 하나로 필터와 정렬이 함께 끝난다
    -- (review_item에서 확인한 복합 인덱스 구성 원칙, docs/08).
    KEY idx_report_status_created (status, created_at),

    -- CASCADE인 이유: 제보는 "그 문제에 딸린 지적"이라 문제가 사라지면 가리킬 대상이 없다.
    -- submission(RESTRICT, 이력 보존)과 다른 점 — 이건 학습 이력이 아니다.
    CONSTRAINT fk_report_problem FOREIGN KEY (problem_id) REFERENCES problem (id) ON DELETE CASCADE,
    CONSTRAINT fk_report_user    FOREIGN KEY (user_id)    REFERENCES `user` (id)  ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
