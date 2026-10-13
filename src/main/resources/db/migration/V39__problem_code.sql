-- =====================================================================
-- V39__problem_code.sql — 문제와 초안에 코드 칸을 둔다
-- =====================================================================
-- 자바 코드 문제(실행 결과, 빈칸 코드 등)를 내려면 지문 글과 코드를 따로 담아야 한다.
-- 한 칸에 섞으면 화면이 코드만 고정폭으로 그릴 수 없고, 배치가 코드만 꺼내 실행해 볼 수도 없다.
--
-- code_language를 함께 두는 이유: 지금은 java만 받지만, 언어가 늘 때 열을 다시 만들지 않으려는 것이다.
-- 두 열 다 NULL이면 코드가 없는 보통 문제다. 기존 행은 전부 그렇다.
-- =====================================================================

ALTER TABLE problem
    ADD COLUMN code TEXT NULL AFTER question,
    ADD COLUMN code_language VARCHAR(20) NULL AFTER code;

ALTER TABLE generated_problem_draft
    ADD COLUMN code TEXT NULL AFTER question,
    ADD COLUMN code_language VARCHAR(20) NULL AFTER code;
