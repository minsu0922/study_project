package project.study.study_project.quiz;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.TestFixtures;
import project.study.study_project.quiz.service.AdminProblemService;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.dto.QuizSubmitRequest;
import project.study.study_project.quiz.dto.StudyTrendDay;
import project.study.study_project.quiz.dto.SubmissionHistoryItem;
import project.study.study_project.quiz.service.QuizService;
import project.study.study_project.quiz.service.StudyHistoryService;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 학습 추이와 풀이 이력 — 안 푼 날이 0으로 채워지는지, 맞힌 것도 이력에 남는지. */
@SpringBootTest
@Transactional
class StudyHistoryIntegrationTest {

    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private QuizService quizService;
    @Autowired
    private StudyHistoryService studyHistoryService;
    @Autowired
    private AdminProblemService adminProblemService;

    @Test
    @DisplayName("추이는 요청한 날 수만큼 오고, 안 푼 날은 0이며 마지막이 오늘이다")
    void trendFillsEmptyDays() {
        Problem problem = fixtures.problem();   // 정답 "O"
        User user = fixtures.user(Role.USER);
        quizService.submit(user.getId(), new QuizSubmitRequest(problem.getId(), "O"));
        quizService.submit(user.getId(), new QuizSubmitRequest(problem.getId(), "X"));

        List<StudyTrendDay> days = studyHistoryService.trend(user.getId(), 7);

        assertThat(days).hasSize(7);
        StudyTrendDay today = days.get(6);
        assertThat(today.date()).isEqualTo(LocalDate.now());
        assertThat(today.attempts()).isEqualTo(2);
        assertThat(today.correct()).isEqualTo(1);
        assertThat(days.subList(0, 6)).allMatch(d -> d.attempts() == 0 && d.correct() == 0);
        assertThat(days.get(0).date()).isEqualTo(LocalDate.now().minusDays(6));
    }

    @Test
    @DisplayName("남의 제출은 내 추이에 섞이지 않는다")
    void trendIsPerUser() {
        Problem problem = fixtures.problem();
        User mine = fixtures.user(Role.USER);
        User other = fixtures.user(Role.USER);
        quizService.submit(other.getId(), new QuizSubmitRequest(problem.getId(), "O"));

        assertThat(studyHistoryService.trend(mine.getId(), 30))
                .hasSize(30)
                .allMatch(d -> d.attempts() == 0);
    }

    @Test
    @DisplayName("날 수는 1~90으로 맞춘다")
    void trendDaysAreClamped() {
        User user = fixtures.user(Role.USER);

        assertThat(studyHistoryService.trend(user.getId(), 0)).hasSize(1);
        assertThat(studyHistoryService.trend(user.getId(), 1000)).hasSize(90);
    }

    @Test
    @DisplayName("이력은 맞힌 것과 틀린 것을 최신순으로 주고, 결과로 거를 수 있다")
    void historyListsBothResults() {
        Problem problem = fixtures.problem();
        User user = fixtures.user(Role.USER);
        quizService.submit(user.getId(), new QuizSubmitRequest(problem.getId(), "X"));
        quizService.submit(user.getId(), new QuizSubmitRequest(problem.getId(), "O"));

        assertThat(history(user, null)).extracting(SubmissionHistoryItem::correct)
                .containsExactly(true, false);
        assertThat(history(user, true)).hasSize(1);
        assertThat(history(user, false)).hasSize(1);
        assertThat(history(user, null).get(0).title()).isEqualTo("TCP 3-way handshake");
    }

    @Test
    @DisplayName("내려 둔 문제의 제출은 이력에서 빠진다")
    void historySkipsHiddenProblems() {
        Problem problem = fixtures.problem();
        User user = fixtures.user(Role.USER);
        quizService.submit(user.getId(), new QuizSubmitRequest(problem.getId(), "O"));

        adminProblemService.setHidden(problem.getId(), true);

        assertThat(history(user, null)).isEmpty();
    }

    private List<SubmissionHistoryItem> history(User user, Boolean correct) {
        return studyHistoryService.history(user.getId(), correct, PageRequest.of(0, 20)).content();
    }
}
