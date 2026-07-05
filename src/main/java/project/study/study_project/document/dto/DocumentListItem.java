package project.study.study_project.document.dto;

import project.study.study_project.global.common.Domain;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 문서 목록 항목 — API 스펙(docs/03). <b>본문(content_md)은 제외</b>(단건에서만 반환).
 *
 * <p>로드맵 1부터 이 DTO는 엔티티 변환이 아니라 <b>QueryDSL 프로젝션 결과로 직접 조립</b>된다
 * (DocumentRepositoryImpl) — 엔티티를 거치지 않아 본문(LONGTEXT)을 DB에서 읽지 않는다.
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
}
