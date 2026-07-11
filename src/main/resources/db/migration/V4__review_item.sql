-- =====================================================================
-- V4__review_item.sql — 복습 추천(로드맵 4): review_item 테이블 + 기존 오답 백필
-- =====================================================================
-- 기준 문서: docs/10-review-recommendation.md, 결정 기록: ADR-0004
--
-- ReviewItem = 사용자 × 문제당 딱 1행의 "복습 진행 상태"(이력 아님 — 이력은 submission).
-- 간격 사다리(stage 0..4 → 1/3/7/14/30일)의 현재 칸과 다음 복습 예정 시각을 저장한다.
-- "미복습(due)"은 컬럼으로 저장하지 않는다 — next_review_at <= NOW() 라는 시간 비교로
-- 파생되는 값이라, 저장하면 매일 배치로 갱신해야 하는 상태가 생기기 때문(docs/10).
-- =====================================================================

CREATE TABLE review_item (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    user_id        BIGINT      NOT NULL,
    problem_id     BIGINT      NOT NULL,
    stage          INT         NOT NULL DEFAULT 0,       -- 사다리 칸(0..4)
    status         VARCHAR(15) NOT NULL,                 -- enum LEARNING / GRADUATED (2가지뿐 — due는 파생값)
    review_count   INT         NOT NULL DEFAULT 0,       -- 사다리에 오른 뒤 푼 횟수(통계용)
    next_review_at DATETIME(6) NOT NULL,                 -- 다음 복습 예정 시각
    created_at     DATETIME(6) NOT NULL,                 -- 처음 틀린 시각
    updated_at     DATETIME(6) NOT NULL,
    PRIMARY KEY (id),

    -- "사용자×문제당 1행"을 코드가 아니라 DB가 보장한다.
    -- 동시 제출(더블클릭 등)로 upsert가 경합해도 중복 행이 생길 수 없다(docs/10).
    UNIQUE KEY uk_reviewitem_user_problem (user_id, problem_id),

    -- "오늘의 복습" 쿼리(WHERE user_id=? AND status='LEARNING' AND next_review_at <= NOW()
    --  ORDER BY next_review_at)가 이 인덱스 하나로 필터+정렬까지 끝나도록
    -- 등치(user_id, status) → 범위(next_review_at) 순서로 구성(docs/10, 로드맵 1의 교훈 재적용).
    KEY idx_reviewitem_user_due (user_id, status, next_review_at),

    -- Submission(RESTRICT, 이력 보존)과 달리 CASCADE인 이유: ReviewItem은 이력이 아니라
    -- 파생 가능한 "진행 상태"라, 부모(문제/사용자)가 사라지면 함께 사라지는 게 맞다(docs/10).
    CONSTRAINT fk_reviewitem_user    FOREIGN KEY (user_id)    REFERENCES `user` (id)  ON DELETE CASCADE,
    CONSTRAINT fk_reviewitem_problem FOREIGN KEY (problem_id) REFERENCES problem (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- 백필: 기능 도입 전에 쌓인 오답을 사다리에 올린다.
--
-- 대상: 사용자×문제별 "최신 제출이 오답"인 것만.
--   - "틀린 적은 있지만 최근에 다시 맞힌" 문제는 제외 — 이미 스스로 복습을 마친 셈.
--   - 과거 이력 전체를 사다리 규칙으로 리플레이하지 않는다 — 당시엔 규칙이 없었으므로
--     정확하지도 않고, 전부 stage 0에서 시작하는 쪽이 단순하다(docs/10).
--
-- 값: stage 0 / LEARNING / next_review_at = NOW() → 마이그레이션 직후 바로 "오늘의 복습"에 뜬다.
--     created_at은 "처음 틀린 시각" 정의를 지키기 위해 해당 문제의 첫 오답 시각을 쓴다.
-- ---------------------------------------------------------------------
INSERT INTO review_item (user_id, problem_id, stage, status, review_count, next_review_at, created_at, updated_at)
SELECT t.user_id,
       t.problem_id,
       0,
       'LEARNING',
       0,
       NOW(6),
       t.first_wrong_at,
       NOW(6)
FROM (
    -- 윈도우 함수(MySQL 8)로 사용자×문제별 최신 제출 1건(rn=1)과 첫 오답 시각을 한 번에 뽑는다.
    -- submitted_at 동률(같은 마이크로초) 대비로 id를 보조 정렬 키로 둔다.
    SELECT user_id,
           problem_id,
           is_correct,
           ROW_NUMBER() OVER (PARTITION BY user_id, problem_id
                              ORDER BY submitted_at DESC, id DESC) AS rn,
           MIN(CASE WHEN is_correct = FALSE THEN submitted_at END)
               OVER (PARTITION BY user_id, problem_id)             AS first_wrong_at
    FROM submission
) t
WHERE t.rn = 1
  AND t.is_correct = FALSE;
