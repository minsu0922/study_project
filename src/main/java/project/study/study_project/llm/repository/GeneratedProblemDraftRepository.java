package project.study.study_project.llm.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import project.study.study_project.global.common.Domain;
import project.study.study_project.llm.domain.DraftStatus;
import project.study.study_project.llm.domain.GeneratedProblemDraft;

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
}
