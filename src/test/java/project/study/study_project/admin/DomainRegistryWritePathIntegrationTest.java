package project.study.study_project.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.TestDomains;
import project.study.study_project.auth.jwt.JwtTokenProvider;
import project.study.study_project.document.domain.Document;
import project.study.study_project.document.repository.DocumentRepository;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.llm.domain.TopicQueueItem;
import project.study.study_project.llm.repository.TopicQueueItemRepository;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;

import java.util.Set;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 쓰기 경로 8곳이 "등록되지 않은 분야"를 <b>500이 아니라 400(DOMAIN_003)</b>으로 돌려주는지 —
 * 5번 작업.
 *
 * <p><b>왜 컨트롤러를 거치는 MockMvc 테스트인가.</b> 서비스 메서드를 직접 호출하면 예외가
 * 던져졌다는 사실만 보인다 — 그 예외가 실제로 400으로 나가는지, 아니면
 * {@code GlobalExceptionHandler}가 못 잡아 500으로 새는지는 <b>스프링 배선을 거쳐야만</b> 보인다
 * (task-5-brief). "NOPE_X"는 {@code DomainCode} 형식(대문자로 시작하는 대문자·숫자·밑줄)은
 * 지키지만 {@code domain_setting}에는 없는 코드다 — enum이 있던 시절이라면 컴파일조차
 * 안 됐을 값이 지금은 HTTP 요청으로 여기까지 온다는 것이 이 테스트가 재현하는 상황이다.
 *
 * <p>요청 제한은 끈다 — 한 클래스에서 여러 번 호출하면 분당 상한에 걸려 429가 섞인다
 * ({@code AdminLlmUploadGenerateIntegrationTest}와 같은 판단).
 */
@SpringBootTest(properties = "ratelimit.enabled=false")
@AutoConfigureMockMvc
@Transactional
class DomainRegistryWritePathIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private ProblemRepository problemRepository;
    @Autowired
    private DocumentRepository documentRepository;
    @Autowired
    private TopicQueueItemRepository topicQueueItemRepository;

    /* ── 1) AdminProblemService.create ──────────────────────────── */

    @Test
    @DisplayName("문제 등록 — 등록되지 않은 분야는 400(DOMAIN_003)")
    void createProblemRejectsUnregisteredDomain() throws Exception {
        mockMvc.perform(post("/api/admin/problems")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"domain":"NOPE_X","difficulty":"BEGINNER","type":"OX","question":"q","answer":"O"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("DOMAIN_003")));
    }

    /* ── 2) AdminProblemService.update ──────────────────────────── */

    @Test
    @DisplayName("문제 수정 — 등록되지 않은 분야는 400(DOMAIN_003)")
    void updateProblemRejectsUnregisteredDomain() throws Exception {
        Problem problem = problemRepository.save(Problem.create(
                TestDomains.NETWORK, Difficulty.BEGINNER, ProblemType.OX, null, "질문", "O", null, null));

        mockMvc.perform(put("/api/admin/problems/" + problem.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"domain":"NOPE_X","difficulty":"BEGINNER","type":"OX","question":"질문2","answer":"X"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("DOMAIN_003")));
    }

    /* ── 3) AdminDocumentService.create ─────────────────────────── */

    @Test
    @DisplayName("문서 등록 — 등록되지 않은 분야는 400(DOMAIN_003)")
    void createDocumentRejectsUnregisteredDomain() throws Exception {
        mockMvc.perform(post("/api/admin/documents")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"domain":"NOPE_X","title":"제목","slug":"domain-fk-test-create","contentMd":"본문"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("DOMAIN_003")));
    }

    /* ── 4) AdminDocumentService.update ─────────────────────────── */

    @Test
    @DisplayName("문서 수정 — 등록되지 않은 분야는 400(DOMAIN_003)")
    void updateDocumentRejectsUnregisteredDomain() throws Exception {
        Document document = documentRepository.save(Document.create(
                TestDomains.NETWORK, "제목", "domain-fk-test-update", "본문", null, Set.of()));

        mockMvc.perform(put("/api/admin/documents/" + document.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"domain":"NOPE_X","title":"제목2","slug":"domain-fk-test-update","contentMd":"본문2"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("DOMAIN_003")));
    }

    /* ── 5) TopicQueueService.add ───────────────────────────────── */

    @Test
    @DisplayName("주제 범위 추가 — 등록되지 않은 분야는 400(DOMAIN_003)")
    void addTopicQueueRejectsUnregisteredDomain() throws Exception {
        mockMvc.perform(post("/api/admin/topic-queue")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"domain":"NOPE_X","topic":"주제"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("DOMAIN_003")));
    }

    /* ── 6) TopicQueueService.update ────────────────────────────── */

    @Test
    @DisplayName("주제 범위 수정 — 등록되지 않은 분야는 400(DOMAIN_003)")
    void updateTopicQueueRejectsUnregisteredDomain() throws Exception {
        TopicQueueItem item = topicQueueItemRepository.save(
                TopicQueueItem.fresh(TestDomains.NETWORK, "기존 주제", null, 0));

        mockMvc.perform(patch("/api/admin/topic-queue/" + item.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"domain":"NOPE_X","topic":"주제2"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("DOMAIN_003")));
    }

    /* ── 7) LlmProblemService.generate ──────────────────────────── */

    @Test
    @DisplayName("즉시 생성 — 등록되지 않은 분야는 400(DOMAIN_003), Claude를 부르지 않는다")
    void generateRejectsUnregisteredDomain() throws Exception {
        mockMvc.perform(post("/api/admin/llm-problems/generate")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"domain":"NOPE_X","difficulty":"BEGINNER","type":"MULTIPLE_CHOICE","count":1}"""))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("DOMAIN_003")));
    }

    /* ── 8) LlmProblemService.generateFromDocument ──────────────── */

    @Test
    @DisplayName("문서 기반 생성 — 등록되지 않은 분야는 400(DOMAIN_003), Claude를 부르지 않는다")
    void generateFromDocumentRejectsUnregisteredDomain() throws Exception {
        mockMvc.perform(multipart("/api/admin/llm-problems/generate-from-document")
                        .param("domain", "NOPE_X")
                        .param("difficulty", "BEGINNER")
                        .param("type", "MULTIPLE_CHOICE")
                        .param("count", "1")
                        .param("text", "# 제목\n\n본문")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("DOMAIN_003")));
    }

    /* ── 도우미 ──────────────────────────────────────────────── */

    private static final org.springframework.http.MediaType APPLICATION_JSON =
            org.springframework.http.MediaType.APPLICATION_JSON;

    private String bearer() {
        User admin = userRepository.save(User.builder()
                .username("domainfk" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash(passwordEncoder.encode("admin-pw1"))
                .role(Role.ADMIN)
                .build());
        return "Bearer " + jwtTokenProvider.createToken(admin.getId(), Role.ADMIN);
    }
}
