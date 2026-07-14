-- ============================================================================
-- R__domain_stats_view.sql — 도메인별 정답률 통계 뷰 (Repeatable 마이그레이션)
-- ----------------------------------------------------------------------------
-- [왜 V__가 아니라 R__인가]
--   뷰(view)는 "저장된 SELECT문"이라 데이터가 없다 — 지우고 다시 만들어도
--   잃을 게 없다. 이런 객체를 V__로 관리하면 정의가 바뀔 때마다
--   V7, V11, ... 에 전체 재정의가 흩어져 "지금 최신 정의"를 찾기 어렵다.
--   R__는 파일 내용(체크섬)이 바뀔 때마다 Flyway가 재실행해 주므로,
--   뷰의 최신 정의를 이 파일 하나에 유지할 수 있다(과거 이력은 git 몫).
--   실행 순서: 모든 V__가 끝난 뒤 실행되므로 submission/problem 테이블의
--   존재를 전제로 써도 된다.
--
-- [멱등성(idempotent) 규칙]
--   R__는 몇 번을 다시 실행해도 결과가 같아야 한다. 그래서 CREATE VIEW가
--   아니라 CREATE OR REPLACE VIEW("있으면 교체")를 쓴다 — 일반 CREATE는
--   두 번째 실행에서 '이미 존재함' 에러가 나므로 R__에 쓰면 안 된다.
--
-- [왜 뷰로 만드나 — 트레이드오프]
--   같은 통계를 얻는 방법은 세 가지다:
--     1) 애플리케이션에서 매번 GROUP BY 쿼리 실행 (JPQL/QueryDSL)
--     2) 통계 테이블을 두고 제출 때마다 갱신 (비정규화)
--     3) 뷰 — 쿼리에 이름을 붙여 DB에 저장 (지금 방식)
--   뷰는 "쿼리의 별명"일 뿐 데이터를 미리 계산해 두지 않으므로(1)과 성능이
--   같다. 대신 복잡한 집계 SQL을 DB 한 곳에 정의해 두고 앱/DB콘솔/관리도구
--   어디서든 SELECT * FROM domain_stats 로 재사용할 수 있는 게 장점.
--   데이터가 커져 조회가 느려지면 (2)로 옮기는 게 다음 단계다.
--   (참고: docs/08 성능 실험 — 집계 쿼리 비용 감각)
-- ============================================================================

CREATE OR REPLACE VIEW domain_stats AS
SELECT
    p.domain                          AS domain,                  -- enum Domain 문자열 (docs/02)
    COUNT(*)                          AS submission_count,        -- 이 도메인에 쌓인 전체 제출 수
    SUM(s.is_correct)                 AS correct_count,           -- 그중 정답 수 (BOOLEAN=0/1이라 SUM이 곧 개수)
    ROUND(AVG(s.is_correct) * 100, 1) AS accuracy_pct,            -- 정답률(%) — AVG(0/1)*100, 소수 1자리
    COUNT(DISTINCT s.problem_id)      AS attempted_problem_count, -- 한 번이라도 풀린 문제 수
    COUNT(DISTINCT s.user_id)         AS solver_count             -- 도전한 사용자 수
FROM submission s
JOIN problem p ON p.id = s.problem_id                             -- 도메인은 problem 쪽에 있어 JOIN 필요
GROUP BY p.domain;
