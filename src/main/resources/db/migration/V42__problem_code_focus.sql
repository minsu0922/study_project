-- =====================================================================
-- V42__problem_code_focus.sql — 코드 문제가 가리키는 줄과 "보기가 코드"임을 남긴다
-- =====================================================================
-- 빈칸 코드와 한 줄 고치기는 코드의 한 줄을 두고 묻는다. 그 줄이 다른 줄과 똑같이 보이면
-- 학습자가 15줄 가운데서 "12행"을 세어 찾아야 한다. 화면이 그 줄을 강조하도록 번호를 저장한다.
--
--   code_focus_line — 강조할 줄(1부터). 빈칸 코드는 빈칸이 있는 줄, 한 줄 고치기는 바꿀 줄.
--                     오류 지점·컴파일 여부는 그 줄이 곧 정답이라 NULL로 둔다
--   code_choices    — 보기가 코드인가. 그렇다면 화면이 보기를 고정폭으로 그린다
--
-- 초안에는 유형(code_kind, V41)이 있어 code_choices를 따로 두지 않는다.
-- =====================================================================

ALTER TABLE problem
    ADD COLUMN code_focus_line INT NULL AFTER code_language,
    ADD COLUMN code_choices TINYINT(1) NOT NULL DEFAULT 0 AFTER code_focus_line;

ALTER TABLE generated_problem_draft
    ADD COLUMN code_focus_line INT NULL AFTER expected_output;
