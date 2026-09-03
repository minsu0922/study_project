package project.study.study_project.document.dto;

import project.study.study_project.document.domain.Document;
import project.study.study_project.document.support.DocumentEditions;
import project.study.study_project.global.common.Domain;
import project.study.study_project.tag.domain.Tag;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 문서 단건 응답(본문 포함) — API 스펙(docs/03).
 *
 * @param edition         "입문편"/"심화편". <b>짝이 실제로 있을 때만</b> 채운다(2026-09-03).
 *                        짝이 없는 한 편짜리 문서에 편 이름을 달면 읽는 사람이 없는 글을 찾아 나선다.
 * @param counterpartSlug 짝이 되는 편의 slug. 없으면 {@code null} — 화면은 이 값으로
 *                        배지와 링크를 함께 켜고 끈다(둘이 따로 놀면 링크만 남는 화면이 나온다)
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
        LocalDateTime updatedAt,
        String edition,
        String counterpartSlug
) {
    /**
     * 짝을 모르는 자리에서 쓴다(관리자 등록·수정 응답). 편 정보는 비운다.
     *
     * <p>여기서 짝을 찾지 않는 이유: 관리자 응답은 방금 저장한 것을 되돌려 주는 자리라
     * 화면이 배지를 쓰지 않는다. 쓰지도 않을 값을 위해 조회를 한 번 더 하지 않는다.
     */
    public static DocumentDetailResponse from(Document d) {
        return withEdition(d, null);
    }

    /**
     * 짝이 있는지 아는 자리에서 쓴다(학습자 단건 조회).
     *
     * @param counterpartSlug 실제로 존재하는 짝의 slug. 짝이 없으면 {@code null}
     */
    public static DocumentDetailResponse withEdition(Document d, String counterpartSlug) {
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
                d.getUpdatedAt(),
                counterpartSlug == null ? null : DocumentEditions.labelOf(d.getSlug()),
                counterpartSlug
        );
    }
}
