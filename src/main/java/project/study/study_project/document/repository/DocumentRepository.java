package project.study.study_project.document.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import project.study.study_project.document.domain.Document;

import java.util.Optional;

/**
 * Document 저장소.
 *
 * <p>{@link JpaSpecificationExecutor}를 함께 상속해 <b>동적 조건 검색</b>을 지원한다.
 * 목록 API의 필터(도메인·태그)는 있을 수도 없을 수도 있어, JPQL에 {@code (:x is null or ...)}를
 * 늘어놓는 대신 Specification으로 조건을 조립하는 편이 깔끔하고 타입 안전하다(서비스 참고).
 */
public interface DocumentRepository extends JpaRepository<Document, Long>, JpaSpecificationExecutor<Document> {

    /** slug로 문서 단건 조회(없으면 DOC_001). */
    Optional<Document> findBySlug(String slug);
}
