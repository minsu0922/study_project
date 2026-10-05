package project.study.study_project.discussion.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import project.study.study_project.discussion.domain.CommentReportReason;

/** 글 신고. 사유는 댓글 신고와 같은 목록을 쓴다 — 글과 댓글에서 뜻이 다르지 않다. */
public record PostReportRequest(
        @NotNull(message = "신고할 글을 지정해 주세요.")
        Long postId,
        @NotNull(message = "신고 사유를 골라 주세요.")
        CommentReportReason reason,
        @Size(max = 500, message = "상세 내용은 500자를 넘을 수 없습니다.")
        String detail
) {
}
