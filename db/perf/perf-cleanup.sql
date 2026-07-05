-- =====================================================================
-- perf-cleanup.sql — 성능 실험 데이터 전량 삭제 (원상 복구)
-- =====================================================================
-- perf-data.sql / perf-problems.sql이 만든 것만 골라 지운다.
-- 식별 표식: 회원 email 'perf_...', 문제 question '[PERF]...'
-- 실제 회원/콘텐츠(퀴즈 시드·직접 등록분)는 건드리지 않는다.
--
-- 순서 중요: 제출(자식, FK RESTRICT) → 회원(부모) 순.
-- =====================================================================

-- 1) 가짜 회원들의 제출 이력 (50만 건 — 수십 초 걸릴 수 있음)
DELETE s FROM submission s
JOIN `user` u ON u.id = s.user_id
WHERE u.email LIKE 'perf\_%';

-- 2) 가짜 회원
DELETE FROM `user` WHERE email LIKE 'perf\_%';

-- 3) 가짜 문제 (제출 이력 없음 → RESTRICT에 걸리지 않음. 보기도 안 만들었음)
DELETE FROM problem WHERE question LIKE '[PERF]%';

SELECT COUNT(*) AS users FROM `user`;
SELECT COUNT(*) AS submissions FROM submission;
SELECT COUNT(*) AS problems FROM problem;
