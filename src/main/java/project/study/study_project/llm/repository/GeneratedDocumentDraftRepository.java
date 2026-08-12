package project.study.study_project.llm.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import project.study.study_project.llm.domain.DraftStatus;
import project.study.study_project.llm.domain.GeneratedDocumentDraft;

import java.util.List;

/**
 * 개념 문서 초안 저장소 — docs/15.
 *
 * <p>{@link GeneratedProblemDraftRepository}보다 훨씬 단출하다. 문제 초안에는 "가장 부족한
 * 칸 집계"와 "거절 사례 되먹임" 쿼리가 있지만 문서에는 없다. 문서 주제 선택은 클라우드에서
 * 날짜 기반 순환({@code GenerationSchedule})으로 이미 정해지므로 DB 집계가 필요 없고,
 * 거절 사례 되먹임은 문서 생성량이 쌓인 뒤에 판단할 일이라 지금 넣으면 표본 0건짜리
 * 기능만 늘어난다(필요해지면 그때 문제 쪽 쿼리를 그대로 베끼면 된다).
 */
public interface GeneratedDocumentDraftRepository extends JpaRepository<GeneratedDocumentDraft, Long> {

    /** 검수 화면 목록 — 상태 필터, 오래된 순. V8 인덱스(status, created_at)와 정렬 방향 일치. */
    Page<GeneratedDocumentDraft> findByStatusOrderByCreatedAtAsc(DraftStatus status, Pageable pageable);

    /** 대기 건수 — 관리자 화면 배지("문서 검수 대기 N건")용. */
    long countByStatus(DraftStatus status);

    /**
     * 아직 검수 안 된 초안의 제목 — 다음 생성의 중복 회피 목록에 정식 문서 제목과 <b>함께</b> 넣는다.
     *
     * <p>정식 문서만 피하게 하면 어떤 일이 생기나: 오늘 "캐시 전략" 문서가 대기함에 들어왔는데
     * 아직 승인을 안 했다면, 내일 배치는 그 주제가 없는 줄 알고 <b>같은 주제를 또 쓴다</b>.
     * 문제 쪽에서 이미 겪은 구조라 처음부터 넣어 둔다
     * ({@link GeneratedProblemDraftRepository#findPendingQuestionsByDomain} 참고).
     *
     * <p>도메인으로 거르지 않는 이유: 문서는 하루 한 편이라 전체가 수십 건 규모다. 게다가
     * "TCP 혼잡 제어"가 NETWORK로 갔다가 다음엔 SYSTEM_DESIGN으로 분류될 수도 있어,
     * 도메인으로 나누면 그 경계를 넘나드는 중복을 못 잡는다.
     */
    @Query("""
            select d.title from GeneratedDocumentDraft d
            where d.status = 'PENDING'
            """)
    List<String> findPendingTitles();
}
