package project.study.study_project.llm;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.auth.jwt.JwtTokenProvider;
import project.study.study_project.global.common.Domain;
import project.study.study_project.llm.client.GeneratedDocumentItem;
import project.study.study_project.llm.domain.GeneratedDocumentDraft;
import project.study.study_project.llm.service.LlmDocumentService;
import project.study.study_project.llm.support.DocumentDraftValidator;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 개념 문서 검수 흐름 통합 테스트 — docs/15.
 *
 * <p>단위 테스트({@code LlmDocumentServiceTest})가 서비스 안쪽 판단을 봤다면, 여기서는
 * <b>단위 테스트가 절대 볼 수 없는 것</b>을 본다: 실제 HTTP 요청이 SecurityConfig의
 * {@code hasRole(ADMIN)}을 통과하는지, 검증 결과가 JSON으로 제대로 실려 나가는지,
 * 그리고 무엇보다 <b>승인한 문서가 정말로 공개 API에 나타나는지</b>.
 *
 * <p>마지막 항목이 이 테스트를 쓴 이유다. 승인 로직이 아무리 옳아도 정식 문서 테이블까지
 * 실제로 닿지 않으면 기능은 없는 것과 같은데, 서비스 단위 테스트는
 * {@code AdminDocumentService}를 가짜로 두므로 그 구간이 통째로 비어 있다.
 *
 * <p>MySQL이 필요하다(다른 통합 테스트와 같은 전제). 클래스 {@code @Transactional}로
 * 테스트마다 롤백된다.
 */
@SpringBootTest(properties = "ratelimit.enabled=false")
@AutoConfigureMockMvc
@Transactional
class LlmDocumentFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private LlmDocumentService llmDocumentService;

    @Test
    @DisplayName("검수 대기 목록에 자동 검증 결과가 함께 실려 온다 — 화면이 스스로 판단하면 규칙이 두 벌이 된다")
    void listCarriesValidationResult() throws Exception {
        // 분량만 살짝 넘긴 문서 — 경고는 뜨지만 승인은 가능해야 한다.
        // 숫자를 직접 적지 않고 상수에 묶는다: 권장 분량을 올린 날 이 문서가 경고선 아래로
        // 내려가면 검증 결과가 0건이 되는데, 그건 "응답에 안 실렸다"와 구분되지 않는다.
        GeneratedDocumentDraft draft = saveDraft("캐시 전략", uniqueSlug(),
                validContent("캐시 전략", "가".repeat(DocumentDraftValidator.WARN_LENGTH + 1)));

        // 검수 대기 목록은 오래된 순이라 방금 만든 초안이 맨 뒤에 온다. 기존 초안이 몇 건
        // 쌓여 있을지 모르므로 넉넉히 받아 와서 우리 것을 직접 찾는다 —
        // JSON 경로 필터로 한 줄에 끝낼 수도 있지만, 못 찾았을 때 "null"만 나와 원인을 못 읽는다.
        String json = mockMvc.perform(get("/api/admin/llm-documents?status=PENDING&size=100")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> mine = findById(json, draft.getId());
        assertThat(mine).as("방금 만든 초안이 검수 대기 목록에 있어야 한다").isNotNull();
        assertThat(mine.get("blocked")).as("분량 초과는 경고일 뿐 — 승인을 막으면 안 된다").isEqualTo(false);
        assertThat(mine.get("contentLength")).as("분량 감각을 화면에서 바로 볼 수 있어야 한다")
                .isEqualTo(draft.getContentMd().length());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> checks = (List<Map<String, Object>>) mine.get("checks");
        // 개수가 아니라 <우리가 유발한 경고가 실려 있는가>를 본다. 검증기에 규칙이 늘 때마다
        // (2026-08-17에 형식 규칙 넷이 늘었다) 개수를 세는 단언은 이 테스트를 깨뜨리는데,
        // 여기서 확인하려는 것은 규칙의 가짓수가 아니라 "결과가 응답에 실려 오는가"다.
        assertThat(checks).as("검증 결과가 응답에 실려야 화면이 규칙을 따로 갖지 않는다")
                .anySatisfy(c -> {
                    assertThat(c.get("severity")).isEqualTo("WARNING");
                    assertThat((String) c.get("message")).contains("권장 분량");
                });
    }

    /** 응답 JSON에서 특정 id의 초안을 찾는다. 없으면 null. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> findById(String json, Long id) {
        List<Map<String, Object>> content = JsonPath.read(json, "$.data.content");
        return content.stream()
                .filter(d -> id.intValue() == ((Number) d.get("id")).intValue())
                .findFirst().orElse(null);
    }

    @Test
    @DisplayName("승인하면 정식 문서가 되어 공개 API에 나타난다 — 여기까지 닿아야 기능이 있는 것이다")
    void approvedDraftBecomesPublicDocument() throws Exception {
        String slug = uniqueSlug();
        GeneratedDocumentDraft draft = saveDraft("캐시 전략", slug, validContent("캐시 전략", "본문 내용"));

        mockMvc.perform(post("/api/admin/llm-documents/%d/approve".formatted(draft.getId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.slug").value(slug));

        // 공개 경로(비로그인)에서 조회된다 — 태그도 함께 만들어졌는지 확인한다
        mockMvc.perform(get("/api/documents/" + slug))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("캐시 전략"))
                .andExpect(jsonPath("$.data.tags").isNotEmpty());
    }

    @Test
    @DisplayName("차단 항목이 있는 초안은 승인이 409로 막히고, 무엇에 걸렸는지 메시지로 알려 준다")
    void blockedDraftCannotBeApproved() throws Exception {
        GeneratedDocumentDraft draft = saveDraft("엉망 문서", uniqueSlug(), "# 엉망 문서\n\n필수 절이 하나도 없다.");

        mockMvc.perform(post("/api/admin/llm-documents/%d/approve".formatted(draft.getId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("LLM_005"))
                .andExpect(jsonPath("$.error.message").value(
                        org.hamcrest.Matchers.containsString("## 왜 필요한가")));
    }

    /**
     * 같은 주제를 다시 뽑으면 slug도 같아지므로 <b>실제로 자주 생길 상황</b>이다.
     * slug 중복 검사를 여기 따로 두지 않고 {@code AdminDocumentService}에 맡긴 판단이
     * 실제로 통하는지 확인한다 — 통하지 않으면 500이 나거나 문서가 둘로 늘어난다.
     */
    @Test
    @DisplayName("이미 쓰이는 slug면 승인이 409(DOC_002)로 막힌다 — 같은 주제를 다시 뽑으면 흔히 생긴다")
    void duplicateSlugIsRejectedOnApprove() throws Exception {
        String slug = uniqueSlug();
        GeneratedDocumentDraft first = saveDraft("캐시 전략", slug, validContent("캐시 전략", "본문"));
        GeneratedDocumentDraft second = saveDraft("캐시 전략(재생성)", slug, validContent("캐시 전략(재생성)", "본문"));

        mockMvc.perform(post("/api/admin/llm-documents/%d/approve".formatted(first.getId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/admin/llm-documents/%d/approve".formatted(second.getId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DOC_002"));
    }

    @Test
    @DisplayName("관리자가 아니면 접근할 수 없다 — 검수 API는 /api/admin/** 규칙에 얹혀 있다")
    void requiresAdminRole() throws Exception {
        mockMvc.perform(get("/api/admin/llm-documents"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 복구 로직 자체는 단위 테스트가 보고 있다. 여기서 보는 것은 <b>경로가 실제로 연결됐는가</b>다 —
     * 서비스가 아무리 옳아도 {@code @PostMapping} 경로에 오타가 있으면 404가 나고, 그건
     * 단위 테스트가 절대 잡지 못한다.
     */
    @Test
    @DisplayName("거절한 문서를 API로 되돌리면 검수 대기 목록에 다시 나타난다")
    void restoreEndpointPutsDraftBackInPendingList() throws Exception {
        GeneratedDocumentDraft draft = saveDraft("캐시 전략", uniqueSlug(), validContent("캐시 전략", "본문"));
        String token = adminToken();

        mockMvc.perform(post("/api/admin/llm-documents/%d/reject".formatted(draft.getId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"실수로 거절\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/llm-documents/%d/restore".formatted(draft.getId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        String json = mockMvc.perform(get("/api/admin/llm-documents?status=PENDING&size=100")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(findById(json, draft.getId()))
                .as("되돌린 초안이 검수 대기 목록으로 돌아와야 한다").isNotNull();
    }

    /* ── 도우미 ──────────────────────────────────────────────── */

    private String adminToken() {
        User admin = userRepository.save(User.builder()
                .email("llmdoc-test-" + UUID.randomUUID() + "@test.local")
                .passwordHash(passwordEncoder.encode("admin-pw1"))
                .role(Role.ADMIN)
                .build());
        return jwtTokenProvider.createToken(admin.getId(), Role.ADMIN);
    }

    /**
     * 흡수 경로와 같은 입구로 초안을 만든다. 엔티티를 직접 저장하지 않는 이유는
     * 태그 JSON 형식 같은 세부가 실제와 어긋나 "테스트만 통과하는" 상태가 되는 것을 막기 위해서다.
     */
    private GeneratedDocumentDraft saveDraft(String title, String slug, String content) {
        return llmDocumentService.saveDraft(Domain.SYSTEM_DESIGN,
                new GeneratedDocumentItem(title, slug, content, List.of("cache", "performance")),
                "claude-opus-5");
    }

    /** slug는 document 테이블에서 UNIQUE라, 테스트끼리 겹치지 않게 매번 새로 만든다. */
    private String uniqueSlug() {
        return "llmdoc-test-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String validContent(String heading, String body) {
        return """
                # %s

                ## 무엇인가
                한 문장 정의.

                ## 왜 필요한가
                %s

                ## 실무에서는 이렇게 쓴다
                - 이런 상황에서 이렇게 쓴다.

                ## 언제 깨지는가
                - 깨지는 조건 하나.

                ## 면접에서 이렇게 물어본다
                **Q. 무엇인가?**
                답변 요점.

                ## 면접 한 줄 요약
                한 줄.
                """.formatted(heading, body);
    }
}
