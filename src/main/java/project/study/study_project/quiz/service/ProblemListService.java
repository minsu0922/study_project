package project.study.study_project.quiz.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.response.PageResponse;
import project.study.study_project.quiz.dto.ProblemListItem;
import project.study.study_project.quiz.dto.StudySummaryResponse;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.quiz.repository.SubmissionRepository;
import project.study.study_project.review.repository.ReviewItemRepository;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 학습자 문제 목록 화면(docs/18) — 목록 한 판과 통계 요약(2026-08-29 신설).
 *
 * <p>이 화면의 유일한 목적은 <b>다음에 풀 문제 하나를 30초 안에 고르게 하는 것</b>이다.
 * 그래서 여기서 만드는 값들은 전부 그 선택에 쓰이는 것만 있다 — 무엇을 안 풀었나(state),
 * 언제 마지막으로 건드렸나(lastAttemptedAt), 지금 복습할 때인가(reviewDue).
 *
 * <p><b>퀴즈 풀이(QuizService)와 갈라 둔 이유</b>: 저쪽은 "문제를 내고 채점한다"이고 여기는
 * "무엇을 풀지 고르게 한다"다. 재료는 겹치지만 한 클래스에 넣으면 채점 규칙을 고치러 들어온
 * 사람이 목록 정렬 코드를 함께 읽어야 한다.
 */
@Service
@RequiredArgsConstructor
public class ProblemListService {

    private final ProblemRepository problemRepository;
    private final SubmissionRepository submissionRepository;
    private final ReviewItemRepository reviewItemRepository;

    /**
     * 목록 한 판.
     *
     * <p>기준 시각을 여기서 한 번 읽어 쿼리에 넘긴다. 쿼리 안에서 현재 시각을 읽으면 같은
     * 요청 안에서도 값이 흔들리고, 테스트에서 "복습 차례"를 만들 방법이 없어진다.
     *
     * @param state {@code null}이면 상태를 가리지 않는다
     * @param onlyDue {@code true}면 지금 복습 차례인 문제만
     */
    @Transactional(readOnly = true)
    public PageResponse<ProblemListItem> getList(Long userId, Domain domain, Difficulty difficulty,
                                                 ProblemListItem.SolveState state, boolean onlyDue,
                                                 Pageable pageable) {
        LocalDateTime now = LocalDateTime.now();
        return PageResponse.from(problemRepository
                .findListForUser(userId, domain, difficulty,
                        state == null ? null : state.name(), onlyDue, now, pageable)
                .map(row -> new ProblemListItem(
                        row.getId(),
                        row.getTitle(),
                        row.getDomain(),
                        row.getDomain().getDisplayName(),
                        row.getDifficulty(),
                        row.getType(),
                        row.getLastAttemptedAt(),
                        stateOf(row),
                        row.getDueCount() > 0)));
    }

    /**
     * 개수 → 상태. 판정 규칙이 <b>한 곳에만</b> 있어야 목록과 통계 카드가 같은 말을 한다.
     *
     * <p>맞힌 적이 있으면 마지막 시도가 오답이어도 {@code CORRECT}다(사용자 결정, DTO 주석 참고).
     * "지금도 아는지"는 {@code reviewDue}가 따로 말한다.
     */
    private ProblemListItem.SolveState stateOf(ProblemRepository.ProblemListRow row) {
        if (row.getCorrectCount() > 0) {
            return ProblemListItem.SolveState.CORRECT;
        }
        return row.getAttemptCount() > 0
                ? ProblemListItem.SolveState.WRONG
                : ProblemListItem.SolveState.UNSOLVED;
    }

    /**
     * 통계 카드 + 분야별 진척.
     *
     * <p>"이번 주"의 시작은 <b>월요일 0시</b>다. 일요일 시작을 쓰면 주말에 몰아 푸는 사람에게
     * 토·일이 서로 다른 주로 갈린다. ISO 기준이기도 하다.
     */
    @Transactional(readOnly = true)
    public StudySummaryResponse getSummary(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime weekStart = LocalDate.now().with(DayOfWeek.MONDAY).atStartOfDay();

        var overall = submissionRepository.aggregateOverall(userId);
        // 제출이 0건이면 정답률은 0%가 아니라 "없음"이다 — 0%(다 틀렸다)와 정반대 신호라
        // 뭉개면 안 된다(대시보드 정답률에서 이미 배운 구분).
        Integer correctRate = (overall == null || overall.getTotal() == 0) ? null
                : (int) Math.round(
                        (overall.getCorrectCount() == null ? 0L : overall.getCorrectCount()) * 100.0
                                / overall.getTotal());

        var stats = new StudySummaryResponse.Stats(
                submissionRepository.countSolvedProblems(userId),
                correctRate,
                submissionRepository.countSolvedProblemsSince(userId, weekStart),
                reviewItemRepository.countDue(userId, now));

        return new StudySummaryResponse(stats, domainProgress(userId));
    }

    /**
     * 분야별 맞힌 개수 — <b>모든 분야를 0으로라도 채워</b> 돌려준다.
     *
     * <p>집계 쿼리는 맞힌 적 없는 분야를 아예 안 준다(GROUP BY의 성질). 그대로 내려보내면
     * 사이드바에서 <b>손대지 않은 분야가 사라져</b>, 정작 "여기부터 해 볼까"의 후보가 안 보인다.
     * 빠진 분야를 만들어 내려면 Domain 목록이 필요한데 그건 자바가 아는 것이라 여기서 채운다.
     */
    private List<StudySummaryResponse.DomainProgress> domainProgress(Long userId) {
        Map<Domain, Long> solved = new EnumMap<>(Domain.class);
        submissionRepository.countSolvedByDomain(userId)
                .forEach(row -> solved.put(row.getDomain(), row.getSolved()));

        return java.util.Arrays.stream(Domain.values())
                .map(domain -> new StudySummaryResponse.DomainProgress(
                        domain, domain.getDisplayName(), solved.getOrDefault(domain, 0L)))
                .toList();
    }
}
