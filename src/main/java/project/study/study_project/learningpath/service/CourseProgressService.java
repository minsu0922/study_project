package project.study.study_project.learningpath.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.learningpath.repository.LearningPathStatsRepository;
import project.study.study_project.learningpath.repository.LearningPathStatsRepository.KeyProgress;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 코스 진도 계산 — 쿼리는 코스마다 두 번(문제 수, 내 제출)이다. 단계 수와 상관없다.
 *
 * <p>단계 문제는 이렇게 정한다. 문서 단계는 그 문서로 만든 문제(problem.document_slug),
 * 코드 단계는 그 난이도의 자바 코드 문제. 숨긴 문제는 분모에서도 뺀다.
 */
@Service
@RequiredArgsConstructor
public class CourseProgressService {

    private final LearningPathStatsRepository statsRepository;

    /** @param userId 비로그인이면 null — 문제 수만 채우고 제출·맞힌 수는 0이다 */
    @Transactional(readOnly = true)
    public CourseProgress progressOf(Long userId, Course course) {
        List<String> slugs = course.steps().stream().filter(Course.Step::isDocument)
                .map(Course.Step::documentSlug).toList();
        boolean hasCode = course.steps().stream().anyMatch(s -> !s.isDocument());

        Map<String, Long> totalBySlug = slugs.isEmpty() ? Map.of()
                : statsRepository.countProblemsBySlug(slugs).stream()
                        .collect(Collectors.toMap(LearningPathStatsRepository.KeyCount::getKey,
                                LearningPathStatsRepository.KeyCount::getCnt));
        Map<String, KeyProgress> mineBySlug = userId == null || slugs.isEmpty() ? Map.of()
                : statsRepository.progressBySlug(userId, slugs).stream()
                        .collect(Collectors.toMap(KeyProgress::getKey, Function.identity()));

        Map<Difficulty, Long> totalByLevel = new EnumMap<>(Difficulty.class);
        Map<Difficulty, long[]> mineByLevel = new EnumMap<>(Difficulty.class);
        if (hasCode) {
            statsRepository.countCodeProblemsByDifficulty(CourseCatalog.CODE_LANGUAGE)
                    .forEach(r -> totalByLevel.put(r.getDifficulty(), r.getCnt()));
            if (userId != null) {
                statsRepository.codeProgressByDifficulty(userId, CourseCatalog.CODE_LANGUAGE)
                        .forEach(r -> mineByLevel.put(r.getDifficulty(), new long[]{r.getAttempted(), r.getSolved()}));
            }
        }

        List<CourseProgress.StepProgress> steps = new ArrayList<>();
        Integer next = null;
        for (Course.Step s : course.steps()) {
            long total, attempted, solved;
            if (s.isDocument()) {
                KeyProgress mine = mineBySlug.get(s.documentSlug());
                total = totalBySlug.getOrDefault(s.documentSlug(), 0L);
                attempted = mine == null ? 0 : mine.getAttempted();
                solved = mine == null ? 0 : mine.getSolved();
            } else {
                long[] mine = mineByLevel.get(s.difficulty());
                total = totalByLevel.getOrDefault(s.difficulty(), 0L);
                attempted = mine == null ? 0 : mine[0];
                solved = mine == null ? 0 : mine[1];
            }
            steps.add(new CourseProgress.StepProgress(s.seq(), total, attempted, solved));
            if (next == null && attempted < total) next = s.seq();
        }
        return new CourseProgress(steps, next);
    }
}
