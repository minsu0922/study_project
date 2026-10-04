package project.study.study_project.discussion.domain;

/** 댓글 신고 사유. 문구의 주인은 이 enum 하나다 — 화면은 서버가 준 label을 그대로 쓴다. */
public enum CommentReportReason {
    ABUSE("욕설·비방"),
    SPAM("광고·도배"),
    OFF_TOPIC("문제와 무관한 글"),
    OTHER("그 밖의 문제");

    private final String label;

    CommentReportReason(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
