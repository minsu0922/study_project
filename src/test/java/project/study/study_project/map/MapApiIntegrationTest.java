package project.study.study_project.map;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.TestDomains;
import project.study.study_project.TestFixtures;
import project.study.study_project.document.domain.Document;
import project.study.study_project.document.repository.DocumentRepository;
import project.study.study_project.domainsetting.support.DomainCatalog;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.DomainCode;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.llm.domain.TopicKind;
import project.study.study_project.llm.domain.TopicQueueItem;
import project.study.study_project.llm.dto.AdminDomainSettingRequest;
import project.study.study_project.llm.repository.TopicQueueItemRepository;
import project.study.study_project.llm.service.DomainSettingService;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.dto.QuizSubmitRequest;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.quiz.service.QuizService;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 지도 API를 HTTP로 — 공개 여부, 진도, 점 이름, 숨김·꺼진 분야·코드 주제 제외, 순서. */
@SpringBootTest(properties = "ratelimit.enabled=false")
@AutoConfigureMockMvc
@Transactional
class MapApiIntegrationTest {

    private static final String ADVANCED_BODY = "본문\n\n## 어떤 때 통하지 않는가\n\n내용";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private TopicQueueItemRepository topicRepository;
    @Autowired
    private DocumentRepository documentRepository;
    @Autowired
    private ProblemRepository problemRepository;
    @Autowired
    private QuizService quizService;
    @Autowired
    private DomainSettingService domainSettingService;
    @Autowired
    private DomainCatalog domainCatalog;

    @Test
    @DisplayName("비로그인도 주제와 문제 수를 보고, 진도는 0이다")
    void anonymousSeesTopicsWithZeroProgress() throws Exception {
        TopicQueueItem t = topic(TestDomains.NETWORK, "TCP 연결", 1000);
        Document d = doc(t, "입문 글", "본문");
        problem(d.getSlug());
        problem(d.getSlug());

        mockMvc.perform(get("/api/map")).andExpect(status().isOk())
                .andExpect(jsonPath(node(t, "problemTotal"), hasItem(2)))
                .andExpect(jsonPath(node(t, "attempted"), hasItem(0)))
                .andExpect(jsonPath(node(t, "solved"), hasItem(0)))
                .andExpect(jsonPath(node(t, "documents[0].slug"), hasItem(d.getSlug())))
                .andExpect(jsonPath(node(t, "documents[0].edition"), hasItem("BEGINNER")));
    }

    @Test
    @DisplayName("로그인하면 내 진도가 실린다")
    void loggedInSeesOwnProgress() throws Exception {
        TopicQueueItem t = topic(TestDomains.NETWORK, "TCP 연결", 1000);
        Document d = doc(t, "입문 글", "본문");
        Problem p = problem(d.getSlug());
        problem(d.getSlug());
        User me = fixtures.user(Role.USER);
        quizService.submit(me.getId(), new QuizSubmitRequest(p.getId(), "O"));

        mockMvc.perform(get("/api/map").header(HttpHeaders.AUTHORIZATION, fixtures.bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath(node(t, "problemTotal"), hasItem(2)))
                .andExpect(jsonPath(node(t, "attempted"), hasItem(1)))
                .andExpect(jsonPath(node(t, "solved"), hasItem(1)));
    }

    @Test
    @DisplayName("문서가 없는 주제도 나오고, 제목은 줄인 주제 문장이다")
    void topicWithoutDocumentsHasEmptyDocuments() throws Exception {
        TopicQueueItem t = topic(TestDomains.NETWORK, "TCP 흐름 제어 — 윈도우로 속도를 맞추는 방식", 1000);

        mockMvc.perform(get("/api/map")).andExpect(status().isOk())
                .andExpect(jsonPath(node(t, "documents.length()"), hasItem(0)))
                .andExpect(jsonPath(node(t, "title"), hasItem("TCP 흐름 제어")));
    }

    @Test
    @DisplayName("공백 없는 줄표로도 점 이름을 자른다")
    void shortenCutsAtDashWithoutSpaces() throws Exception {
        TopicQueueItem t = topic(TestDomains.NETWORK, "TCP 흐름 제어—윈도우로 속도를 맞추는 방식", 1000);

        mockMvc.perform(get("/api/map")).andExpect(status().isOk())
                .andExpect(jsonPath(node(t, "title"), hasItem("TCP 흐름 제어")));
    }

    @Test
    @DisplayName("구분자가 없어도 24자를 넘으면 24자와 줄임표로 줄인다")
    void shortenTruncatesLongTitle() throws Exception {
        String longText = "가".repeat(30);
        TopicQueueItem t = topic(TestDomains.NETWORK, longText, 1000);

        mockMvc.perform(get("/api/map")).andExpect(status().isOk())
                .andExpect(jsonPath(node(t, "title"), hasItem("가".repeat(24) + "…")));
    }

    @Test
    @DisplayName("점 이름은 입문편 제목이다. 심화편이 먼저 이어져 있어도 그렇다")
    void titleUsesBeginnerDocumentTitle() throws Exception {
        TopicQueueItem t = topic(TestDomains.NETWORK, "TCP 연결", 1000);
        doc(t, "심화 글", ADVANCED_BODY);
        doc(t, "입문 글", "본문");

        mockMvc.perform(get("/api/map")).andExpect(status().isOk())
                .andExpect(jsonPath(node(t, "title"), hasItem("입문 글")))
                .andExpect(jsonPath(node(t, "documents.length()"), hasItem(2)))
                .andExpect(jsonPath(node(t, "documents[0].edition"), hasItem("ADVANCED")));
    }

    @Test
    @DisplayName("문서가 여럿이면 문제 수를 모두 더한다")
    void topicWithManyDocumentsSumsProgress() throws Exception {
        TopicQueueItem t = topic(TestDomains.NETWORK, "TCP 연결", 1000);
        for (int i = 0; i < 3; i++) {
            problem(doc(t, "글 " + i, "본문").getSlug());
        }

        mockMvc.perform(get("/api/map")).andExpect(status().isOk())
                .andExpect(jsonPath(node(t, "documents.length()"), hasItem(3)))
                .andExpect(jsonPath(node(t, "problemTotal"), hasItem(3)));
    }

    @Test
    @DisplayName("숨긴 문서는 문서 목록과 문제 수에서 빠진다")
    void hiddenDocumentsAreLeftOut() throws Exception {
        TopicQueueItem t = topic(TestDomains.NETWORK, "TCP 연결", 1000);
        Document shown = doc(t, "보이는 글", "본문");
        Document hidden = doc(t, "숨긴 글", ADVANCED_BODY);
        problem(shown.getSlug());
        problem(hidden.getSlug());
        hidden.hide();
        documentRepository.saveAndFlush(hidden);

        mockMvc.perform(get("/api/map")).andExpect(status().isOk())
                .andExpect(jsonPath(node(t, "documents.length()"), hasItem(1)))
                .andExpect(jsonPath(node(t, "documents[0].slug"), hasItem(shown.getSlug())))
                .andExpect(jsonPath(node(t, "problemTotal"), hasItem(1)));
    }

    @Test
    @DisplayName("꺼진 분야의 주제는 지도에 없다")
    void disabledDomainIsLeftOut() throws Exception {
        TopicQueueItem on = topic(TestDomains.NETWORK, "켜진 분야 주제", 1000);
        TopicQueueItem off = topic(TestDomains.OS, "꺼진 분야 주제", 1001);
        domainSettingService.findAll().stream()
                .filter(s -> s.getDomain().equals(TestDomains.OS))
                .forEach(s -> domainSettingService.edit(TestDomains.OS,
                        new AdminDomainSettingRequest(false, s.getDisplayName(), s.getHint())));

        mockMvc.perform(get("/api/map")).andExpect(status().isOk())
                .andExpect(jsonPath(node(on, "id"), hasItem(on.getId().intValue())))
                .andExpect(jsonPath("$.data.domains[?(@.code=='OS')]").isEmpty())
                .andExpect(jsonPath(node(off, "id")).isEmpty());
    }

    @Test
    @DisplayName("코드 주제는 지도에 없다")
    void codeTopicsAreLeftOut() throws Exception {
        TopicQueueItem code = topicRepository.save(
                TopicQueueItem.fresh(TopicKind.CODE, TestDomains.NETWORK, "코드 주제", null, 1000));

        mockMvc.perform(get("/api/map")).andExpect(status().isOk())
                .andExpect(jsonPath(node(code, "id")).isEmpty());
    }

    @Test
    @DisplayName("분야는 등록부 순서, 주제는 sort_order 순서다")
    void ordersFollowRegistryAndSortOrder() throws Exception {
        List<DomainCode> registry = domainCatalog.enabled();
        DomainCode first = registry.get(0);
        DomainCode last = registry.get(registry.size() - 1);
        // 일부러 등록부 반대 순서로 저장하고, 같은 분야 안에서도 순번을 거꾸로 준다
        TopicQueueItem lastB = topic(last, "뒤 분야 둘째", 1003);
        TopicQueueItem lastA = topic(last, "뒤 분야 첫째", 1002);
        TopicQueueItem firstB = topic(first, "앞 분야 둘째", 1001);
        TopicQueueItem firstA = topic(first, "앞 분야 첫째", 1000);

        String body = mockMvc.perform(get("/api/map")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<String> codes = JsonPath.read(body, "$.data.domains[*].code");
        assertThat(codes.indexOf(first.value())).isLessThan(codes.indexOf(last.value()));
        List<Integer> ids = JsonPath.read(body, "$.data.domains[*].topics[*].id");
        assertThat(ids.indexOf(firstA.getId().intValue())).isLessThan(ids.indexOf(firstB.getId().intValue()));
        assertThat(ids.indexOf(lastA.getId().intValue())).isLessThan(ids.indexOf(lastB.getId().intValue()));
        assertThat(ids.indexOf(firstB.getId().intValue())).isLessThan(ids.indexOf(lastA.getId().intValue()));
    }

    private static String node(TopicQueueItem t, String field) {
        return "$.data.domains[*].topics[?(@.id==" + t.getId() + ")]." + field;
    }

    private TopicQueueItem topic(DomainCode domain, String text, int sortOrder) {
        return topicRepository.save(TopicQueueItem.fresh(domain, text, null, sortOrder));
    }

    private Document doc(TopicQueueItem topic, String title, String body) {
        String key = UUID.randomUUID().toString().substring(0, 8);
        Document d = Document.create(topic.getDomain(), title, "doc-" + key, body, null, Set.of());
        d.linkTopic(topic.getId());
        return documentRepository.saveAndFlush(d);
    }

    private Problem problem(String slug) {
        return problemRepository.save(Problem.create(TestDomains.NETWORK, Difficulty.BEGINNER, ProblemType.OX,
                null, "지문 " + UUID.randomUUID(), "O", "해설", slug));
    }
}
