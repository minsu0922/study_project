-- =====================================================================
-- V50__document_draft_dedup_removals.sql — 문서 초안에 중복 제거 기록을 담는다
-- (docs/superpowers/specs/2026-11-22-document-dedup-design.md)
-- =====================================================================
-- 배치 파일의 dedupRemovals는 편별로 나뉘어 오므로, 흡수할 때 그 편 목록을 그대로 넣는다.
-- NULL = 중복 제거를 적용하지 않은 초안. 승인을 막지 않는다.
ALTER TABLE generated_document_draft
    ADD COLUMN dedup_removals_json TEXT NULL;
