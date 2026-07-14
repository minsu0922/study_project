package project.study.study_project.admin.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.admin.dto.AdminDashboardResponse;
import project.study.study_project.document.repository.DocumentRepository;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.quiz.repository.SubmissionRepository;
import project.study.study_project.user.repository.UserRepository;

import java.util.List;

/**
 * 관리자 대시보드 통계 — 전체 요약 + 도메인×난이도 현황판 + 문제별 정답률.
 *
 * <p>집계는 전부 SQL(GROUP BY/COUNT)에서 끝내고 자바는 포장만 한다.
 * 전 행을 애플리케이션으로 끌어와 세는 방식은 데이터가 커지는 순간 무너진다.
 * 이 쿼리들은 로드맵 1(인덱스 효과 실측)의 측정 대상이기도 하다.
 */
@Service
@RequiredArgsConstructor
public class AdminStatsService {

    private final UserRepository userRepository;
    private final DocumentRepository documentRepository;
    private final ProblemRepository problemRepository;
    private final SubmissionRepository submissionRepository;

    /**
     * 뷰(domain_stats) 조회용. 뷰는 JPA 엔티티가 아니라서(PK 없음, 수정 불가)
     * 리포지토리 대신 JdbcClient로 SQL을 직접 실행한다 — 엔티티로 억지 매핑하는 것보다
     * "읽기 전용 SQL 결과 → DTO" 경로가 정직하다. JdbcClient는 Boot 3.2+의
     * JdbcTemplate 후속(체이닝 API)으로, 같은 DataSource/트랜잭션에 참여한다.
     */
    private final JdbcClient jdbcClient;

    @Transactional(readOnly = true)
    public AdminDashboardResponse getDashboard() {
        var totals = new AdminDashboardResponse.Totals(
                userRepository.count(),
                documentRepository.count(),
                problemRepository.count(),
                submissionRepository.count());

        List<AdminDashboardResponse.MatrixCell> matrix = problemRepository.countGroupByDomainAndDifficulty()
                .stream()
                .map(row -> new AdminDashboardResponse.MatrixCell(row.getDomain(), row.getDifficulty(), row.getCnt()))
                .toList();

        // 도메인별 정답률 — 집계는 뷰가 이미 끝냈으므로 여기선 SELECT * 수준의 단순 조회.
        // 정렬(정답률 낮은 순)은 화면 요구라 SQL에 두지 않고 프론트가 한다(뷰는 표현 중립 유지).
        List<AdminDashboardResponse.DomainStat> domainStats = jdbcClient
                .sql("""
                        SELECT domain, submission_count, correct_count, accuracy_pct,
                               attempted_problem_count, solver_count
                        FROM domain_stats
                        """)
                .query((rs, rowNum) -> new AdminDashboardResponse.DomainStat(
                        rs.getString("domain"),
                        rs.getLong("submission_count"),
                        rs.getLong("correct_count"),
                        rs.getDouble("accuracy_pct"),
                        rs.getLong("attempted_problem_count"),
                        rs.getLong("solver_count")))
                .list();

        List<AdminDashboardResponse.ProblemStat> stats = submissionRepository.aggregateProblemStats()
                .stream()
                .map(row -> new AdminDashboardResponse.ProblemStat(
                        row.getProblemId(), row.getDomain(), row.getType(), row.getQuestion(),
                        row.getAttempts(), row.getCorrectCount(),
                        // 제출 0건은 "데이터 없음"(null) — 0%(전부 오답)와 의미가 다르다(DTO 주석 참고)
                        row.getAttempts() == 0 ? null
                                : (int) Math.round(row.getCorrectCount() * 100.0 / row.getAttempts())))
                .toList();

        return new AdminDashboardResponse(totals, matrix, domainStats, stats);
    }
}
