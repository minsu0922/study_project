package project.study.study_project.quiz;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.TestDomains;
import project.study.study_project.TestFixtures;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.dto.QuizSubmitRequest;
import project.study.study_project.quiz.dto.WrongAnswerItem;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.quiz.service.QuizService;
import project.study.study_project.quiz.service.WrongAnswerService;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 지정한 문제들의 오답 카드(GET /api/me/wrong-answers/of) — 복습 화면이 목록의 펼칠 내용을 받는 경로.
 * problemIds는 클라이언트가 준 값이라, 남의 오답이 새지 않는지를 함께 본다.
 */
@SpringBootTest
@Transactional
class WrongAnswersOfIntegrationTest {

    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private ProblemRepository problemRepository;
    @Autowired
    private QuizService quizService;
    @Autowired
    private WrongAnswerService wrongAnswerService;

    @Test
    @DisplayName("고른 문제의 최신 오답만 돌아온다 — 맞히기만 한 문제와 고르지 않은 문제는 빠진다")
    void returnsLatestWrongOfRequestedOnly() {
        User me = fixtures.user(Role.USER);
        Problem wrongTwice = problem("두 번 틀린 문제");
        Problem correctOnly = problem("맞히기만 한 문제");
        Problem notAsked = problem("고르지 않은 문제");
        submit(me, wrongTwice, "X");
        submit(me, wrongTwice, "X");
        submit(me, correctOnly, "O");
        submit(me, notAsked, "X");

        List<WrongAnswerItem> items = wrongAnswerService.getWrongAnswersOf(
                me.getId(), List.of(wrongTwice.getId(), correctOnly.getId()));

        assertThat(items).extracting(WrongAnswerItem::problemId).containsExactly(wrongTwice.getId());
        assertThat(items.get(0).myAnswer()).isEqualTo("X");
        assertThat(items.get(0).explanation()).isEqualTo("해설");
    }

    @Test
    @DisplayName("남의 오답 문제 id를 넣어도 남의 답은 새지 않는다")
    void doesNotLeakOtherUsersAnswers() {
        User me = fixtures.user(Role.USER);
        User stranger = fixtures.user(Role.USER);
        Problem p = problem("남이 틀린 문제");
        submit(stranger, p, "X");

        assertThat(wrongAnswerService.getWrongAnswersOf(me.getId(), List.of(p.getId()))).isEmpty();
    }

    @Test
    @DisplayName("빈 목록이면 쿼리 없이 빈 결과 — IN () 문법 오류를 만들지 않는다")
    void emptyIdsReturnEmpty() {
        User me = fixtures.user(Role.USER);
        assertThat(wrongAnswerService.getWrongAnswersOf(me.getId(), List.of())).isEmpty();
    }

    private void submit(User user, Problem p, String answer) {
        quizService.submit(user.getId(), new QuizSubmitRequest(p.getId(), answer));
    }

    private Problem problem(String question) {
        return problemRepository.save(Problem.create(
                TestDomains.NETWORK, Difficulty.BEGINNER, ProblemType.OX, null, question, "O", "해설", null));
    }
}
