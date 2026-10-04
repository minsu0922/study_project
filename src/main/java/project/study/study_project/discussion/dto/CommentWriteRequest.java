package project.study.study_project.discussion.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * @param parentId 답글이면 그 대상 댓글 id. 답글에 다는 답글이면 서비스가 원글 id로 바꾼다
 */
public record CommentWriteRequest(
        @NotNull(message = "문제를 지정해 주세요.")
        Long problemId,
        Long parentId,
        @NotBlank(message = "내용을 입력해 주세요.")
        @Size(max = 1000, message = "댓글은 1,000자를 넘을 수 없습니다.")
        String body
) {
}
