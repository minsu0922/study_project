package project.study.study_project.llm.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import project.study.study_project.global.common.Domain;
import project.study.study_project.llm.domain.TopicQueueItem;

import java.util.List;

/**
 * 주제 범위 대기열 조회 — {@code topic_queue}(V10·V11).
 *
 * <p>조회가 단출한 이유: 이 테이블을 보는 곳이 관리 화면·내보내기·동기화 셋뿐이고,
 * 셋 다 <b>전부를 순서대로</b> 본다. 범위는 소진되지 않으므로 "대기 중인 것만" 같은 구분도
 * 없다(V11에서 사라졌다). 수십 줄 규모라 페이징이나 조건 검색을 미리 만들 이유도 없다.
 */
public interface TopicQueueItemRepository extends JpaRepository<TopicQueueItem, Long> {

    /** 사람이 정한 순서대로 — 화면과 내보내기가 같은 순서를 본다(그래야 "다음 차례"가 일치한다). */
    List<TopicQueueItem> findAllByOrderBySortOrderAsc();

    /**
     * 같은 분야에 같은 이름의 범위가 이미 있는지.
     *
     * <p>V10에서는 "대기 중인 것만" 봤다. 소진 개념이 있었을 때는 다 쓴 주제를 다시 넣는 것이
     * 정당했기 때문인데, 범위는 소진되지 않으므로 같은 이름이 둘 있으면 <b>그냥 중복</b>이다
     * (순환에서 그 범위만 두 배로 자주 나온다).
     */
    boolean existsByDomainAndTopic(Domain domain, String topic);

    /**
     * 지금까지 쓴 가장 큰 순서값. 새 범위는 이 뒤에 붙는다.
     *
     * <p>{@code count()}로 대신하지 않는 이유: 중간을 삭제하면 개수와 순서값이 어긋나
     * <b>이미 쓰이는 값</b>이 나온다. 그러면 새 범위가 기존 범위와 같은 자리에 끼어들어
     * 순서가 뒤죽박죽이 된다.
     */
    @Query("select coalesce(max(t.sortOrder), 0) from TopicQueueItem t")
    int findMaxSortOrder();
}
