package project.study.study_project.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import project.study.study_project.global.common.Domain;

import java.util.List;

/**
 * 관리자 문서 등록/수정 요청 바디.
 *
 * @param slug URL용 영문 식별자. 프로젝트 규칙(docs/01: 영문 수동 입력, 한글 자동변환 없음)을
 *             정규식으로 강제한다 — 소문자·숫자·하이픈만, 예 {@code tcp-3-way-handshake}
 * @param tags 태그 이름 목록 — 없는 태그는 서버가 자동 생성(TagService)
 */
public record AdminDocumentRequest(

        @NotNull(message = "domain은 필수입니다.")
        Domain domain,

        @NotBlank(message = "title은 필수입니다.")
        @Size(max = 200, message = "title은 200자 이하여야 합니다.")
        String title,

        @NotBlank(message = "slug는 필수입니다.")
        @Size(max = 150, message = "slug는 150자 이하여야 합니다.")
        @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$",
                message = "slug는 영문 소문자·숫자·하이픈만 가능합니다. (예: tcp-3-way-handshake)")
        String slug,

        @NotBlank(message = "본문(contentMd)은 필수입니다.")
        String contentMd,

        @Size(max = 500, message = "source는 500자 이하여야 합니다.")
        String source,

        List<String> tags
) {
}
