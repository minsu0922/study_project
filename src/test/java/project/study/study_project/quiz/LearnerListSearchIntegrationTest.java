package project.study.study_project.quiz;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.TestDomains;
import project.study.study_project.bookmark.dto.BookmarkItem;
import project.study.study_project.bookmark.dto.BookmarkTarget;
import project.study.study_project.bookmark.service.BookmarkService;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.dto.QuizSubmitRequest;
import project.study.study_project.quiz.dto.SubmissionHistoryItem;
import project.study.study_project.quiz.dto.WrongAnswerItem;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.quiz.service.QuizService;
import project.study.study_project.quiz.service.StudyHistoryService;
import project.study.study_project.quiz.service.WrongAnswerService;
import project.study.study_project.review.dto.ReviewListItem;
import project.study.study_project.review.service.ReviewService;
import project.study.study_project.TestFixtures;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 학습 화면 목록의 검색어 — 풀이 기록·오답노트·복습·북마크가 문제 제목과 지문에서 찾고,
 * 분야를 함께 거른다. JPQL 조건이라 실제 DB에서 본다.
 */
@SpringBootTest
@Transactional
class LearnerListSearchIntegrationTest {

    private static final PageRequest PAGE = PageRequest.of(0, 20);

    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private ProblemRepository problemRepository;
    @Autowired
    private QuizService quizService;
    @Autowired
    private StudyHistoryService studyHistoryService;
    @Autowired
    private WrongAnswerService wrongAnswerService;
    @Autowired
    private ReviewService reviewService;
    @Autowired
    private BookmarkService bookmarkService;

    @Test
    @DisplayName("네 목록 모두 제목·지문으로 찾고, 분야를 함께 걸 수 있다")
    void searchesTitleAndQuestion() {
        String word = "Zq" + UUID.randomUUID().toString().substring(0, 8);
        Problem byTitle = problem(TestDomains.NETWORK, word + " 제목", "지문");
        Problem byQuestion = problem(TestDomains.SECURITY, null, "지문 안의 " + word.toLowerCase());
        Problem other = problem(TestDomains.NETWORK, "다른 제목", "다른 지문");
        User user = fixtures.user(Role.USER);
        for (Problem p : List.of(byTitle, byQuestion, other)) {
            quizService.submit(user.getId(), new QuizSubmitRequest(p.getId(), "X"));   // 정답은 O — 틀린다
            bookmarkService.add(user.getId(), BookmarkTarget.PROBLEM, p.getId());
        }
        String q = word.toUpperCase();

        assertThat(studyHistoryService.history(user.getId(), null, null, q, PAGE).content())
                .extracting(SubmissionHistoryItem::problemId)
                .containsExactlyInAnyOrder(byTitle.getId(), byQuestion.getId());
        assertThat(studyHistoryService.history(user.getId(), null, TestDomains.NETWORK, q, PAGE).content())
                .extracting(SubmissionHistoryItem::problemId).containsExactly(byTitle.getId());
        assertThat(wrongAnswerService.getWrongAnswers(user.getId(), null, q, PAGE).content())
                .extracting(WrongAnswerItem::problemId, WrongAnswerItem::title)
                .containsExactlyInAnyOrder(tuple(byTitle.getId(), word + " 제목"), tuple(byQuestion.getId(), null));
        assertThat(reviewService.getMyReviews(user.getId(), null, TestDomains.SECURITY, q, null, PAGE).content())
                .extracting(ReviewListItem::problemId).containsExactly(byQuestion.getId());
        assertThat(bookmarkService.list(user.getId(), BookmarkTarget.PROBLEM, q, PAGE).content())
                .extracting(BookmarkItem::targetId)
                .containsExactlyInAnyOrder(byTitle.getId(), byQuestion.getId());
    }

    private Problem problem(project.study.study_project.global.common.DomainCode domain, String title, String question) {
        return problemRepository.save(Problem.create(
                domain, Difficulty.BEGINNER, ProblemType.OX, title, question, "O", "해설", null));
    }
}
