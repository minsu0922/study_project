package project.study.study_project.learningpath.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 관리자 코스 등록·수정 — 폼 전체를 보낸다(문서 관리와 같은 방식). 단계는 문서 id를 순서대로.
 *
 * @param documentIds 단계 순서 그대로. 같은 문서가 두 번 오면 PATH_002
 * @param published   true면 공개. 공개 문서가 없는 코스는 공개할 수 없다(PATH_003)
 */
public record AdminLearningPathRequest(
        @NotBlank @Size(max = 100) String title,
        @NotBlank @Size(max = 500) String description,
        @Min(0) @Max(9999) int sortOrder,
        @NotNull @Size(max = 100) List<Long> documentIds,
        boolean published
) {
}
