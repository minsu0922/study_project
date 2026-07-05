package project.study.study_project.document.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import project.study.study_project.document.dto.DocumentListItem;
import project.study.study_project.global.common.Domain;

import java.util.List;

/**
 * Document 커스텀 조회 — QueryDSL 구현(DocumentRepositoryImpl)의 계약.
 *
 * <p>Spring Data 관례: {@code XxxRepositoryCustom} 인터페이스 + {@code XxxRepositoryImpl} 구현을
 * 만들고 본 리포지토리가 Custom을 상속하면, 스프링이 이름 규칙(Impl 접미사)으로 구현체를 찾아
 * 이어 붙인다 — 기본 CRUD(JpaRepository)와 손수 짠 QueryDSL 쿼리가 한 인터페이스로 합쳐진다.
 */
public interface DocumentRepositoryCustom {

    /**
     * 문서 목록 화면용 조회 — 필터(도메인·태그) + 페이징 + 정렬을 QueryDSL로 처리하고,
     * <b>목록에 필요한 컬럼만</b> 뽑아 DTO로 바로 만든다(본문 content_md는 읽지 않음 — 구현 주석 참고).
     */
    Page<DocumentListItem> searchListItems(Domain domain, List<String> tagNames, Pageable pageable);
}
