package project.study.study_project.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.TestDomains;
import project.study.study_project.TestFixtures;
import project.study.study_project.admin.audit.AdminAuditLog;
import project.study.study_project.admin.audit.AdminAuditLogRepository;
import project.study.study_project.admin.dto.AdminDocumentRequest;
import project.study.study_project.admin.dto.AdminProblemDetail;
import project.study.study_project.admin.dto.AdminProblemRequest;
import project.study.study_project.admin.revision.RevisionItem;
import project.study.study_project.admin.service.AdminDocumentService;
import project.study.study_project.admin.service.AdminProblemService;
import project.study.study_project.admin.service.AdminUsageService;
import project.study.study_project.admin.dto.AdminUsageDay;
import project.study.study_project.document.service.DocumentService;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 관리 쪽 셋 — 처리 기록(V35), 수정 이력(V36), 이용 추이. */
@SpringBootTest(properties = {"ratelimit.enabled=false", "admin.audit.enabled=true"})
@AutoConfigureMockMvc
@Transactional
class AdminAuditAndRevisionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private AdminAuditLogRepository auditLogRepository;
    @Autowired
    private AdminProblemService adminProblemService;
    @Autowired
    private AdminDocumentService adminDocumentService;
    @Autowired
    private DocumentService documentService;
    @Autowired
    private AdminUsageService adminUsageService;

    /* ── 처리 기록 ── */

    @Test
    @DisplayName("관리 API의 쓰기 요청이 성공하면 누가 무엇을 했는지 남는다")
    void successfulAdminWriteIsLogged() throws Exception {
        User admin = fixtures.userWithoutNickname(Role.ADMIN);
        Problem problem = fixtures.problem();
        long before = auditLogRepository.count();

        mockMvc.perform(post("/api/admin/problems/" + problem.getId() + "/hide")
                        .header("Authorization", fixtures.bearer(admin)))
                .andExpect(status().isOk());

        assertThat(auditLogRepository.count()).isEqualTo(before + 1);
        AdminAuditLog log = latestLog();
        assertThat(log.getActorId()).isEqualTo(admin.getId());
        assertThat(log.getActorUsername()).isEqualTo(admin.getUsername());
        assertThat(log.getMethod()).isEqualTo("POST");
        assertThat(log.getPattern()).isEqualTo("/api/admin/problems/{id}/hide");
        assertThat(log.getPath()).isEqualTo("/api/admin/problems/" + problem.getId() + "/hide");
    }

    @Test
    @DisplayName("관리자의 조회는 남지 않는다")
    void adminReadsAreNotLogged() throws Exception {
        long before = auditLogRepository.count();

        mockMvc.perform(get("/api/admin/problems").header("Authorization", fixtures.bearer(Role.ADMIN)))
                .andExpect(status().isOk());

        assertThat(auditLogRepository.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("실패한 쓰기 요청은 응답 상태와 함께 남는다")
    void failedAdminWriteIsLoggedWithStatus() throws Exception {
        long before = auditLogRepository.count();

        mockMvc.perform(post("/api/admin/problems/-1/hide").header("Authorization", fixtures.bearer(Role.ADMIN)))
                .andExpect(status().isNotFound());

        assertThat(auditLogRepository.count()).isEqualTo(before + 1);
        assertThat(latestLog().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("관리자가 아닌 사람이 관리 API를 부르면 조회든 쓰기든 403으로 남는다")
    void deniedAccessIsLogged() throws Exception {
        User user = fixtures.user(Role.USER);
        long before = auditLogRepository.count();

        mockMvc.perform(post("/api/admin/problems/1/hide").header("Authorization", fixtures.bearer(user)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/users").header("Authorization", fixtures.bearer(user)))
                .andExpect(status().isForbidden());

        assertThat(auditLogRepository.count()).isEqualTo(before + 2);
        AdminAuditLog log = latestLog();
        assertThat(log.getActorId()).isEqualTo(user.getId());
        assertThat(log.getMethod()).isEqualTo("GET");
        assertThat(log.getPath()).isEqualTo("/api/admin/users");
        assertThat(log.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("토큰 없이 관리 API를 부른 요청은 남지 않는다 — 401은 누구인지 적을 것이 없다")
    void anonymousAccessIsNotLogged() throws Exception {
        long before = auditLogRepository.count();

        mockMvc.perform(get("/api/admin/users")).andExpect(status().isUnauthorized());

        assertThat(auditLogRepository.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("처리 기록 목록은 이름표가 붙은 한 일을 최신순으로 준다")
    void auditListShowsLabel() throws Exception {
        String admin = fixtures.bearer(Role.ADMIN);
        Problem problem = fixtures.problem();
        mockMvc.perform(post("/api/admin/problems/" + problem.getId() + "/hide").header("Authorization", admin))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/audit-logs").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].action").value("문제 내리기"))
                .andExpect(jsonPath("$.data.content[0].path")
                        .value("/api/admin/problems/" + problem.getId() + "/hide"));
    }

    @Test
    @DisplayName("등록 기록에는 새로 만든 번호가 남고, 등록이 아닌 기록에는 남지 않는다")
    void createLogCarriesNewId() throws Exception {
        String admin = fixtures.bearer(Role.ADMIN);
        String body = """
                {"domain":"NETWORK","difficulty":"BEGINNER","type":"OX","title":"등록 기록 테스트",
                 "question":"TCP는 연결 지향이다.","answer":"O","explanation":"해설","choices":[],"documentSlug":null}
                """;

        String response = mockMvc.perform(post("/api/admin/problems").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long createdId = Long.valueOf(response.replaceAll("(?s).*?\"id\":(\\d+).*", "$1"));

        assertThat(latestLog().getCreatedId()).isEqualTo(createdId);

        mockMvc.perform(post("/api/admin/problems/" + createdId + "/hide").header("Authorization", admin))
                .andExpect(status().isOk());

        assertThat(latestLog().getCreatedId()).isNull();
        mockMvc.perform(get("/api/admin/audit-logs").header("Authorization", admin))
                .andExpect(jsonPath("$.data.content[1].action").value("문제 등록"))
                .andExpect(jsonPath("$.data.content[1].createdId").value(createdId));
    }

    /* ── 수정 이력 ── */

    @Test
    @DisplayName("문제를 고치면 고치기 전 모습이 남고, 그 모습으로 되돌릴 수 있다")
    void problemRevisionCanBeRestored() {
        Long id = adminProblemService.create(mcRequest("옛 제목", "옛 지문입니다.", "옛 정답")).id();

        adminProblemService.update(id, mcRequest("새 제목", "새 지문입니다.", "새 정답"));

        List<RevisionItem> revisions = adminProblemService.revisions(id);
        assertThat(revisions).hasSize(1);
        assertThat(revisions.get(0).title()).isEqualTo("옛 제목");

        AdminProblemDetail restored = adminProblemService.restore(id, revisions.get(0).id());

        assertThat(restored.title()).isEqualTo("옛 제목");
        assertThat(restored.question()).isEqualTo("옛 지문입니다.");
        assertThat(restored.choices()).filteredOn(AdminProblemDetail.ChoiceDetail::correct)
                .extracting(AdminProblemDetail.ChoiceDetail::text).containsExactly("옛 정답");
        // 되돌린 것도 수정이라, 되돌리기 전의 "새 제목"이 이력 맨 위에 남는다
        assertThat(adminProblemService.revisions(id)).extracting(RevisionItem::title)
                .containsExactly("새 제목", "옛 제목");
    }

    @Test
    @DisplayName("다른 문제의 이력으로는 되돌릴 수 없다")
    void revisionOfAnotherProblemIsRejected() {
        Long a = adminProblemService.create(mcRequest("가", "가 지문입니다.", "가 정답")).id();
        Long b = adminProblemService.create(mcRequest("나", "나 지문입니다.", "나 정답")).id();
        adminProblemService.update(a, mcRequest("가2", "가 지문 둘입니다.", "가 정답"));
        Long revisionOfA = adminProblemService.revisions(a).get(0).id();

        assertThatThrownBy(() -> adminProblemService.restore(b, revisionOfA))
                .isInstanceOf(BusinessException.class);
        assertThat(adminProblemService.getProblem(b).title()).isEqualTo("나");
    }

    @Test
    @DisplayName("문서를 고치면 옛 본문이 남고, 되돌리면 독자에게도 옛 본문이 나온다")
    void documentRevisionCanBeRestored() {
        String slug = "revision-test-" + UUID.randomUUID().toString().substring(0, 8);
        Long id = adminDocumentService.create(docRequest(slug, "옛 본문이다.")).id();
        adminDocumentService.update(id, docRequest(slug, "새 본문이다."));
        assertThat(documentService.getDocument(slug).contentMd()).isEqualTo("새 본문이다.");

        Long revisionId = adminDocumentService.revisions(id).get(0).id();
        adminDocumentService.restore(id, revisionId);

        assertThat(documentService.getDocument(slug).contentMd())
                .as("되돌리기도 캐시를 비워야 한다 — 안 비우면 독자는 10분 동안 되돌리기 전 본문을 읽는다")
                .isEqualTo("옛 본문이다.");
    }

    /* ── 이용 추이 ── */

    @Test
    @DisplayName("이용 추이는 날 수만큼 오고, 오늘 가입과 제출이 오늘 칸에 잡힌다")
    void usageTrendCountsToday() {
        AdminUsageDay before = today(adminUsageService.trend(7));
        Problem problem = fixtures.problem();
        fixtures.solver(problem);   // 가입 1 + 제출 1

        List<AdminUsageDay> days = adminUsageService.trend(7);

        assertThat(days).hasSize(7);
        AdminUsageDay after = today(days);
        assertThat(after.date()).isEqualTo(LocalDate.now());
        assertThat(after.signups()).isEqualTo(before.signups() + 1);
        assertThat(after.submissions()).isEqualTo(before.submissions() + 1);
        assertThat(after.activeUsers()).isEqualTo(before.activeUsers() + 1);
    }

    /* ── 도우미 ── */

    private AdminUsageDay today(List<AdminUsageDay> days) {
        return days.get(days.size() - 1);
    }

    private AdminAuditLog latestLog() {
        return auditLogRepository.findAll().stream()
                .max((x, y) -> Long.compare(x.getId(), y.getId())).orElseThrow();
    }

    private AdminProblemRequest mcRequest(String title, String question, String correct) {
        return new AdminProblemRequest(TestDomains.NETWORK, Difficulty.BEGINNER, ProblemType.MULTIPLE_CHOICE,
                title, question, null, "해설",
                List.of(new AdminProblemRequest.ChoiceItem(correct, true),
                        new AdminProblemRequest.ChoiceItem("오답", false, "틀린 이유")),
                null);
    }

    private AdminDocumentRequest docRequest(String slug, String contentMd) {
        return new AdminDocumentRequest(TestDomains.DATABASE, "수정 이력 테스트 문서", slug, contentMd,
                null, List.of("revision"));
    }
}
