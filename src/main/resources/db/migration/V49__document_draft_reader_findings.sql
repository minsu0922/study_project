-- =====================================================================
-- V49__document_draft_reader_findings.sql — 문서 초안에 독자 점검 지적을 담는다
-- (docs/superpowers/specs/2026-11-20-document-reader-check-design.md)
-- =====================================================================
-- 배치 파일의 readerFindings는 편별로 나뉘어 오므로, 흡수할 때 그 편 목록을 그대로 넣는다.
-- NULL = 독자 점검을 안 돌린 초안, '[]' = 돌렸는데 지적 없음. 둘은 화면에서 다르게 보인다.
-- 사실 검수와 달리 미처리 지적이 남아도 승인을 막지 않는다.
ALTER TABLE generated_document_draft
    ADD COLUMN reader_findings_json TEXT NULL;
