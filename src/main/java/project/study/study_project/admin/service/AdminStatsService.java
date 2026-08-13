package project.study.study_project.admin.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.admin.dto.AdminDashboardResponse;
import project.study.study_project.document.repository.DocumentRepository;
import project.study.study_project.llm.domain.DraftStatus;
import project.study.study_project.llm.repository.GeneratedDocumentDraftRepository;
import project.study.study_project.llm.repository.GeneratedProblemDraftRepository;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.quiz.repository.SubmissionRepository;
import project.study.study_project.user.repository.UserRepository;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
     * AI 초안 승인율용. 대시보드가 <b>정식 데이터(problem·document)뿐 아니라 초안까지</b> 보는
     * 이유는, 이 프로젝트에서 문제 창고를 실제로 채우는 통로가 AI 생성이기 때문이다 —
     * 승인율이 떨어지면 검수 부담이 늘고 결국 창고가 안 찬다.
     */
    private final GeneratedProblemDraftRepository problemDraftRepository;
    private final GeneratedDocumentDraftRepository documentDraftRepository;

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

        var llmReview = new AdminDashboardResponse.LlmReview(
                toModelStats(problemDraftRepository.countGroupByModelAndStatus()),
                toModelStats(documentDraftRepository.countGroupByModelAndStatus()));

        return new AdminDashboardResponse(totals, matrix, domainStats, stats, llmReview);
    }

    /**
     * (모델, 상태, 개수) 행들을 모델 한 줄씩으로 접는다.
     *
     * <p><b>왜 SQL이 아니라 여기서 접나.</b> DB에서 피벗(상태를 열로 눕히기)하려면
     * {@code SUM(CASE WHEN ...)}를 상태 수만큼 나열해야 하고, 상태가 하나 늘면 쿼리를 고쳐야 한다.
     * 모델 종류는 많아야 서너 개라 자바에서 접는 비용이 사실상 0이고, 대신
     * <b>"검수 0건이면 null"</b> 같은 판단을 표현하기 훨씬 쉽다(SQL에서는 0으로 나누기 방어가 붙는다).
     *
     * <p>{@link LinkedHashMap}을 쓴 이유: 순서가 뒤죽박죽이면 화면을 새로 고칠 때마다 표의
     * 행 순서가 바뀌어 눈으로 비교하기 어렵다. 아래에서 모델 이름으로 한 번 더 정렬한다.
     */
    private List<AdminDashboardResponse.ModelStat> toModelStats(
            List<GeneratedProblemDraftRepository.ModelStatusCount> rows) {

        Map<String, EnumMap<DraftStatus, Long>> byModel = new LinkedHashMap<>();
        for (var row : rows) {
            // model은 NOT NULL이지만, 옛 파일에서 흡수된 초안은 "unknown"으로 들어온다(DraftImportService).
            byModel.computeIfAbsent(row.getModel(), k -> new EnumMap<>(DraftStatus.class))
                    .merge(row.getStatus(), row.getCnt(), Long::sum);
        }

        return byModel.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    EnumMap<DraftStatus, Long> counts = entry.getValue();
                    long pending = counts.getOrDefault(DraftStatus.PENDING, 0L);
                    long approved = counts.getOrDefault(DraftStatus.APPROVED, 0L);
                    long rejected = counts.getOrDefault(DraftStatus.REJECTED, 0L);
                    long reviewed = approved + rejected;
                    // 검수 전(pending)은 분모에서 뺀다 — 아직 판정 안 난 것을 실패로 세면
                    // 부지런히 만들수록 승인율이 떨어지는 이상한 지표가 된다.
                    Integer rate = reviewed == 0 ? null
                            : (int) Math.round(approved * 100.0 / reviewed);
                    return new AdminDashboardResponse.ModelStat(
                            entry.getKey(), pending, approved, rejected, rate);
                })
                .toList();
    }
}
