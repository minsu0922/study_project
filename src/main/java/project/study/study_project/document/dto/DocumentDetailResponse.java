package project.study.study_project.document.dto;

import project.study.study_project.document.domain.Document;
import project.study.study_project.global.common.Domain;
import project.study.study_project.tag.domain.Tag;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 문서 단건 응답(본문 포함) — API 스펙(docs/03).
 */
public record DocumentDetailResponse(
        Long id,
        Domain domain,
        String domainLabel,
        String title,
        String slug,
        String contentMd,
        String source,
        List<String> tags,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static DocumentDetailResponse from(Document d) {
        return new DocumentDetailResponse(
                d.getId(),
                d.getDomain(),
                d.getDomain().getDisplayName(),
                d.getTitle(),
                d.getSlug(),
                d.getContentMd(),
                d.getSource(),
                d.getTags().stream().map(Tag::getName).toList(),
                d.getCreatedAt(),
                d.getUpdatedAt()
        );
    }
}
