package project.study.study_project.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.auth.jwt.JwtTokenProvider;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 업로드 문서 기반 문제 생성 API 통합 테스트 — 2026-08-18 신설.
 *
 * <p><b>여기서 보는 것은 "요청이 서비스에 닿기 전에 걸러지는가"다.</b> 실제 생성은 Claude를
 * 부르므로(돈이 들고 결과가 매번 다르다) 검증할 수 없고, 생성 이후의 로직은
 * {@code LlmProblemServiceTest}가 가짜 생성기로 이미 본다. 그래서 이 테스트는 <b>거부 경로만</b>
 * 다룬다 — 전부 Claude 호출 전에 끝나므로 API 키 없이도 돈 한 푼 안 쓰고 돈다.
 *
 * <p><b>왜 굳이 통합 테스트인가.</b> 여기서 검증하는 셋은 전부 <b>스프링 배선</b>이라
 * 단위 테스트로는 하나도 못 본다.
 * <ol>
 *   <li>{@code multipart/form-data}의 폼 필드가 record DTO에 바인딩되는가 —
 *       {@code @ModelAttribute}가 record 생성자 바인딩을 못 하면 런타임에만 터진다
 *   <li>{@code @NotNull}(분야·난이도 필수)이 실제로 400으로 나가는가
 *   <li>{@code /api/admin/**}의 권한 규칙이 이 새 경로에도 걸리는가 —
 *       경로를 새로 만들 때 가장 놓치기 쉬운 자리다
 * </ol>
 *
 * <p>MySQL이 필요하다(다른 통합 테스트와 같은 전제). 클래스 {@code @Transactional}로 롤백된다.
 * 요청 제한은 끈다 — 한 클래스에서 여러 번 호출하면 분당 5회 상한에 걸려 429가 섞인다.
 */
@SpringBootTest(properties = "ratelimit.enabled=false")
@AutoConfigureMockMvc
@Transactional
class AdminLlmUploadGenerateIntegrationTest {

    private static final String URL = "/api/admin/llm-problems/generate-from-document";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    /**
     * 파일도 본문도 없으면 무엇으로 만들지 알 수 없다. 조용히 진행해서 빈 문서로 요금을 내는
     * 것이 최악이라 400으로 되돌린다.
     */
    @Test
    @DisplayName("파일도 붙여넣기도 없으면 400 — 빈 문서로 요금을 내면 안 된다")
    void rejectsWhenNeitherFileNorTextGiven() throws Exception {
        mockMvc.perform(withCell(multipart(URL)).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(
                        org.hamcrest.Matchers.containsString("올리거나")));
    }

    /**
     * 둘 다 오면 <b>골라 주지 않는다.</b> 조용히 하나를 고르면 "올린 파일이 무시된 줄 모르는"
     * 사고가 난다 — 결과 문제를 봐도 어느 쪽으로 만들었는지 알 수 없어 검수에서도 안 걸린다.
     */
    @Test
    @DisplayName("파일과 붙여넣기가 둘 다 오면 400 — 조용히 하나를 고르면 무시된 줄 모른다")
    void rejectsWhenBothFileAndTextGiven() throws Exception {
        mockMvc.perform(withCell(multipart(URL).file(mdFile("# 제목\n\n본문")))
                        .param("text", "붙여넣은 본문")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(
                        org.hamcrest.Matchers.containsString("하나만")));
    }

    /**
     * 분야를 비우면 400이어야 한다. 기존 즉시 생성은 비우면 "가장 부족한 칸"을 자동으로 고르는데,
     * 그 규칙이 여기 새어 들어오면 <b>올린 문서와 무관한 분야</b>가 붙는다.
     */
    @Test
    @DisplayName("분야를 비우면 400 — 업로드 생성은 칸을 자동으로 고르지 않는다")
    void rejectsWhenDomainMissing() throws Exception {
        mockMvc.perform(multipart(URL).file(mdFile("# 제목\n\n본문"))
                        .param("difficulty", "BEGINNER")
                        .param("type", "MULTIPLE_CHOICE")
                        .param("count", "3")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isBadRequest());
    }

    /** 상한을 넘긴 문서는 잘라서 진행하지 않고 거부한다 — 조용히 잘리면 아무도 모른다. */
    @Test
    @DisplayName("길이 상한을 넘긴 붙여넣기는 400 — 잘라서 진행하지 않는다")
    void rejectsTooLongText() throws Exception {
        mockMvc.perform(withCell(multipart(URL))
                        .param("text", "가".repeat(20_001))
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(
                        org.hamcrest.Matchers.containsString("너무 깁니다")));
    }

    /** 메모장이 저장한 CP949 파일 — 이 서비스에서 가장 흔한 업로드 사고다. */
    @Test
    @DisplayName("UTF-8이 아닌 파일은 400 — 깨진 문서로 문제를 만들면 검수에서도 못 잡는다")
    void rejectsNonUtf8File() throws Exception {
        byte[] cp949 = "# 트랜잭션 격리 수준\n\n본문이다.".getBytes(Charset.forName("x-windows-949"));

        mockMvc.perform(withCell(multipart(URL)
                        .file(new MockMultipartFile("file", "메모장.md", "text/markdown", cp949)))
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(
                        org.hamcrest.Matchers.containsString("UTF-8")));
    }

    /**
     * 경로를 새로 만들 때 가장 놓치기 쉬운 자리. {@code /api/admin/**} 규칙에 얹혀 있으므로
     * 컨트롤러에는 권한 코드가 없는데, 그래서 <b>규칙이 안 걸려도 컴파일은 된다</b>.
     */
    @Test
    @DisplayName("관리자가 아니면 401 — 새 경로가 /api/admin/** 규칙에 실제로 얹혔는지 본다")
    void requiresAdmin() throws Exception {
        mockMvc.perform(withCell(multipart(URL)).param("text", "본문"))
                .andExpect(status().isUnauthorized());
    }

    /* ── 도우미 ──────────────────────────────────────────────── */

    /** 분야·난이도·유형·개수를 채운다 — 이 넷은 거의 모든 케이스에서 같아 잡음만 된다. */
    private MockMultipartHttpServletRequestBuilder withCell(MockMultipartHttpServletRequestBuilder builder) {
        builder.param("domain", "NETWORK");
        builder.param("difficulty", "BEGINNER");
        builder.param("type", "MULTIPLE_CHOICE");
        builder.param("count", "3");
        return builder;
    }

    private MockMultipartFile mdFile(String content) {
        return new MockMultipartFile("file", "doc.md", "text/markdown", content.getBytes(StandardCharsets.UTF_8));
    }

    private String bearer() {
        User admin = userRepository.save(User.builder()
                .email("llmupload-test-" + UUID.randomUUID() + "@test.local")
                .passwordHash(passwordEncoder.encode("admin-pw1"))
                .role(Role.ADMIN)
                .build());
        return "Bearer " + jwtTokenProvider.createToken(admin.getId(), Role.ADMIN);
    }
}
