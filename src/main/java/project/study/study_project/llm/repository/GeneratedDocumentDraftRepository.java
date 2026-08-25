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
     *
     * <h2>2026-08-25 — 거절이 나중의 승인을 덮어쓰고 있었다</h2>
     *
     * <p>전에는 {@code status = 'REJECTED'}만 봤다. 그러면 <b>같은 주제를 다시 뽑아 승인해도</b>
     * 옛 거절 기록 때문에 영영 "쓰지 마라" 목록에 남는다. 배치는 그 문서를 근거로 삼지 않고
     * 폴백으로 돌아가는데, 로그에는 "검수에서 거절돼 폴백으로 생성합니다"라고만 찍혀
     * <b>이미 승인한 문서인데 왜 안 쓰지</b>를 알아채기 어렵다.
     *
     * <p>실제로 걸린 자리: OSI 계층별 장애 진단 문서가 2026-08-23에 거절됐고(기본 개념 절이
     * 없어 비개발자가 읽을 수 없었다), 문서 프롬프트를 고친 뒤 같은 주제를 다시 뽑아 승인할
     * 참이었다. 그대로 뒀다면 새 문서가 승인된 채로 배치에서만 조용히 무시됐을 것이다.
     *
     * <p><b>고친 방향</b>: 같은 slug로 승인된 초안이 하나라도 있으면 목록에서 뺀다.
     * "거절했다"는 사실보다 "지금 이 slug의 문서가 쓸 만한가"가 이 목록이 답해야 하는 질문이다.
     */
    @Query("""
            select d.slug from GeneratedDocumentDraft d
            where d.status = 'REJECTED'
              and d.slug not in (
                  select a.slug from GeneratedDocumentDraft a where a.status = 'APPROVED'
              )
            order by d.slug
            """)
    List<String> findRejectedSlugs();

    /**
     * 모델별 × 상태별 초안 수 — 대시보드 승인율 표(문제 쪽과 같은 목적).
     *
     * <p>문서와 문제를 <b>한 표로 합치지 않는</b> 이유: 둘은 검수 기준도 생성 빈도도 다르다.
     * 문서는 하루 한 편이라 표본이 훨씬 적어서, 합치면 문제 쪽 숫자에 묻혀 문서의 승인율
     * 변화가 안 보인다. "모델을 바꿨더니 문서만 나빠졌다" 같은 판단을 하려면 나뉘어 있어야 한다.
     *
     * <p>반환 타입은 문제 쪽 {@link GeneratedProblemDraftRepository.ModelStatusCount}를 재사용한다.
     */
    @Query("""
            select d.model as model, d.status as status, count(d) as cnt
            from GeneratedDocumentDraft d
            group by d.model, d.status
            """)
    List<GeneratedProblemDraftRepository.ModelStatusCount> countGroupByModelAndStatus();
}
