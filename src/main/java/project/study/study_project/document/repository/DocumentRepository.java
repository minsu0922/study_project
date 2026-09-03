package project.study.study_project.document.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import project.study.study_project.document.domain.Document;
import project.study.study_project.llm.dto.DomainTitle;

import java.util.Collection;
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

    /**
     * 분야를 함께 뽑는다 — 회피 목록을 <b>[분야] 제목</b> 꼴로 내보내려고(2026-09-03).
     *
     * <p>{@link #findAllTitles()}를 두고 하나 더 만든 이유: 제목만 쓰는 자리가 아직 있고,
     * 쓰지도 않을 분야를 매번 함께 읽을 이유가 없다. 정렬을 id로 고정하는 이유는 위와 같다 —
     * 순서가 흔들리면 내용이 같은데도 스냅샷 파일이 매번 바뀐 것으로 보인다.
     */
    @Query("select new project.study.study_project.llm.dto.DomainTitle(d.domain, d.title) "
            + "from Document d order by d.id")
    List<DomainTitle> findAllDomainTitles();

    /**
     * 주어진 slug 중 <b>실제로 존재하는 것</b>만 골라 낸다 — 문제 화면의 "개념 문서 읽기" 링크용(3단계).
     *
     * <p><b>왜 존재 확인이 필요한가.</b> 문제의 {@code document_slug}는 FK가 아니라 이름표일 뿐이라
     * 가리키는 문서가 없을 수 있다(V9). 특히 흔한 경우가 <b>문제는 승인했는데 문서는 아직
     * 검수 대기</b>인 상황이다 — 검수 순서를 강제하지 않으므로 정상적으로 자주 생긴다.
     * 확인 없이 링크를 걸면 학습자가 눌렀을 때 "문서를 찾을 수 없습니다"를 만난다.
     *
     * <p><b>왜 {@code existsBySlug}를 반복하지 않고 목록으로 받나.</b> 오답노트는 한 화면에 20건이
     * 나오는데 건마다 조회하면 쿼리가 20번 나간다(N+1). slug를 모아 한 번에 묻고 결과를
     * {@code Set}으로 만들어 걸러 내면 <b>항상 쿼리 한 방</b>이다.
     *
     * <p>빈 목록으로 호출하면 {@code IN ()}이 되어 DB에 따라 문법 오류가 난다 — 호출부가
     * 비었을 때 아예 부르지 않도록 막아야 한다(그래서 이 메서드에는 방어를 두지 않았다).
     */
    @Query("select d.slug from Document d where d.slug in :slugs")
    List<String> findExistingSlugs(@Param("slugs") Collection<String> slugs);
}
