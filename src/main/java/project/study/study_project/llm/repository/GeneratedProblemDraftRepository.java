package project.study.study_project.llm.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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
}
