package project.study.study_project.discussion.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PostWriteRequest(
        @NotNull(message = "문제를 지정해 주세요.")
        Long problemId,
        @NotBlank(message = "제목을 입력해 주세요.")
        @Size(min = 2, max = 100, message = "제목은 2~100자로 써 주세요.")
        String title,
        @NotBlank(message = "내용을 입력해 주세요.")
        @Size(max = 5000, message = "본문은 5,000자를 넘을 수 없습니다.")
        String body
) {
}
