package project.study.study_project.document.dto;

import project.study.study_project.global.common.DomainCode;

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
 * @param edition     "입문편"/"심화편". <b>짝이 실제로 있을 때만</b> 채운다(2026-09-03).
 *                    2026-09-03 이전 한 편짜리 문서에는 {@code null}이고, 화면은 배지를 안 그린다.
 *                    두 편은 제목이 같으므로 목록에서 이 값이 유일한 구별 수단이다
 */
public record DocumentListItem(
        Long id,
        DomainCode domain,
        String domainLabel,
        String title,
        String slug,
        List<String> tags,
        LocalDateTime updatedAt,
        String edition
) {
    /** 편 정보를 채운 사본 — 리포지토리는 짝을 모르므로 서비스가 나중에 붙인다. */
    public DocumentListItem withEdition(String edition) {
        return new DocumentListItem(id, domain, domainLabel, title, slug, tags, updatedAt, edition);
    }

    /**
     * 분야 표기 이름을 채운 사본 — {@code DocumentRepositoryImpl}(QueryDSL 프로젝션)은 이 값을
     * {@code null}로 비워 두고, {@code DocumentService}가 {@code DomainCatalog}로 채운다.
     *
     * <p><b>왜 리포지토리가 직접 채우지 않나(6번 작업 리뷰 1차).</b> 리포지토리(영속성 계층)가
     * {@code DomainCatalog}(서비스 계층 인터페이스)를 올려다보는 것 자체가 방향이 거꾸로다 —
     * 그 역방향 의존이 실제로 사고를 냈다. 6번 작업에서 {@code DomainSettingService}(그
     * {@code DomainCatalog}의 구현체)가 분야 삭제 시 사용량을 확인하려고 {@code DocumentRepository}를
     * 물게 되자, 스프링이 두 빈을 서로를 기다리며 만들다 죽었다(순환 참조).
     * {@code @Lazy}로 그 자리를 미루는 미봉책도 가능했지만, 그러면 "이 저장소 하나만 예외"라는
     * 근거를 다음 사람이 다시 추적해야 하고, 앞으로 카탈로그가 필요한 프로젝션이 하나 더
     * 생기면 그 프로젝션마다 같은 미봉책을 반복해야 한다. 근본 해법은 의존 방향을 바로잡는
     * 것 — {@link project.study.study_project.document.service.DocumentService#getDocuments}가
     * {@code page.map(...)}로 조회 뒤에 라벨을 붙인다({@code withEditions}가 짝 편 이름을 붙이는
     * 것과 같은 자리, 같은 방식).
     */
    public DocumentListItem withDomainLabel(String domainLabel) {
        return new DocumentListItem(id, domain, domainLabel, title, slug, tags, updatedAt, edition);
    }
}
