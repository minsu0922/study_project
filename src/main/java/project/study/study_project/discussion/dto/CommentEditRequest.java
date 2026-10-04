package project.study.study_project.discussion.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CommentEditRequest(
        @NotBlank(message = "내용을 입력해 주세요.")
        @Size(max = 1000, message = "댓글은 1,000자를 넘을 수 없습니다.")
        String body
) {
}
