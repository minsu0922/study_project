package project.study.study_project.learningpath.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.learningpath.domain.PathKey;
import project.study.study_project.learningpath.domain.UserLearningPath;
import project.study.study_project.learningpath.dto.LearningPathDetail;
import project.study.study_project.learningpath.dto.LearningPathRecommendation;
import project.study.study_project.learningpath.dto.LearningPathRecommendation.RecommendReason;
import project.study.study_project.learningpath.dto.LearningPathSummary;
import project.study.study_project.learningpath.repository.LearningPathStatsRepository;
import project.study.study_project.learningpath.repository.UserLearningPathRepository;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.dto.QuizProblemItem;
import project.study.study_project.quiz.dto.QuizResponse;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 학습 로드맵 사용자 API의 서비스 — 설계는 docs/26.
 *
 * <p>단계는 잠그지 않는다. 어느 단계든 문제 세트를 받을 수 있고, {@code nextSeq}는 권유일 뿐이다.
 * 채점은 기존 {@code POST /api/quiz/submit}이 한다. 그래서 틀린 문제는 복습 사다리에 그대로 오른다.
 */
@Service
@RequiredArgsConstructor
public class LearningPathService {

    private final CourseCatalog catalog;
    private final CourseProgressService progressService;
    private final LearningPathStatsRepository statsRepository;
    private final UserLearningPathRepository userPathRepository;

    /**
     * 코스 목록. 시작한 코스가 앞(최근 순), 그다음은 카탈로그 순서다.
     * 코스마다 진도 쿼리가 두 번 나간다. 코스가 스무 개 안팎이라 한 번에 묶지 않았다.
     */
    @Transactional(readOnly = true)
    public List<LearningPathSummary> list(Long userId) {
        Map<String, UserLearningPath> mine = mineByKey(userId);
        List<Course> courses = catalog.all();
        Map<String, Integer> catalogOrder = new java.util.HashMap<>();
        for (int i = 0; i < courses.size(); i++) catalogOrder.put(courses.get(i).key().toString(), i);

        return courses.stream()
                .map(c -> summaryOf(c, progressService.progressOf(userId, c), mine.get(c.key().toString())))
                .sorted(Comparator
                        .comparing((LearningPathSummary s) -> !s.started())
                        .thenComparing(s -> s.lastStudiedAt() == null ? LocalDateTime.MIN : s.lastStudiedAt(),
                                Comparator.reverseOrder())
                        .thenComparing(s -> catalogOrder.get(s.key())))
                .toList();
    }

    @Transactional(readOnly = true)
    public LearningPathDetail detail(Long userId, String rawKey) {
        Course course = courseOf(rawKey);
        CourseProgress progress = progressService.progressOf(userId, course);
        UserLearningPath started = userId == null ? null : mineByKey(userId).get(course.key().toString());

        List<LearningPathDetail.StepItem> steps = course.steps().stream().map(s -> {
            CourseProgress.StepProgress p = progress.steps().get(s.seq() - 1);
            return new LearningPathDetail.StepItem(s.seq(), s.title(), s.edition(), s.documentSlug(),
                    s.difficulty(), p.total(), p.attempted(), p.solved(), p.done());
        }).toList();
        return new LearningPathDetail(summaryOf(course, progress, started), steps);
    }

    /**
     * 단계 문제 세트 — 플레이어가 받는 {@link QuizResponse} 모양 그대로.
     * 쉬운 문제부터, 로그인했으면 아직 못 맞힌 문제를 앞에 세운다. 다 맞힌 단계도 전부 준다(다시 풀기).
     * 로그인 상태로 받으면 그 코스를 "시작한" 것으로 적는다 — 따로 [시작] 단추를 누르지 않아도 된다.
     */
    @Transactional
    public QuizResponse stepProblems(Long userId, String rawKey, int seq) {
        Course course = courseOf(rawKey);
        Course.Step step = course.steps().stream().filter(s -> s.seq() == seq).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.PATH_001, "없는 단계입니다."));

        List<Problem> problems = step.isDocument()
                ? statsRepository.findStepProblemsBySlug(step.documentSlug())
                : statsRepository.findStepCodeProblems(CourseCatalog.CODE_LANGUAGE, step.difficulty());

        Set<Long> solved = userId == null || problems.isEmpty() ? Set.of()
                : new HashSet<>(statsRepository.findSolvedProblemIds(userId, problems.stream().map(Problem::getId).toList()));
        List<Problem> ordered = problems.stream()
                .sorted(Comparator.comparing((Problem p) -> solved.contains(p.getId()))
                        .thenComparing(p -> p.getDifficulty().ordinal())
                        .thenComparing(Problem::getId))
                .toList();

        if (userId != null) userPathRepository.touch(userId, course.key().toString(), LocalDateTime.now());
        return new QuizResponse(ordered.stream().map(QuizProblemItem::from).toList());
    }

    /** 홈의 "이어서 하기" — 가장 최근에 공부한 코스. 없어진 코스는 건너뛴다 */
    @Transactional(readOnly = true)
    public Optional<LearningPathSummary> current(Long userId) {
        for (UserLearningPath u : userPathRepository.findMine(userId)) {
            Optional<Course> course = PathKey.parse(u.getPathKey()).flatMap(catalog::find);
            if (course.isPresent()) {
                return Optional.of(summaryOf(course.get(), progressService.progressOf(userId, course.get()), u));
            }
        }
        return Optional.empty();
    }

    /**
     * 홈의 "추천 코스" — 최대 {@code limit}개. 이어서 하기 카드(current)와 다 푼 코스, 문제가 없는 코스는 뺀다.
     * 순서는 이유 순서와 같다(RecommendReason). 같은 이유 안에서는 목록 순서(최근 공부 순 → 카탈로그 순)다.
     * 반쯤 푼 분야를 새 분야보다 앞에 둔다. 이미 쌓은 것을 마저 끝내는 쪽이 다시 오게 만든다(docs/26).
     */
    @Transactional(readOnly = true)
    public List<LearningPathRecommendation> recommend(Long userId, int limit) {
        String currentKey = current(userId).map(LearningPathSummary::key).orElse(null);
        return list(userId).stream()
                .filter(s -> !s.key().equals(currentKey) && s.nextSeq() != null && s.total() > 0)
                .map(s -> new LearningPathRecommendation(s, reasonOf(s)))
                .sorted(Comparator.comparing(r -> r.reason().ordinal()))
                .limit(limit)
                .toList();
    }

    private static RecommendReason reasonOf(LearningPathSummary s) {
        if (s.started()) return RecommendReason.STARTED;
        if (s.solved() > 0) return RecommendReason.HALF_DONE;
        if ("CUSTOM".equals(s.kind())) return RecommendReason.CURATED;
        return RecommendReason.NEW;
    }

    @Transactional
    public void start(Long userId, String rawKey) {
        Course course = courseOf(rawKey);
        userPathRepository.touch(userId, course.key().toString(), LocalDateTime.now());
    }

    /** 그만두기 — "진행 중"에서 뺀다. 풀이 기록은 그대로라 다시 시작하면 진도가 돌아온다 */
    @Transactional
    public void stop(Long userId, String rawKey) {
        PathKey key = PathKey.parse(rawKey).orElseThrow(() -> new BusinessException(ErrorCode.PATH_001));
        userPathRepository.remove(userId, key.toString());
    }

    private Course courseOf(String rawKey) {
        return PathKey.parse(rawKey).flatMap(catalog::find)
                .orElseThrow(() -> new BusinessException(ErrorCode.PATH_001));
    }

    private Map<String, UserLearningPath> mineByKey(Long userId) {
        if (userId == null) return Map.of();
        return userPathRepository.findMine(userId).stream()
                .collect(Collectors.toMap(UserLearningPath::getPathKey, Function.identity(), (a, b) -> a));
    }

    private static LearningPathSummary summaryOf(Course c, CourseProgress p, UserLearningPath started) {
        String nextTitle = p.nextSeq() == null ? null : c.steps().get(p.nextSeq() - 1).title();
        return new LearningPathSummary(c.key().toString(), c.key().kind().name(), c.title(), c.description(),
                c.steps().size(), p.total(), p.attempted(), p.solved(), p.nextSeq(), nextTitle,
                started != null, started == null ? null : started.getLastStudiedAt());
    }
}
