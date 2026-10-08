package project.study.study_project.notification.domain;

/** 알림의 종류. 화면이 아이콘을 고르는 데 쓴다. */
public enum NotificationType {
    /** 내 글에 댓글이 달렸다 */
    POST_COMMENT,
    /** 내 댓글에 답글이 달렸다 */
    COMMENT_REPLY,
    /** 내가 보낸 문제 오류 제보가 인정됐다 */
    REPORT_ACCEPTED,
    /** 내가 보낸 문제 오류 제보가 기각됐다 */
    REPORT_DISMISSED
}
