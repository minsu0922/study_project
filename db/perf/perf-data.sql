-- =====================================================================
-- perf-data.sql — 성능 실험용 대량 데이터 생성 (로드맵 1: 인덱스 효과 실측)
-- =====================================================================
-- ⚠ Flyway 마이그레이션이 아니다. 실험할 때만 수동 실행하는 스크립트다.
--   (실험 데이터는 스키마·운영 콘텐츠가 아니므로 버전 이력에 넣지 않는다)
-- 실행:   mysql -u csquiz -p csquiz < db/perf/perf-data.sql
-- 정리:   perf-cleanup.sql 실행 (실험 데이터만 골라 삭제)
--
-- 만드는 것:
--   - 가짜 회원 1,000명   (email 'perf_...' — 정리 시 이 접두어로 식별)
--   - 가짜 제출 500,000건 (가짜 회원들이 기존 문제 1~24를 무작위로 푼 기록)
--
-- 왜 이 모양인가:
--   - 오답노트 인덱스 (user_id, is_correct, submitted_at)의 효과는
--     "수많은 회원의 제출이 뒤섞인 큰 테이블에서 한 회원 것만 골라낼 때" 드러난다.
--     회원이 1~2명이면 인덱스가 있으나 없으나 다 읽어야 해서 실험이 안 된다.
--   - INSERT ... SELECT + 재귀 CTE로 DB 안에서 생성한다. 앱/스크립트에서 50만 번
--     INSERT하면 네트워크 왕복 때문에 수십 배 느리다.
-- =====================================================================

-- 재귀 CTE 기본 한도(1000)를 늘린다 (500행 시퀀스 생성엔 불필요하지만 명시적으로)
SET SESSION cte_max_recursion_depth = 100000;

-- ---------------------------------------------------------------------
-- 1) 가짜 회원 1,000명
--    password_hash 'x'는 BCrypt 형식이 아니라 로그인 자체가 불가능 —
--    실험용 계정이 실수로라도 인증에 쓰이지 않게 하는 안전장치를 겸한다.
-- ---------------------------------------------------------------------
INSERT INTO `user` (email, password_hash, role, created_at)
WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 1000
)
SELECT CONCAT('perf_', n, '@perf.local'), 'x', 'USER', NOW(6)
FROM seq;

-- ---------------------------------------------------------------------
-- 2) 가짜 제출 500,000건 = 가짜 회원 1,000명 × 각 500건
--    - problem_id: 기존 문제 1~24 무작위 (FK 만족 — 실행 전 id 연속 확인함)
--    - is_correct: 절반 확률
--    - submitted_at: 최근 90일 사이 무작위 (시간 조건/정렬이 현실적이도록)
-- ---------------------------------------------------------------------
INSERT INTO submission (user_id, problem_id, user_answer, is_correct, submitted_at)
SELECT u.id,
       1 + FLOOR(RAND() * 24),
       'perf',
       FLOOR(RAND() * 2),
       TIMESTAMPADD(SECOND, -FLOOR(RAND() * 90 * 24 * 3600), NOW(6))
FROM `user` u
CROSS JOIN (
    WITH RECURSIVE seq AS (
        SELECT 1 AS n
        UNION ALL
        SELECT n + 1 FROM seq WHERE n < 500
    )
    SELECT n FROM seq
) x
WHERE u.email LIKE 'perf\_%';

SELECT COUNT(*) AS perf_users FROM `user` WHERE email LIKE 'perf\_%';
SELECT COUNT(*) AS total_submissions FROM submission;
