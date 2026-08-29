package project.study.study_project.llm.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.llm.domain.DraftStatus;
import project.study.study_project.llm.domain.GeneratedProblemDraft;
import project.study.study_project.quiz.repository.ProblemRepository;

import java.util.List;

/**
 * LLM 생성 초안 저장소. 검수 화면 목록 + 중복 방지용 질문 텍스트 조회.
 * 파생 쿼리(메서드 이름 규칙)로 충분한 것은 @Query 없이 두었다 — 단순 조회에
 * JPQL을 손으로 쓰면 오타 검증 시점만 늦어진다(파생 쿼리는 부팅 시 검증).
 */
public interface GeneratedProblemDraftRepository extends JpaRepository<GeneratedProblemDraft, Long> {

    /** 검수 화면 목록 — 상태 필터, 오래된 순(먼저 생성된 것부터 처리). V6 인덱스와 정렬 방향 일치. */
    Page<GeneratedProblemDraft> findByStatusOrderByCreatedAtAsc(DraftStatus status, Pageable pageable);

    /**
     * 검수 화면 목록 — 상태에 <b>분야·난이도·근거 문서</b>를 더해 좁힌다(2026-08-29).
     *
     * <h2>왜 필요했나</h2>
     *
     * <p>필터가 상태 하나뿐이라 검수 대기 47건이 한 줄로 쏟아졌다. 그런데 검수의 실제 단위는
     * "오늘 들어온 전부"가 아니라 <b>"이 문서로 만든 것들"</b>이다 — 같은 문서에서 나온 문제
     * 다섯을 나란히 봐야 서로 겹치는지, 난이도가 실제로 갈리는지를 판단할 수 있다.
     * 문서 하나로 열 건 넘게 만들어 놓고 그걸 못 모아 본다는 것이 이 화면의 가장 큰 불편이었다.
     *
     * <h2>파생 쿼리를 쓰지 않은 이유</h2>
     *
     * <p>이름 규칙으로 쓰면 {@code findByStatusAndDomainAndDifficultyAndDocumentSlug…}가 되는데,
     * <b>선택 조건 셋을 조합</b>하려면 메서드가 여덟 개 필요하다. null이면 건너뛰는 조건을
     * JPQL 한 곳에 모으는 편이 짧고, 조건이 하나 늘어도 메서드가 배로 늘지 않는다.
     * (이 저장소의 {@code ProblemRepository.findForAdmin}이 같은 방식이다.)
     *
     * @param domain       null이면 분야를 가리지 않는다
     * @param difficulty   null이면 난이도를 가리지 않는다
     * @param documentSlug null이면 근거 문서를 가리지 않는다. <b>빈 문자열이 아니라 null</b>이어야
     *                     한다 — 컨트롤러가 빈 값을 null로 정규화한다
     */
    @Query("""
            select d from GeneratedProblemDraft d
            where d.status = :status
              and (:domain is null or d.domain = :domain)
              and (:difficulty is null or d.difficulty = :difficulty)
              and (:documentSlug is null or d.documentSlug = :documentSlug)
            order by d.createdAt asc
            """)
    Page<GeneratedProblemDraft> findForReview(
            @Param("status") DraftStatus status,
            @Param("domain") Domain domain,
            @Param("difficulty") Difficulty difficulty,
            @Param("documentSlug") String documentSlug,
            Pageable pageable);

    /**
     * 검수 화면 필터에 채울 <b>근거 문서 목록</b> — 그 상태에 실제로 존재하는 slug만.
     *
     * <p>등록된 문서 전체를 드롭다운에 넣지 않는 이유: 문서는 6편인데 초안이 걸린 것은
     * 두세 편뿐일 수 있고, 그러면 고르는 족족 "0건"이 나온다. <b>고를 수 있는 것만 보여 주는</b>
     * 목록이 고르고 나서 실망하지 않는 목록이다.
     */
    @Query("""
            select distinct d.documentSlug from GeneratedProblemDraft d
            where d.status = :status and d.documentSlug is not null
            order by d.documentSlug
            """)
    List<String> findDocumentSlugsByStatus(@Param("status") DraftStatus status);

    /** 대기 건수 — 관리자 화면 배지("검수 대기 N건")용. */
    long countByStatus(DraftStatus status);

    /**
     * 같은 도메인의 PENDING 초안 질문 텍스트 — 생성 프롬프트의 중복 회피 목록에 포함한다.
     * 기존 problem만 피하게 하면 "아직 검수 안 된 초안과 똑같은 문제"가 또 생성될 수 있다.
     */
    @Query("""
            select d.question from GeneratedProblemDraft d
            where d.domain = :domain and d.status = 'PENDING'
            """)
    List<String> findPendingQuestionsByDomain(@Param("domain") Domain domain);

    /**
     * 검수 대기(PENDING) 초안의 도메인×난이도 집계 — "가장 부족한 칸" 선택에 정식 문제와 <b>합산</b>된다.
     *
     * <p>이 쿼리가 없던 시절의 버그: 칸 선택은 정식 문제만 세는데(ProblemRepository) 중복 회피 목록에는
     * 초안까지 넣었다. 그래서 검수를 미루면 그 칸의 정식 문제 수가 계속 0이라 매일 같은 칸만 뽑혔다
     * — 내용은 안 겹치지만(회피 목록 덕분) "빈 칸을 골고루 채운다"는 전략 자체가 무너진다.
     * 수동 생성만 할 때는 드러나지 않고 <b>일일 배치를 켜는 순간</b> 나타나는 종류의 결함이다.
     *
     * <p>반환 타입으로 {@link ProblemRepository.DomainDifficultyCount}를 재사용하는 이유:
     * 모양이 같은 프로젝션 인터페이스를 두 벌 두면 언젠가 한쪽만 바뀌어 어긋난다.
     * 호출부(LlmProblemService)가 두 결과를 같은 맵에 합치므로 타입이 같아야 코드도 단순해진다.
     */
    @Query("""
            select d.domain as domain, d.difficulty as difficulty, count(d) as cnt
            from GeneratedProblemDraft d
            where d.status = 'PENDING'
            group by d.domain, d.difficulty
            """)
    List<ProblemRepository.DomainDifficultyCount> countPendingGroupByDomainAndDifficulty();

    /**
     * 최근 거절 사례(지문 + 사유) — 생성 프롬프트에 되먹인다(docs/14).
     *
     * <p><b>도메인으로 거르지 않는 이유</b>: 거절 사유는 대개 "부정형 문제라 검수가 어렵다",
     * "단순 암기 확인이다"처럼 <b>분야를 가리지 않는 출제 품질 문제</b>다. 네트워크 문제를
     * 만들 때도 운영체제 문제가 거절된 이유가 그대로 교훈이 된다. 도메인별로 나누면
     * 표본이 잘게 쪼개져 대부분의 칸에서 사례가 0건이 되어 기능이 무력해진다.
     *
     * <p><b>사유가 없는 거절은 제외</b>: reject 사유는 선택 입력이라 NULL일 수 있는데,
     * 지문만 있고 이유가 없으면 모델에게 "이건 왜인지 모르지만 나쁘다"는 말이 되어 도움이 안 된다.
     * (중복 방지 목적이라면 이미 중복 회피 목록이 담당한다.)
     *
     * <p>최근 처리 순으로 정렬 — 프롬프트를 개선한 뒤의 최신 판단이 앞에 오게 한다.
     * 개수 제한은 {@link Pageable}로 호출부가 정한다(입력 토큰 비용과의 균형).
     */
    @Query("""
            select d.question as question, d.rejectReason as reason
            from GeneratedProblemDraft d
            where d.status = 'REJECTED' and d.rejectReason is not null
            order by d.reviewedAt desc
            """)
    List<RejectionNoteView> findRecentRejectionNotes(Pageable pageable);

    /**
     * 거절 사례 조회 결과 프로젝션. 엔티티 전체를 읽지 않는 이유는 필요한 두 컬럼만 가져오기 위해서다
     * — 초안의 question·explanation·choices_json은 TEXT라 전부 읽으면 쓸데없이 크다.
     */
    interface RejectionNoteView {
        String getQuestion();

        String getReason();
    }

    /**
     * 모델별 × 상태별 초안 수 — 관리자 대시보드의 <b>승인율</b> 표에 쓴다.
     *
     * <p><b>왜 필요했나.</b> {@code model} 컬럼은 처음부터 "모델을 바꿨을 때 품질이 올랐는지
     * 비교하려고" 기록해 왔고, 설계 문서에도 <b>"측정 없이 바꾸지 않는다"</b>고 적어 뒀다.
     * 그런데 정작 <b>승인율을 볼 방법이 없었다</b> — 재료만 모으고 저울이 없던 셈이다.
     *
     * <p>여기서 비율까지 계산하지 않고 개수만 돌려주는 이유: 나눗셈을 SQL에 넣으면
     * "검수한 게 0건일 때 0으로 나누기"를 DB 방언마다 다르게 처리해야 하고, 그 결과
     * <b>"0%(전부 거절)"과 "아직 안 봄"이 구분되지 않는다.</b> 개수를 그대로 받아
     * 서비스에서 판단하면 후자를 {@code null}로 남길 수 있다(ProblemStat과 같은 규칙).
     *
     * <p>초안이 수백 건 규모라 인덱스 없이 전체 스캔해도 무해하다. 대시보드는 관리자
     * 한 명이 가끔 여는 화면이라 여기에 인덱스를 더하는 것은 쓰기 비용만 늘리는 손해다.
     */
    @Query("""
            select d.model as model, d.status as status, count(d) as cnt
            from GeneratedProblemDraft d
            group by d.model, d.status
            """)
    List<ModelStatusCount> countGroupByModelAndStatus();

    /**
     * 모델별 집계 프로젝션. 문서 초안 쪽({@code GeneratedDocumentDraftRepository})도 <b>이 타입을
     * 재사용</b>한다 — 모양이 같은 인터페이스를 두 벌 두면 언젠가 한쪽만 바뀌어 어긋나고,
     * 조립하는 서비스가 두 결과를 같은 방식으로 다뤄야 코드도 단순해진다
     * ({@code DomainDifficultyCount}를 공유한 것과 같은 판단).
     */
    interface ModelStatusCount {
        String getModel();

        DraftStatus getStatus();

        long getCnt();
    }
}
