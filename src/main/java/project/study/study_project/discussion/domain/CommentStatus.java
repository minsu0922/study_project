package project.study.study_project.discussion.domain;

/** 댓글 상태. 행은 지우지 않고 상태만 바꾼다 — 답글이 달린 글의 자리가 남아야 한다. */
public enum CommentStatus {
    VISIBLE,
    /** 관리자가 가렸다. 복구할 수 있다. */
    HIDDEN,
    /** 글쓴이가 지웠다. 되돌리지 않는다. */
    DELETED
}
