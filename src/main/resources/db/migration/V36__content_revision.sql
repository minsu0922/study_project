-- =====================================================================
-- V36__content_revision.sql — 문제·문서의 수정 전 모습
-- =====================================================================
-- 문제와 문서는 고치면 옛 내용이 그대로 사라졌다. 잘못 고친 것을 알아도 되돌릴 원본이 없었다.
--
-- 고치기 직전의 모습을 통째로(JSON) 적어 둔다. 바뀐 칸만 적으면 되돌릴 때 여러 행을
-- 거슬러 맞춰야 하고, 그 사이 칸이 늘면 맞출 수 없게 된다.
-- JSON의 모양은 관리 등록 요청(AdminProblemRequest·AdminDocumentRequest)과 같다 —
-- 되돌리기가 "그 요청을 다시 보내는 것"이 된다.
--
-- 외래 키가 없는 이유: target_id가 종류에 따라 다른 테이블을 가리킨다.
-- 대상을 지울 때 서비스가 함께 지운다.
-- =====================================================================

CREATE TABLE content_revision (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    target_type     VARCHAR(20) NOT NULL,             -- PROBLEM / DOCUMENT
    target_id       BIGINT      NOT NULL,
    snapshot_json   LONGTEXT    NOT NULL,
    editor_username VARCHAR(30) NULL,                 -- 고친 사람. 배치·테스트처럼 사람이 없으면 NULL
    created_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_revision_target (target_type, target_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
