package project.study.study_project.document.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import project.study.study_project.document.domain.Document;

import java.util.List;
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

    /**
     * 모든 문서 제목 — 클라우드 문서 생성 배치의 <b>중복 주제 회피 목록</b>으로 내보낸다(docs/15).
     *
     * <p>제목만 뽑는 이유: 본문(LONGTEXT)까지 딸려오면 수십~수백 KB를 읽어 버리는데, 필요한 건
     * "이미 다룬 주제가 무엇인가"뿐이다. 정렬을 id로 두어 결과 순서를 고정한다 —
     * 순서가 흔들리면 내용이 같은데도 스냅샷 파일이 매번 바뀐 것으로 보인다
     * ({@code ExistingDocumentsExporter}가 변경 여부로 파일 쓰기를 결정한다).
     */
    @Query("select d.title from Document d order by d.id")
    List<String> findAllTitles();
}
