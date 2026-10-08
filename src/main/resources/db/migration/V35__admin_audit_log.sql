-- =====================================================================
-- V35__admin_audit_log.sql — 관리자가 한 일의 기록
-- =====================================================================
-- 정지·권한 변경·강제 탈퇴·숨김을 누가 언제 했는지 남지 않았다.
-- 사용자 행에는 정지 기한과 사유만 있어, 풀린 뒤에는 정지가 있었다는 사실도 사라졌다.
--
-- 외래 키를 걸지 않는다. 기록은 대상(사용자·문제)이나 처리한 관리자 계정이 지워진 뒤에도
-- 남아야 한다. 그래서 처리한 사람의 아이디도 그때 값을 굳혀 적는다(actor_username).
-- =====================================================================

CREATE TABLE admin_audit_log (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    actor_id       BIGINT       NULL,
    actor_username VARCHAR(30)  NOT NULL,
    method         VARCHAR(10)  NOT NULL,            -- POST / PUT / PATCH / DELETE
    pattern        VARCHAR(200) NOT NULL,            -- 예: /api/admin/users/{id}/suspend
    path           VARCHAR(300) NOT NULL,            -- 예: /api/admin/users/12/suspend
    created_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    -- 화면은 최신순으로만 읽는다. id가 시간 순이라 따로 정렬 인덱스를 두지 않는다.
    KEY idx_audit_actor (actor_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
