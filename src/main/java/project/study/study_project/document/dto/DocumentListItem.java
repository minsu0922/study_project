package project.study.study_project.document.dto;

import project.study.study_project.document.domain.Document;
import project.study.study_project.global.common.Domain;
import project.study.study_project.tag.domain.Tag;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 문서 목록 항목 — API 스펙(docs/03). <b>본문(content_md)은 제외</b>(단건에서만 반환).
 *
 * @param domain      enum 상수명(영문, 예 {@code NETWORK}) — 클라이언트 분기용
 * @param domainLabel 화면 표기용 한글(예 "네트워크")
 */
public record DocumentListItem(
        Long id,
        Domain domain,
        String domainLabel,
        String title,
        String slug,
        List<String> tags,
        LocalDateTime updatedAt
) {
    public static DocumentListItem from(Document d) {
        return new DocumentListItem(
                d.getId(),
                d.getDomain(),
                d.getDomain().getDisplayName(),
                d.getTitle(),
                d.getSlug(),
                d.getTags().stream().map(Tag::getName).toList(), // LAZY 태그 로딩 → 트랜잭션 안에서 호출
                d.getUpdatedAt()
        );
    }
}
