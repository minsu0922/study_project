package project.study.study_project.admin;

import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.TestDomains;
import project.study.study_project.TestFixtures;
import project.study.study_project.admin.dto.DocumentTopicBackfillResult;
import project.study.study_project.admin.service.DocumentTopicBackfillService;
import project.study.study_project.document.domain.Document;
import project.study.study_project.document.dto.AdminDocumentRequest;
import project.study.study_project.document.dto.DocumentDetailResponse;
import project.study.study_project.document.repository.DocumentRepository;
import project.study.study_project.document.service.AdminDocumentService;
import project.study.study_project.global.common.DomainCode;
import project.study.study_project.llm.domain.TopicQueueItem;
import project.study.study_project.llm.repository.TopicQueueItemRepository;
import project.study.study_project.user.domain.Role;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 이미 나간 문서를 사용 기록과 문서 파일로 주제에 잇는 일(백엔드 기술 지도)과 손으로 잇기. */
@SpringBootTest(properties = {"ratelimit.enabled=false", "llm.import.enabled=false"})
@AutoConfigureMockMvc
@Transactional
class DocumentTopicBackfillIntegrationTest {

    @TempDir
    static Path dir;

    @DynamicPropertySource
    static void importDir(DynamicPropertyRegistry registry) {
        registry.add("llm.import.dir", () -> dir.toString());
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private DocumentTopicBackfillService backfillService;
    @Autowired
    private AdminDocumentService adminDocumentService;
    @Autowired
    private DocumentRepository documentRepository;
    @Autowired
    private TopicQueueItemRepository topicRepository;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    @AfterEach
    void cleanFiles() throws IOException {
        Files.deleteIfExists(dir.resolve("_topic-usage.json"));
        Path docs = dir.resolve("documents");
        if (Files.exists(docs)) {
            try (Stream<Path> files = Files.walk(docs)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    @DisplayName("사용 날짜와 slug로 입문편·심화편을 모두 잇는다")
    void linksBothEditionsByDateAndSlug() throws IOException {
        TopicQueueItem topic = topic(TestDomains.NETWORK);
        writeUsage(usage(topic.getId(), "NETWORK", "2026-10-05"));
        writeDocumentFile("2026-10-05", "NETWORK", "bf-a-" + suffix, "bf-b-" + suffix);
        Document a = document("bf-a-" + suffix);
        Document b = document("bf-b-" + suffix);

        DocumentTopicBackfillResult result = backfillService.backfill();

        assertThat(result.linked()).isEqualTo(2);
        assertThat(result.unlinked()).isEmpty();
        assertThat(a.getTopicId()).isEqualTo(topic.getId());
        assertThat(b.getTopicId()).isEqualTo(topic.getId());
    }

    @Test
    @DisplayName("테이블에 없는 주제는 실패가 아니라 보고한다")
    void missingTopicIsReportedNotFailed() throws IOException {
        writeUsage(usage(999_999_001L, "NETWORK", "2026-10-05"));

        DocumentTopicBackfillResult result = backfillService.backfill();

        assertThat(result.linked()).isZero();
        assertThat(result.unlinked()).hasSize(1);
        assertThat(result.unlinked().get(0).reason()).isEqualTo("TOPIC_MISSING");
        assertThat(result.unlinked().get(0).topicId()).isEqualTo(999_999_001L);
    }

    @Test
    @DisplayName("파일 분야가 기록 분야와 다르면 잇지 않고 보고한다")
    void domainMismatchIsReported() throws IOException {
        TopicQueueItem topic = topic(TestDomains.NETWORK);
        writeUsage(usage(topic.getId(), "NETWORK", "2026-10-05"));
        writeDocumentFile("2026-10-05", "OS", "bf-a-" + suffix, null);
        Document a = document("bf-a-" + suffix);

        DocumentTopicBackfillResult result = backfillService.backfill();

        assertThat(result.linked()).isZero();
        assertThat(result.unlinked().get(0).reason()).isEqualTo("DOMAIN_MISMATCH");
        assertThat(a.getTopicId()).isNull();
    }

    @Test
    @DisplayName("파일이 없으면 FILE_MISSING, 파일의 slug로 문서가 없으면 DOCUMENT_MISSING")
    void missingFileOrSlugIsReported() throws IOException {
        TopicQueueItem noFile = topic(TestDomains.NETWORK);
        TopicQueueItem noDoc = topic(TestDomains.NETWORK);
        writeUsage(usage(noFile.getId(), "NETWORK", "2026-10-06"),
                usage(noDoc.getId(), "NETWORK", "2026-10-07"));
        writeDocumentFile("2026-10-07", "NETWORK", "bf-ghost-" + suffix, null);

        DocumentTopicBackfillResult result = backfillService.backfill();

        assertThat(result.linked()).isZero();
        assertThat(result.unlinked())
                .extracting(DocumentTopicBackfillResult.Unlinked::topicId, DocumentTopicBackfillResult.Unlinked::reason)
                .containsExactlyInAnyOrder(
                        Tuple.tuple(noFile.getId(), "FILE_MISSING"),
                        Tuple.tuple(noDoc.getId(), "DOCUMENT_MISSING"));
    }

    @Test
    @DisplayName("두 번 돌려도 오류 없이 같은 연결 상태다")
    void runningTwiceIsHarmless() throws IOException {
        TopicQueueItem topic = topic(TestDomains.NETWORK);
        writeUsage(usage(topic.getId(), "NETWORK", "2026-10-05"));
        writeDocumentFile("2026-10-05", "NETWORK", "bf-a-" + suffix, null);
        Document a = document("bf-a-" + suffix);

        backfillService.backfill();
        DocumentTopicBackfillResult second = backfillService.backfill();

        assertThat(second.linked()).isEqualTo(1);
        assertThat(a.getTopicId()).isEqualTo(topic.getId());
    }

    @Test
    @DisplayName("이미 다른 주제에 이은 문서는 덮지 않고 ALREADY_LINKED로 보고한다")
    void keepsExistingDifferentLink() throws IOException {
        TopicQueueItem topicA = topic(TestDomains.NETWORK);
        TopicQueueItem topicB = topic(TestDomains.NETWORK);
        writeUsage(usage(topicA.getId(), "NETWORK", "2026-10-05"));
        writeDocumentFile("2026-10-05", "NETWORK", "bf-a-" + suffix, null);
        Document a = document("bf-a-" + suffix);
        a.linkTopic(topicB.getId());

        DocumentTopicBackfillResult result = backfillService.backfill();

        assertThat(a.getTopicId()).isEqualTo(topicB.getId());
        assertThat(result.linked()).isZero();
        assertThat(result.unlinked())
                .extracting(DocumentTopicBackfillResult.Unlinked::topicId, DocumentTopicBackfillResult.Unlinked::reason)
                .containsExactly(Tuple.tuple(topicA.getId(), "ALREADY_LINKED"));
    }

    @Test
    @DisplayName("손으로 잇고 풀고, 없는 주제 id는 404")
    void manualLinkAndUnlink() throws Exception {
        TopicQueueItem topic = topic(TestDomains.NETWORK);
        Document a = document("bf-a-" + suffix);
        String admin = fixtures.bearer(Role.ADMIN);

        mockMvc.perform(put("/api/admin/documents/" + a.getId() + "/topic").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"topicId\":" + topic.getId() + "}"))
                .andExpect(status().isOk());
        assertThat(a.getTopicId()).isEqualTo(topic.getId());
        assertThat(adminDocumentService.get(a.getId()).topicId()).isEqualTo(topic.getId());

        mockMvc.perform(put("/api/admin/documents/" + a.getId() + "/topic").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"topicId\":null}"))
                .andExpect(status().isOk());
        assertThat(a.getTopicId()).isNull();

        mockMvc.perform(put("/api/admin/documents/" + a.getId() + "/topic").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"topicId\":999999002}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("백필 API는 관리자만 부를 수 있다")
    void backfillApi() throws Exception {
        mockMvc.perform(post("/api/admin/documents/topic-backfill").header("Authorization", fixtures.bearer(Role.ADMIN)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/admin/documents/topic-backfill").header("Authorization", fixtures.bearer(Role.USER)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("문서를 PUT으로 고쳐도 주제 연결은 그대로다")
    void documentPutKeepsTopicLink() {
        TopicQueueItem topic = topic(TestDomains.NETWORK);
        Document a = document("bf-a-" + suffix);
        a.linkTopic(topic.getId());

        DocumentDetailResponse updated = adminDocumentService.update(a.getId(),
                new AdminDocumentRequest(TestDomains.NETWORK, "고친 제목", "bf-a-" + suffix, "본문이다.", null, List.of("bf")));

        assertThat(updated.title()).isEqualTo("고친 제목");
        assertThat(documentRepository.findById(a.getId()).orElseThrow().getTopicId()).isEqualTo(topic.getId());
    }

    private TopicQueueItem topic(DomainCode domain) {
        return topicRepository.save(TopicQueueItem.fresh(domain, "백필 주제 " + UUID.randomUUID(), null, 9_999));
    }

    private Document document(String slug) {
        adminDocumentService.create(new AdminDocumentRequest(TestDomains.NETWORK, "백필 문서", slug, "본문이다.", null, List.of("bf")));
        return documentRepository.findBySlug(slug).orElseThrow();
    }

    private static String usage(Long id, String domain, String date) {
        return "{\"id\":" + id + ",\"domain\":\"" + domain + "\",\"topic\":\"t\",\"lastUsedAt\":\"" + date + "\",\"usedCount\":1}";
    }

    private void writeUsage(String... lines) throws IOException {
        Files.writeString(dir.resolve("_topic-usage.json"), "{\"usage\":[" + String.join(",", lines) + "]}");
    }

    private void writeDocumentFile(String date, String domain, String slug, String advancedSlug) throws IOException {
        Path docs = Files.createDirectories(dir.resolve("documents"));
        String advanced = advancedSlug == null ? "" : ",\"advancedDocument\":{\"title\":\"심화\",\"slug\":\"" + advancedSlug + "\"}";
        Files.writeString(docs.resolve(date + ".json"), "{\"date\":\"" + date + "\",\"domain\":\"" + domain
                + "\",\"document\":{\"title\":\"입문\",\"slug\":\"" + slug + "\"}" + advanced + "}");
    }
}
