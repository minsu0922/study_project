package project.study.study_project.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.auth.jwt.JwtTokenProvider;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.report.domain.ReportStatus;
import project.study.study_project.report.repository.ProblemReportRepository;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 제보 기능의 <b>경계</b>를 실제 요청으로 확인한다 — 누가 부를 수 있고, 무엇이 막히는가.
 *
 * <p>규칙 자체는 {@code ProblemReportServiceTest}가 가짜 저장소로 본다. 여기서만 볼 수 있는 것은
 * 셋이다:
 * <ul>
 *   <li>SecurityConfig가 이 경로들을 의도대로 가르는가(제보는 로그인, 제보함은 ADMIN)
 *   <li>DB의 UNIQUE 제약이 실제로 걸리는가 — 서비스의 사전 검사를 지우면 이 테스트만 남아 잡는다
 *   <li>enum·검증 애너테이션이 JSON 바인딩에서 제대로 작동하는가
 * </ul>
 *
 * <p>MySQL이 필요하다(다른 통합 테스트와 같은 전제). 요청 제한은 끈다 — 한 테스트가 같은
 * 경로를 여러 번 두드려 429에 걸리면 검증하려던 것과 무관한 실패가 난다.
 */
@SpringBootTest(properties = "ratelimit.enabled=false")
@AutoConfigureMockMvc
@Transactional
class ProblemReportIntegrationTest {

    private static final String PATH = "/api/me/problem-reports";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ProblemRepository problemRepository;
    @Autowired
    private ProblemReportRepository reportRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("비로그인은 제보할 수 없다 — /api/me/** 규칙에 걸린다")
    void requiresLogin() throws Exception {
        Long problemId = saveProblem().getId();

        mockMvc.perform(post(PATH).contentType("application/json")
                        .content(body(problemId, "TYPO", null)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("로그인하면 접수된다 — 201, PENDING으로 시작")
    void acceptsReport() throws Exception {
        Long problemId = saveProblem().getId();

        mockMvc.perform(post(PATH).header("Authorization", bearer(Role.USER))
                        .contentType("application/json")
                        .content(body(problemId, "WRONG_ANSWER", "3번도 맞는 것 같습니다")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.detail").value("3번도 맞는 것 같습니다"))
                // 사유 문구를 서버가 함께 준다 — 화면이 코드-문구 표를 따로 갖지 않게
                .andExpect(jsonPath("$.data.reasonLabel").exists());
    }

    /**
     * 이 테스트의 값어치는 <b>DB 제약을 실제로 밟는다</b>는 데 있다. 서비스의 사전 검사만
     * 있으면 통과하는 것이 아니라, 그 검사를 지워도 여기서 잡힌다.
     */
    @Test
    @DisplayName("같은 사람이 같은 문제를 두 번 제보하면 409 REPORT_001")
    void rejectsDuplicate() throws Exception {
        Long problemId = saveProblem().getId();
        String token = bearer(Role.USER);

        mockMvc.perform(post(PATH).header("Authorization", token)
                        .contentType("application/json").content(body(problemId, "TYPO", null)))
                .andExpect(status().isCreated());

        mockMvc.perform(post(PATH).header("Authorization", token)
                        .contentType("application/json").content(body(problemId, "AMBIGUOUS", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REPORT_001"));
    }

    /** 막히는 것은 "같은 사람 + 같은 문제"뿐이다. 다른 사람의 같은 문제 제보는 오히려 신호가 겹쳐 세진다. */
    @Test
    @DisplayName("다른 사람은 같은 문제를 제보할 수 있다")
    void allowsDifferentReporters() throws Exception {
        Long problemId = saveProblem().getId();

        mockMvc.perform(post(PATH).header("Authorization", bearer(Role.USER))
                        .contentType("application/json").content(body(problemId, "TYPO", null)))
                .andExpect(status().isCreated());
        mockMvc.perform(post(PATH).header("Authorization", bearer(Role.USER))
                        .contentType("application/json").content(body(problemId, "TYPO", null)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("사유가 없으면 400 — 되먹임에 못 쓰는 제보가 쌓이지 않게")
    void requiresReason() throws Exception {
        Long problemId = saveProblem().getId();

        mockMvc.perform(post(PATH).header("Authorization", bearer(Role.USER))
                        .contentType("application/json")
                        .content("{\"problemId\":%d}".formatted(problemId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("없는 문제를 제보하면 404 QUIZ_001")
    void rejectsUnknownProblem() throws Exception {
        mockMvc.perform(post(PATH).header("Authorization", bearer(Role.USER))
                        .contentType("application/json").content(body(999_999L, "TYPO", null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("QUIZ_001"));
    }

    /* ── 제보함(관리자) ───────────────────────────────────── */

    @Test
    @DisplayName("제보함은 관리자만 — 일반 사용자는 403")
    void reportBoxIsAdminOnly() throws Exception {
        mockMvc.perform(get("/api/admin/reports"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/reports").header("Authorization", bearer(Role.USER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/reports").header("Authorization", bearer(Role.ADMIN)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("인정하면 상태와 처리 시각이 남고, 대기 건수에서 빠진다")
    void acceptMovesOutOfPending() throws Exception {
        Long problemId = saveProblem().getId();
        String admin = bearer(Role.ADMIN);

        String created = mockMvc.perform(post(PATH).header("Authorization", bearer(Role.USER))
                        .contentType("application/json").content(body(problemId, "WRONG_ANSWER", null)))
                .andReturn().getResponse().getContentAsString();
        long reportId = idOf(created);

        assertThat(reportRepository.countByStatus(ReportStatus.PENDING)).isEqualTo(1);

        mockMvc.perform(post("/api/admin/reports/%d/accept".formatted(reportId))
                        .header("Authorization", admin)
                        .contentType("application/json").content("{\"note\":\"맞는 지적\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.adminNote").value("맞는 지적"))
                .andExpect(jsonPath("$.data.resolvedAt").exists());

        assertThat(reportRepository.countByStatus(ReportStatus.PENDING)).isZero();
    }

    @Test
    @DisplayName("이미 처리한 제보를 또 처리하면 409 REPORT_003")
    void rejectsDoubleResolve() throws Exception {
        Long problemId = saveProblem().getId();
        String admin = bearer(Role.ADMIN);

        String created = mockMvc.perform(post(PATH).header("Authorization", bearer(Role.USER))
                        .contentType("application/json").content(body(problemId, "TYPO", null)))
                .andReturn().getResponse().getContentAsString();
        long reportId = idOf(created);

        mockMvc.perform(post("/api/admin/reports/%d/dismiss".formatted(reportId))
                .header("Authorization", admin)).andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/reports/%d/accept".formatted(reportId))
                        .header("Authorization", admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REPORT_003"));
    }

    /* ── 재료 ─────────────────────────────────────────────── */

    private String body(Long problemId, String reason, String detail) {
        return detail == null
                ? "{\"problemId\":%d,\"reason\":\"%s\"}".formatted(problemId, reason)
                : "{\"problemId\":%d,\"reason\":\"%s\",\"detail\":\"%s\"}".formatted(problemId, reason, detail);
    }

    /** 응답 봉투에서 data.id만 꺼낸다 — 한 값을 위해 ObjectMapper를 끌어오지 않는다. */
    private long idOf(String responseBody) {
        int at = responseBody.indexOf("\"id\":");
        int end = responseBody.indexOf(',', at);
        return Long.parseLong(responseBody.substring(at + 5, end).trim());
    }

    private String bearer(Role role) {
        User user = userRepository.save(User.builder()
                // 아이디는 30자 제한이라 UUID 앞 8자만 딴다(다른 통합 테스트와 같은 방식)
                .username("rep" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash(passwordEncoder.encode("password123"))
                .role(role)
                .build());
        return "Bearer " + jwtTokenProvider.createToken(user.getId(), role);
    }

    private Problem saveProblem() {
        return problemRepository.save(Problem.create(
                Domain.NETWORK, Difficulty.BEGINNER, ProblemType.OX,
                "TCP 3-way handshake",
                "TCP 연결은 3번의 패킷 교환으로 시작한다.", "O", "SYN → SYN+ACK → ACK", null));
    }
}
