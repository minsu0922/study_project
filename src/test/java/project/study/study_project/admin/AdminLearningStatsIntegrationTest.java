package project.study.study_project.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.TestDomains;
import project.study.study_project.TestFixtures;
import project.study.study_project.admin.dto.AdminProblemStatItem;
import project.study.study_project.admin.dto.AdminUsageSummary;
import project.study.study_project.admin.service.AdminProblemStatsService;
import project.study.study_project.admin.service.AdminProblemStatsService.Filter;
import project.study.study_project.admin.service.AdminProblemStatsService.RateBand;
import project.study.study_project.admin.service.AdminProblemStatsService.Sort;
import project.study.study_project.admin.service.AdminUsageService;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.global.response.PageResponse;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.domain.Submission;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.quiz.repository.SubmissionRepository;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 학습 현황 — 문제별 정답률 검색·정렬·쪽 나누기, 이용 추이 기간 요약. */
@SpringBootTest(properties = "ratelimit.enabled=false")
@AutoConfigureMockMvc
@Transactional
class AdminLearningStatsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private ProblemRepository problemRepository;
    @Autowired
    private SubmissionRepository submissionRepository;
    @Autowired
    private AdminProblemStatsService problemStatsService;
    @Autowired
    private AdminUsageService usageService;

    /** 테스트 DB에 다른 문제가 있어도 이번 문제만 고르도록 제목에 붙이는 표식. */
    private final String tag = "stat-" + UUID.randomUUID().toString().substring(0, 8);

    @Test
    @DisplayName("정답률 구간과 최소 제출 수로 거르고, 기록 없는 문제는 정답률 정렬에서 맨 아래다")
    void filtersByBandAndMinAttempts() {
        Problem low = problem("low", Difficulty.BEGINNER);      // 1/4 = 25%
        Problem mid = problem("mid", Difficulty.INTERMEDIATE);  // 1/2 = 50%
        Problem high = problem("high", Difficulty.ADVANCED);    // 1/1 = 100%
        Problem none = problem("none", Difficulty.BEGINNER);    // 제출 없음
        submit(low, 1, 3);
        submit(mid, 1, 1);
        submit(high, 1, 0);

        assertThat(titles(search(null, null, null, Sort.RATE_ASC)))
                .containsExactly(tag + " low", tag + " mid", tag + " high", tag + " none");
        assertThat(titles(search(null, null, null, Sort.RATE_DESC)))
                .containsExactly(tag + " high", tag + " mid", tag + " low", tag + " none");
        assertThat(titles(search(RateBand.LOW, null, null, Sort.RATE_ASC))).containsExactly(tag + " low");
        assertThat(titles(search(RateBand.MID, null, null, Sort.RATE_ASC))).containsExactly(tag + " mid");
        assertThat(titles(search(RateBand.HIGH, null, null, Sort.RATE_ASC))).containsExactly(tag + " high");
        assertThat(titles(search(RateBand.NO_DATA, null, null, Sort.RATE_ASC))).containsExactly(tag + " none");
        assertThat(titles(search(null, 2, null, Sort.ATTEMPTS_DESC)))
                .containsExactly(tag + " low", tag + " mid");
        assertThat(titles(search(null, null, Difficulty.BEGINNER, Sort.RATE_ASC)))
                .containsExactly(tag + " low", tag + " none");

        AdminProblemStatItem lowRow = search(RateBand.LOW, null, null, Sort.RATE_ASC).content().get(0);
        assertThat(lowRow.attempts()).isEqualTo(4);
        assertThat(lowRow.correctCount()).isEqualTo(1);
        assertThat(lowRow.correctRate()).isEqualTo(25);
        assertThat(search(RateBand.NO_DATA, null, null, Sort.RATE_ASC).content().get(0).correctRate()).isNull();
    }

    @Test
    @DisplayName("쪽을 나누면 전체 건수와 쪽 수가 맞고, 마지막 쪽 뒤엔 다음이 없다")
    void paginates() {
        for (int i = 0; i < 5; i++) {
            problem("p" + i, Difficulty.BEGINNER);
        }
        var filter = new Filter(tag, null, null, null, null, null, null, Sort.NEWEST);

        PageResponse<AdminProblemStatItem> first = problemStatsService.search(filter, 0, 2);
        PageResponse<AdminProblemStatItem> last = problemStatsService.search(filter, 2, 2);

        assertThat(first.totalElements()).isEqualTo(5);
        assertThat(first.totalPages()).isEqualTo(3);
        assertThat(first.hasNext()).isTrue();
        assertThat(titles(first)).containsExactly(tag + " p4", tag + " p3");
        assertThat(titles(last)).containsExactly(tag + " p0");
        assertThat(last.hasNext()).isFalse();
    }

    @Test
    @DisplayName("API는 검색어·공개 상태를 받아 쪽 응답으로 준다")
    void apiFiltersByKeywordAndHidden() throws Exception {
        Problem shown = problem("shown", Difficulty.BEGINNER);
        Problem hidden = problem("hidden", Difficulty.BEGINNER);
        hidden.hide();
        problemRepository.saveAndFlush(hidden);

        mockMvc.perform(get("/api/admin/problem-stats")
                        .param("q", tag).param("hidden", "true")
                        .header("Authorization", fixtures.bearer(Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].problemId").value(hidden.getId()))
                .andExpect(jsonPath("$.data.content[0].hidden").value(true));
        assertThat(shown.getId()).isNotNull();
    }

    @Test
    @DisplayName("기간 요약은 오늘 가입·제출을 이번 기간에 세고, 활동 회원은 중복 없이 센다")
    void usageSummaryCountsCurrentPeriod() {
        AdminUsageSummary before = usageService.summary(7);
        Problem problem = problem("usage", Difficulty.BEGINNER);
        User user = fixtures.user(Role.USER);
        submissionRepository.save(Submission.of(user.getId(), problem, "O", true));
        submissionRepository.save(Submission.of(user.getId(), problem, "X", false));
        submissionRepository.flush();

        AdminUsageSummary after = usageService.summary(7);

        assertThat(after.current().from()).isEqualTo(after.current().to().minusDays(6));
        assertThat(after.previous().to()).isEqualTo(after.current().from().minusDays(1));
        assertThat(after.current().signups()).isEqualTo(before.current().signups() + 1);
        assertThat(after.current().submissions()).isEqualTo(before.current().submissions() + 2);
        assertThat(after.current().correct()).isEqualTo(before.current().correct() + 1);
        assertThat(after.current().activeUsers()).isEqualTo(before.current().activeUsers() + 1);
        assertThat(after.previous()).isEqualTo(before.previous());
    }

    /* ── 도우미 ── */

    private PageResponse<AdminProblemStatItem> search(RateBand band, Integer minAttempts,
                                                      Difficulty difficulty, Sort sort) {
        return problemStatsService.search(
                new Filter(tag, null, difficulty, null, band, minAttempts, null, sort), 0, 20);
    }

    private static java.util.List<String> titles(PageResponse<AdminProblemStatItem> page) {
        return page.content().stream().map(AdminProblemStatItem::title).toList();
    }

    private Problem problem(String name, Difficulty difficulty) {
        // 집계는 JdbcClient라 JPA 쓰기 지연을 못 본다. 바로 내보낸다
        return problemRepository.saveAndFlush(Problem.create(TestDomains.NETWORK, difficulty, ProblemType.OX,
                tag + " " + name, "지문 " + name, "O", "해설", null));
    }

    private void submit(Problem problem, int correct, int wrong) {
        User user = fixtures.user(Role.USER);
        for (int i = 0; i < correct; i++) {
            submissionRepository.save(Submission.of(user.getId(), problem, "O", true));
        }
        for (int i = 0; i < wrong; i++) {
            submissionRepository.save(Submission.of(user.getId(), problem, "X", false));
        }
        submissionRepository.flush();
    }
}
