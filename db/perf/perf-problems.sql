-- =====================================================================
-- perf-problems.sql — ORDER BY RAND() 병목 실험용 가짜 문제 100,000건
-- =====================================================================
-- ⚠ 수동 실행 스크립트 (Flyway 아님). 실험 후 perf-cleanup.sql로 삭제.
--
-- 목적: 퀴즈 무작위 추출(ORDER BY RAND())은 조건에 걸린 전체 행에 난수를 붙여
--       정렬하므로 O(N log N)이다 — 문제 수가 적을 땐 못 느끼지만 10만 건이면
--       응답 시간으로 체감된다. 이를 숫자로 확인한다. (ProblemRepository 주석 참고)
--
-- question에 '[PERF]' 접두어 — 정리 시 이 표식으로 골라 지운다.
-- 보기(choice)는 만들지 않는다(SHORT_ANSWER라 불필요, 실험 목적에도 무관).
-- =====================================================================

SET SESSION cte_max_recursion_depth = 100000;

INSERT INTO problem (domain, difficulty, type, question, answer, explanation, created_at)
WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 100000
)
SELECT ELT(1 + FLOOR(RAND() * 3), 'NETWORK', 'OS', 'DATABASE'),
       'BEGINNER',
       'SHORT_ANSWER',
       CONCAT('[PERF] 실험용 더미 문제 ', n),
       'x',
       NULL,
       NOW(6)
FROM seq;

SELECT COUNT(*) AS total_problems FROM problem;
