package project.study.study_project.report.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import project.study.study_project.report.domain.ProblemReport;
import project.study.study_project.report.domain.ReportStatus;

import java.util.List;

/**
 * 제보 저장소. 읽는 축이 셋뿐이라 메서드도 셋이다 — 제보함 목록, 배지 숫자, 되먹임 사례.
 */
public interface ProblemReportRepository extends JpaRepository<ProblemReport, Long> {

    /**
     * 이미 제보했는가 — 중복 접수를 400/409로 되돌리기 위한 사전 확인.
     *
     * <p><b>이 검사가 있어도 UNIQUE 제약은 필요하다</b>(그 반대도 마찬가지다). 여기서 보는 것과
     * INSERT 사이에 같은 사람의 두 번째 요청이 끼어들 수 있어(더블클릭) 최종 방어는 DB가 한다.
     * 그럼에도 미리 세는 이유는 <b>메시지</b> 때문이다 — 제약 위반은 500으로 새어 나가지만
     * 여기서 걸리면 "이미 제보하셨습니다"라는 말을 돌려줄 수 있다.
     */
    boolean existsByProblem_IdAndUserId(Long problemId, Long userId);

    /** 제보함 배지 — 아직 안 본 건수. */
    long countByStatus(ReportStatus status);

    /**
     * 제보함 목록. 상태가 {@code null}이면 전체다.
     *
     * <p>정렬을 파라미터로 받지 않고 못 박은 이유: 이 화면에서 의미 있는 순서는 하나뿐이다.
     * 대기 중인 것은 <b>오래 기다린 것부터</b>(방치를 드러낸다), 처리된 것은 <b>최근 것부터</b>
     * 보고 싶은데, 두 요구가 한 정렬로 안 만나므로 아래 두 메서드로 갈랐다.
     * 화면이 상태를 고르면 정렬도 함께 정해진다 — 사용자가 조합을 고민할 자리가 없다.
     *
     * <p>{@code join fetch}로 문제를 함께 읽는다. 목록이 지문을 보여 주므로 안 하면 행마다
     * 추가 조회가 붙는다(N+1). 개수 조회 쿼리는 fetch가 없어야 하므로 따로 적는다.
     */
    @Query(value = """
            select r from ProblemReport r join fetch r.problem
            where (:status is null or r.status = :status)
            order by r.createdAt asc
            """,
            countQuery = "select count(r) from ProblemReport r where (:status is null or r.status = :status)")
    Page<ProblemReport> findOldestFirst(ReportStatus status, Pageable pageable);

    /** 위와 같되 최신순 — 처리 완료 목록이 쓴다. */
    @Query(value = """
            select r from ProblemReport r join fetch r.problem
            where (:status is null or r.status = :status)
            order by r.createdAt desc
            """,
            countQuery = "select count(r) from ProblemReport r where (:status is null or r.status = :status)")
    Page<ProblemReport> findNewestFirst(ReportStatus status, Pageable pageable);

    /**
     * 되먹임에 실을 사례 — <b>인정된 것만</b>, 최근 처리 순.
     *
     * <p>기각된 제보를 빼는 이유는 명백하지만 한 번 적어 둔다: 기각은 "제보가 틀렸다"는 판정이라
     * 그것을 프롬프트에 넣으면 <b>멀쩡한 출제 방식을 하지 말라고 가르치는</b> 셈이 된다.
     *
     * <p>최근 처리 순인 것은 거절 사례와 같은 규칙이다 — 프롬프트를 개선한 뒤의 최신 판단이
     * 앞에 와야 한다({@code GeneratedProblemDraftRepository.findRecentRejectionNotes} 주석).
     * 개수는 호출부가 {@link Pageable}로 정한다(입력 토큰 비용과의 균형).
     */
    @Query("""
            select r from ProblemReport r join fetch r.problem
            where r.status = 'ACCEPTED'
            order by r.resolvedAt desc
            """)
    List<ProblemReport> findAcceptedForFeedback(Pageable pageable);
}
