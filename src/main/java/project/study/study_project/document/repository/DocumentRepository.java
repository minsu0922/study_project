package project.study.study_project.document.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import project.study.study_project.document.domain.Document;

import java.util.Optional;

/**
 * Document 저장소.
 *
 * <p>목록 검색(필터·페이징)은 처음엔 Specification으로 구현했다가 <b>로드맵 1에서
 * QueryDSL 구현({@link DocumentRepositoryCustom})으로 교체</b>했다. 이유(실측은 docs/08):
 * Specification+엔티티 조회는 태그 N+1(설정으로 완화해도 잠복)과 본문(LONGTEXT) 불필요 전송이
 * 있었고, QueryDSL DTO 프로젝션은 쿼리 2방 고정 + 필요한 컬럼만 읽는다.
 */
public interface DocumentRepository extends JpaRepository<Document, Long>, DocumentRepositoryCustom {

    /** slug로 문서 단건 조회(없으면 DOC_001). */
    Optional<Document> findBySlug(String slug);

    /** 관리자 문서 등록/수정 시 slug 중복 검사(DOC_002)용. */
    boolean existsBySlug(String slug);
}
