-- =====================================================================
-- V5__daily_quiz.sql — 오늘의 퀴즈(로드맵 6): daily_quiz + daily_quiz_item
-- =====================================================================
-- 기준 문서: docs/12-daily-quiz.md, 결정 기록: ADR-0005
--
-- "매일 개인화 문제 세트(복습 4 + 취약 3 + 새 문제 3)"를 저장하는 2테이블.
-- 세트를 저장하는 이유(ADR-0005): 진행률("3/10")이 의미 있으려면 오늘 세트가
-- 하루 동안 고정이어야 하는데, 기존 퀴즈 조회(ORDER BY RAND())는 매번 결과가
-- 달라 재사용할 수 없다. 생성은 배치가 아니라 "그날 첫 조회 때"(지연 생성) —
-- 로컬 PC 환경에서 자정 배치는 앱이 꺼져 있으면 그날 기능이 죽는 단일 실패점이다.
-- =====================================================================

-- ---------------------------------------------------------------------
-- daily_quiz : 사용자 × 날짜당 1개의 "세트 머리".
--   completed_at은 파생값 원칙(docs/10)의 예외가 아니다 — 시간이 아니라
--   "마지막 항목 제출"이라는 사건으로 확정되는 값이라 저장한다.
--   스트릭(연속 완료 일수)은 이 completed_at들에서 조회 시점에 계산한다(저장 안 함).
-- ---------------------------------------------------------------------
CREATE TABLE daily_quiz (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    user_id      BIGINT      NOT NULL,
    quiz_date    DATE        NOT NULL,                    -- 이 세트가 속한 날(서버 LocalDate 기준)
    completed_at DATETIME(6) NULL,                        -- 모든 항목을 푼 시각. NULL = 미완료
    created_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (id),

    -- "하루 1세트"를 코드가 아니라 DB가 보장한다. 지연 생성 특성상 동시 첫 조회
    -- (탭 2개)가 INSERT를 경합할 수 있는데, 그때 중복 세트를 막는 최후의 벽이
    -- 이 제약이다(review_item의 UNIQUE와 같은 패턴 — ADR-0005).
    -- 스트릭 계산(user_id로 최근 날짜 내림차순)도 이 인덱스를 그대로 탄다.
    UNIQUE KEY uk_dailyquiz_user_date (user_id, quiz_date),

    -- 세트는 이력이 아니라 사용자에게 종속된 파생 데이터 → 부모 삭제 시 함께 삭제(CASCADE).
    CONSTRAINT fk_dailyquiz_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- daily_quiz_item : 세트 안의 문제 한 줄.
--   "풀었는지"는 boolean이 아니라 submission_id(제출 이력 참조)로 저장한다 —
--   플래그만 남기면 정답 여부·제출 시각을 또 조회해야 하지만, 이력을 가리키면
--   전부 따라온다. 진실의 원천은 여전히 submission이고 여기는 연결만(docs/12).
-- ---------------------------------------------------------------------
CREATE TABLE daily_quiz_item (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    daily_quiz_id BIGINT      NOT NULL,
    problem_id    BIGINT      NOT NULL,
    seq           INT         NOT NULL,                   -- 세트 안 표시 순서(1..N)
    source        VARCHAR(10) NOT NULL,                   -- enum REVIEW/WEAK/NEW/GENERAL — 어느 배합 칸으로 뽑혔나
    submission_id BIGINT      NULL,                       -- 이 세트에서 푼 제출. NULL = 아직 안 풂
    PRIMARY KEY (id),

    -- 세트 안 같은 문제 중복 금지 + "오늘 세트에 이 문제 있나"(submit 연동) 조회 인덱스 겸용.
    UNIQUE KEY uk_dailyquizitem_quiz_problem (daily_quiz_id, problem_id),

    -- 세트가 지워지면 항목도 함께(CASCADE). problem은 세트가 살아 있는 동안 지워지면
    -- 안 되므로 RESTRICT — 관리자 문제 삭제가 오늘 세트를 깨뜨리려 하면 DB가 막고,
    -- 그건 "오늘 세트에 든 문제는 내일 지우라"는 운영 신호다(submission RESTRICT와 같은 판단).
    CONSTRAINT fk_dailyquizitem_quiz       FOREIGN KEY (daily_quiz_id) REFERENCES daily_quiz (id) ON DELETE CASCADE,
    CONSTRAINT fk_dailyquizitem_problem    FOREIGN KEY (problem_id)    REFERENCES problem (id)    ON DELETE RESTRICT,
    CONSTRAINT fk_dailyquizitem_submission FOREIGN KEY (submission_id) REFERENCES submission (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
