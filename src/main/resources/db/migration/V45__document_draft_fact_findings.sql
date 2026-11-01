-- =====================================================================
-- V45__document_draft_fact_findings.sql — 문서 초안에 AI 사실 검수 지적을 담는다(docs/22 §3.1)
-- =====================================================================
-- 지적은 배치 파일(generated/documents/*.json의 factFindings)에만 있어 검수함에서 볼 수 없었다.
-- 두 편의 지적이 한 목록으로 오므로, 흡수할 때 인용이 그 편 본문에 있는 것만 골라 편마다 담는다.
-- NULL = 사실 검수를 안 돌린 초안, '[]' = 돌렸는데 지적 없음. 둘은 화면에서 다르게 보인다.
ALTER TABLE generated_document_draft
    ADD COLUMN fact_findings_json TEXT NULL;
