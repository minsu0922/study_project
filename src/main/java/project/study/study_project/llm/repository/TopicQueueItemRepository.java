package project.study.study_project.llm.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import project.study.study_project.global.common.Domain;
import project.study.study_project.llm.domain.TopicQueueItem;

import java.util.List;

/**
 * 주제 대기열 조회 — {@code topic_queue}(V10).
 *
 * <p>조회가 셋뿐인 이유: 이 테이블을 보는 곳이 관리 화면·내보내기·동기화 셋뿐이고,
 * 셋 다 "순서대로 전부" 또는 "대기 중인 것 전부"를 본다. 대기열은 수십 줄 규모라
 * 페이징이나 조건 검색을 미리 만들어 둘 이유가 없다.
 */
public interface TopicQueueItemRepository extends JpaRepository<TopicQueueItem, Long> {

    /** 아직 안 쓴 주제를 순서대로 — 내보내기와 "다음 주제"가 보는 목록. */
    List<TopicQueueItem> findByUsedAtIsNullOrderBySortOrderAsc();

    /** 전체를 순서대로 — 관리 화면은 다 쓴 주제도 함께 보여 준다(학습 기록). */
    List<TopicQueueItem> findAllByOrderBySortOrderAsc();

    /** 배지·요약용 대기 건수. */
    long countByUsedAtIsNull();

    /**
     * 같은 분야에 같은 주제가 이미 대기 중인지 — 중복 추가와 중복 흡수를 함께 막는다.
     *
     * <p>다 쓴 주제는 세지 않는다({@code UsedAtIsNull}). 예전에 다룬 주제를 다시 넣는 것은
     * 정당한 요구다(문서를 지웠거나, 더 깊이 다시 보고 싶을 때).
     */
    boolean existsByDomainAndTopicAndUsedAtIsNull(Domain domain, String topic);

    /**
     * 지금까지 쓴 가장 큰 순서값. 새 주제는 이 뒤에 붙는다.
     *
     * <p>{@code count()}로 대신하지 않는 이유: 중간을 삭제하면 개수와 순서값이 어긋나
     * <b>이미 쓰이는 값</b>이 나온다. 그러면 새 주제가 기존 주제와 같은 자리에 끼어들어
     * 순서가 뒤죽박죽이 된다.
     */
    @Query("select coalesce(max(t.sortOrder), 0) from TopicQueueItem t")
    int findMaxSortOrder();
}
