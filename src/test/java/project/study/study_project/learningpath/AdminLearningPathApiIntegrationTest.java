package project.study.study_project.learningpath;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.TestDomains;
import project.study.study_project.TestFixtures;
import project.study.study_project.document.domain.Document;
import project.study.study_project.document.repository.DocumentRepository;
import project.study.study_project.user.domain.Role;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 관리자 코스 API — 등록·순서 바꾸기·공개 규칙·권한, 그리고 공개하면 사용자 목록에 뜨는지. */
@SpringBootTest(properties = "ratelimit.enabled=false")
@AutoConfigureMockMvc
@Transactional
class AdminLearningPathApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private DocumentRepository documentRepository;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("만들고, 순서를 바꾸고, 공개하면 사용자 로드맵에 추천 코스로 뜬다. 지우면 사라진다")
    void createReorderPublishDelete() throws Exception {
        String admin = fixtures.bearer(Role.ADMIN);
        Document a = doc(false), b = doc(false);

        long id = idOf(mockMvc.perform(post("/api/admin/learning-paths").header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content(body(List.of(a.getId(), b.getId()), false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.steps[0].documentId").value(a.getId()))
                .andReturn().getResponse().getContentAsString());
        String key = "custom:" + id;

        mockMvc.perform(get("/api/learning-paths")).andExpect(jsonPath("$.data[*].key", not(hasItem(key))));

        mockMvc.perform(put("/api/admin/learning-paths/" + id).header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content(body(List.of(b.getId(), a.getId()), true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.published").value(true))
                .andExpect(jsonPath("$.data.steps[0].documentId").value(b.getId()))
                .andExpect(jsonPath("$.data.steps[1].seq").value(2));

        mockMvc.perform(get("/api/learning-paths/" + key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.kind").value("CUSTOM"))
                .andExpect(jsonPath("$.data.steps[0].documentSlug").value(b.getSlug()));

        mockMvc.perform(delete("/api/admin/learning-paths/" + id).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/learning-paths/" + key)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("같은 문서 두 번·없는 문서는 PATH_002, 내린 문서뿐인 코스의 공개는 PATH_003")
    void rejectsBadSteps() throws Exception {
        String admin = fixtures.bearer(Role.ADMIN);
        Document a = doc(false), hidden = doc(true);

        mockMvc.perform(post("/api/admin/learning-paths").header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content(body(List.of(a.getId(), a.getId()), false)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("PATH_002"));
        mockMvc.perform(post("/api/admin/learning-paths").header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content(body(List.of(Long.MAX_VALUE), false)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("PATH_002"));
        mockMvc.perform(post("/api/admin/learning-paths").header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content(body(List.of(hidden.getId()), true)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("PATH_003"));
    }

    @Test
    @DisplayName("문서 고르기 목록에는 내린 문서도 표시와 함께 나오고, 일반 사용자는 API를 못 쓴다")
    void documentOptionsAndPermission() throws Exception {
        Document hidden = doc(true);
        mockMvc.perform(get("/api/admin/learning-paths/documents")
                        .header(HttpHeaders.AUTHORIZATION, fixtures.bearer(Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == " + hidden.getId() + ")].hidden").value(true));
        mockMvc.perform(get("/api/admin/learning-paths").header(HttpHeaders.AUTHORIZATION, fixtures.bearer(Role.USER)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("분야 코스 순서를 저장하면 사용자 코스가 그 순서가 되고, 빈 목록으로 저장하면 기본 순서로 돌아간다")
    void domainOrderSaveAndReset() throws Exception {
        String admin = fixtures.bearer(Role.ADMIN);
        Document first = doc(false), second = doc(false);   // 기본 순서는 first → second(먼저 만든 순)

        mockMvc.perform(put("/api/admin/learning-paths/domain-order/" + TestDomains.NETWORK.value())
                        .header(HttpHeaders.AUTHORIZATION, admin).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("documentIds", List.of(second.getId())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(second.getId()))
                .andExpect(jsonPath("$.data[0].seq").value(1));

        // 순번을 받은 second가 코스 맨 앞, 순번 없는 문서는 그 뒤
        mockMvc.perform(get("/api/learning-paths/domain:" + TestDomains.NETWORK.value()))
                .andExpect(jsonPath("$.data.steps[0].documentSlug").value(second.getSlug()));

        mockMvc.perform(put("/api/admin/learning-paths/domain-order/" + TestDomains.NETWORK.value())
                        .header(HttpHeaders.AUTHORIZATION, admin).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("documentIds", List.of()))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/learning-paths/domain-order/" + TestDomains.NETWORK.value())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.data[?(@.id == " + second.getId() + ")].seq").value(contains(nullValue())));
        mockMvc.perform(get("/api/learning-paths/domain:" + TestDomains.NETWORK.value()))
                .andExpect(jsonPath("$.data.steps[*].documentSlug", hasItem(first.getSlug())));
    }

    @Test
    @DisplayName("다른 분야 문서·같은 문서 두 번은 PATH_002, 없는 분야는 DOMAIN_003")
    void domainOrderRejectsBadInput() throws Exception {
        String admin = fixtures.bearer(Role.ADMIN);
        Document a = doc(false);
        Document other = documentRepository.save(Document.create(TestDomains.SECURITY, "보안 " + UUID.randomUUID(),
                "sec-" + UUID.randomUUID().toString().substring(0, 8), "본문", null, Set.of()));
        String url = "/api/admin/learning-paths/domain-order/" + TestDomains.NETWORK.value();

        mockMvc.perform(put(url).header(HttpHeaders.AUTHORIZATION, admin).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("documentIds", List.of(other.getId())))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("PATH_002"));
        mockMvc.perform(put(url).header(HttpHeaders.AUTHORIZATION, admin).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("documentIds", List.of(a.getId(), a.getId())))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("PATH_002"));
        mockMvc.perform(get("/api/admin/learning-paths/domain-order/NO_SUCH_DOMAIN").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("DOMAIN_003"));
    }

    private String body(List<Long> ids, boolean published) throws Exception {
        return objectMapper.writeValueAsString(Map.of("title", "입문자 코스", "description", "처음부터 차례로",
                "sortOrder", 0, "documentIds", ids, "published", published));
    }

    private long idOf(String json) throws Exception {
        JsonNode node = objectMapper.readTree(json);
        return node.path("data").path("id").asLong();
    }

    private Document doc(boolean hidden) {
        String key = UUID.randomUUID().toString().substring(0, 8);
        Document d = Document.create(TestDomains.NETWORK, "문서 " + key, "doc-" + key, "본문", null, Set.of());
        if (hidden) d.hide();
        return documentRepository.save(d);
    }
}
