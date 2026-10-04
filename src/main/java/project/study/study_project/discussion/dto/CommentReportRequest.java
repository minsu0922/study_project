package project.study.study_project.discussion.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import project.study.study_project.discussion.domain.CommentReportReason;

public record CommentReportRequest(
        @NotNull(message = "신고할 댓글을 지정해 주세요.")
        Long commentId,
        @NotNull(message = "신고 사유를 골라 주세요.")
        CommentReportReason reason,
        @Size(max = 500, message = "상세 내용은 500자를 넘을 수 없습니다.")
        String detail
) {
}
