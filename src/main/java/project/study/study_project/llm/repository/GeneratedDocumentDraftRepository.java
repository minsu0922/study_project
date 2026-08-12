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

    /**
     * 거절된 문서의 slug — 클라우드 배치가 "이 문서로는 문제를 만들지 마라"를 알기 위한 목록(2단계).
     *
     * <p>거절된 문서 파일은 저장소에 그대로 남아 있다(생성 결과는 지우지 않는다). 배치는 날짜만 보고
     * 그 파일을 근거로 삼으려 하므로, 막지 않으면 <b>검수자가 버린 문서로 사흘치 문제가 나온다</b>.
     *
     * <p>승인된 slug 목록을 주는 대안도 있었지만 그러면 "아직 검수 안 한" 문서까지 걸러진다 —
     * 승인을 며칠 미뤘다고 그 주기를 날리면 배치가 사람의 검수 속도에 인질로 잡힌다.
     * 거절만 실어 보내면 미검수는 자연스럽게 통과한다(부정 목록이 맞는 자리).
     *
     * <p>정렬을 넣은 이유: 스냅샷 파일은 <b>내용이 같으면 다시 쓰지 않는</b>다. DB 순서에 맡기면
     * 같은 목록이 다른 순서로 나와 파일이 매번 바뀐 것으로 보인다({@code ExistingDocumentsExporter}).
     */
    @Query("""
            select d.slug from GeneratedDocumentDraft d
            where d.status = 'REJECTED'
            order by d.slug
            """)
    List<String> findRejectedSlugs();
}
