package project.study.study_project.learningpath.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.TestDomains;
import project.study.study_project.TestFixtures;
import project.study.study_project.document.domain.Document;
import project.study.study_project.document.repository.DocumentRepository;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.learningpath.domain.LearningPath;
import project.study.study_project.learningpath.domain.PathKey;
import project.study.study_project.learningpath.repository.LearningPathRepository;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.dto.QuizSubmitRequest;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.quiz.service.QuizService;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** 코스 구성과 진도 — 실제 제출 경로(QuizService.submit)로 기록을 만들고 센다. */
@SpringBootTest
@Transactional
class CourseProgressIntegrationTest {

    @Autowired
    private CourseCatalog catalog;
    @Autowired
    private CourseProgressService progressService;
    @Autowired
    private LearningPathRepository pathRepository;
    @Autowired
    private DocumentRepository documentRepository;
    @Autowired
    private ProblemRepository problemRepository;
    @Autowired
    private QuizService quizService;
    @Autowired
    private TestFixtures fixtures;

    @Test
    @DisplayName("단계 진도는 문제 수·제출한 수·맞힌 수이고, 다음 추천은 안 푼 문제가 남은 첫 단계다")
    void countsProgressAndPicksNextStep() {
        Document first = doc(), second = doc();
        Problem a1 = problem(first.getSlug()), a2 = problem(first.getSlug());
        Problem b1 = problem(second.getSlug());
        Problem hidden = problem(second.getSlug());
        hidden.hide();
        LearningPath path = published(first, second);
        User me = fixtures.user(Role.USER);

        submit(me, a1, "O");   // 맞힘
        submit(me, a1, "X");   // 같은 문제를 다시 틀려도 "맞힌 적 있음"은 남는다
        submit(me, a2, "X");   // 틀림

        Course course = catalog.find(PathKey.custom(path.getId())).orElseThrow();
        CourseProgress progress = progressService.progressOf(me.getId(), course);

        assertThat(progress.steps())
                .extracting(CourseProgress.StepProgress::seq, CourseProgress.StepProgress::total,
                        CourseProgress.StepProgress::attempted, CourseProgress.StepProgress::solved)
                .containsExactly(tuple(1, 2L, 2L, 1L), tuple(2, 1L, 0L, 0L));   // 숨긴 문제는 분모에서 빠진다
        assertThat(progress.nextSeq()).as("1단계는 다 풀었으니 2단계를 권한다").isEqualTo(2);

        submit(me, b1, "O");
        assertThat(progressService.progressOf(me.getId(), course).nextSeq()).as("다 풀면 추천이 없다").isNull();
    }

    @Test
    @DisplayName("비로그인은 문제 수만 보이고 진도는 0이다 — 첫 단계를 권한다")
    void anonymousSeesTotalsOnly() {
        Document d = doc();
        problem(d.getSlug());
        Course course = catalog.find(PathKey.custom(published(d).getId())).orElseThrow();

        CourseProgress progress = progressService.progressOf(null, course);

        assertThat(progress.total()).isEqualTo(1);
        assertThat(progress.attempted()).isZero();
        assertThat(progress.nextSeq()).isEqualTo(1);
    }

    @Test
    @DisplayName("비공개 코스와 숨긴 문서의 단계는 사용자에게 보이지 않는다")
    void hidesUnpublishedAndHiddenDocuments() {
        Document shown = doc(), hiddenDoc = doc();
        hiddenDoc.hide();
        LearningPath draft = LearningPath.create("만드는 중", "설명", 0);
        draft.addSteps(List.of(shown, hiddenDoc));
        pathRepository.save(draft);

        assertThat(catalog.find(PathKey.custom(draft.getId()))).as("비공개").isEmpty();
        Course preview = catalog.find(PathKey.custom(draft.getId()), true).orElseThrow();
        assertThat(preview.steps()).extracting(Course.Step::documentSlug).containsExactly(shown.getSlug());
    }

    @Test
    @DisplayName("코드 코스는 난이도 단계이고, 내 코드 문제 풀이가 그 단계에 잡힌다")
    void codeCourseCountsByDifficulty() {
        Problem code = problem(null);
        code.attachCode("class Main { public static void main(String[] a) { System.out.println(1); } }", "java");
        problemRepository.flush();
        User me = fixtures.user(Role.USER);
        submit(me, code, "O");

        Course course = catalog.find(PathKey.code("java")).orElseThrow();
        assertThat(course.steps()).extracting(Course.Step::difficulty).contains(Difficulty.BEGINNER);
        CourseProgress progress = progressService.progressOf(me.getId(), course);
        int beginner = course.steps().stream().filter(s -> s.difficulty() == Difficulty.BEGINNER)
                .findFirst().orElseThrow().seq();
        assertThat(progress.steps().get(beginner - 1).solved()).isEqualTo(1);
    }

    @Test
    @DisplayName("분야 코스에는 그 분야 공개 문서가 들어 있고, 없는 분야·이상한 언어는 빈 값이다")
    void domainCourseAndUnknownKeys() {
        Document d = doc();
        Course network = catalog.find(PathKey.domain(TestDomains.NETWORK.value())).orElseThrow();
        assertThat(network.steps()).extracting(Course.Step::documentSlug).contains(d.getSlug());
        assertThat(catalog.all()).extracting(c -> c.key().toString()).contains("domain:NETWORK");

        assertThat(catalog.find(PathKey.domain("NO_SUCH_DOMAIN"))).isEmpty();
        assertThat(catalog.find(PathKey.code("cobol"))).isEmpty();
        assertThat(catalog.find(PathKey.custom(Long.MAX_VALUE / 2))).isEmpty();
    }

    private LearningPath published(Document... docs) {
        LearningPath p = LearningPath.create("코스", "설명", 0);
        p.addSteps(List.of(docs));
        p.publish();
        return pathRepository.save(p);
    }

    private void submit(User user, Problem p, String answer) {
        quizService.submit(user.getId(), new QuizSubmitRequest(p.getId(), answer));
    }

    private Problem problem(String slug) {
        return problemRepository.save(Problem.create(TestDomains.NETWORK, Difficulty.BEGINNER, ProblemType.OX,
                null, "지문 " + UUID.randomUUID(), "O", "해설", slug));
    }

    private Document doc() {
        String key = UUID.randomUUID().toString().substring(0, 8);
        return documentRepository.save(Document.create(TestDomains.NETWORK, "문서 " + key, "doc-" + key, "본문", null, Set.of()));
    }
}
