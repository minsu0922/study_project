-- =====================================================================
-- V31__notification.sql — 알림함
-- =====================================================================
-- 내 글에 댓글이 달리거나 오류 제보가 판정돼도 알 길이 없었다.
-- 직접 그 화면에 다시 들어가 봐야 했고, 제보는 결과를 보는 화면조차 없었다.
--
-- 문구와 링크를 만드는 시점에 굳혀 저장한다. 조회할 때 원본(글·제보)을 다시 읽어
-- 조립하면 원본이 지워진 알림이 빈 줄로 남는다.
-- =====================================================================

CREATE TABLE notification (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_id    BIGINT       NOT NULL,
    type       VARCHAR(30)  NOT NULL,
    message    VARCHAR(300) NOT NULL,
    link       VARCHAR(300) NULL,
    read_at    DATETIME(6)  NULL,
    created_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    -- 탈퇴하면 알림도 함께 지운다. 받을 사람이 없는 알림은 남길 이유가 없다.
    CONSTRAINT fk_notification_user FOREIGN KEY (user_id) REFERENCES user (id) ON DELETE CASCADE,
    -- 목록(최신순)과 안 읽은 수 세기를 함께 받는다.
    KEY idx_notification_user (user_id, read_at, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
