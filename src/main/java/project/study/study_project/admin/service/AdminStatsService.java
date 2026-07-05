package project.study.study_project.admin.service;

import lombok.RequiredArgsConstructor;
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

        List<AdminDashboardResponse.ProblemStat> stats = submissionRepository.aggregateProblemStats()
                .stream()
                .map(row -> new AdminDashboardResponse.ProblemStat(
                        row.getProblemId(), row.getDomain(), row.getType(), row.getQuestion(),
                        row.getAttempts(), row.getCorrectCount(),
                        // 제출 0건은 "데이터 없음"(null) — 0%(전부 오답)와 의미가 다르다(DTO 주석 참고)
                        row.getAttempts() == 0 ? null
                                : (int) Math.round(row.getCorrectCount() * 100.0 / row.getAttempts())))
                .toList();

        return new AdminDashboardResponse(totals, matrix, stats);
    }
}
