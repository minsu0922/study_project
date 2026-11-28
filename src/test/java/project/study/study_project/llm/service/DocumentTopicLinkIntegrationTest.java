package project.study.study_project.llm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.TestDomains;
import project.study.study_project.document.domain.Document;
import project.study.study_project.document.dto.DocumentDetailResponse;
import project.study.study_project.document.repository.DocumentRepository;
import project.study.study_project.llm.domain.GeneratedDocumentDraft;
import project.study.study_project.llm.domain.TopicQueueItem;
import project.study.study_project.llm.dto.GeneratedDocumentFile;
import project.study.study_project.llm.repository.GeneratedDocumentDraftRepository;
import project.study.study_project.llm.repository.TopicQueueItemRepository;
import project.study.study_project.llm.support.FileFormats;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 주제 id가 초안에서 문서로 옮겨지고, 주제가 지워져도 문서가 남는지 — 승인과 외래키가 실제로 맞물리는지 본다. */
@SpringBootTest
@Transactional
class DocumentTopicLinkIntegrationTest {

    @Autowired
    private LlmDocumentService llmDocumentService;
    @Autowired
    private GeneratedDocumentDraftRepository draftRepository;
    @Autowired
    private DocumentRepository documentRepository;
    @Autowired
    private TopicQueueItemRepository topicRepository;
    @Autowired
    private EntityManager em;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("승인하면 초안의 주제 id가 문서로 옮겨진다")
    void approveMovesTopicIdToDocument() {
        Long topicId = savedTopic();
        GeneratedDocumentDraft draft = savedDraft();
        draft.recordTopic(topicId);

        DocumentDetailResponse created = llmDocumentService.approve(draft.getId());
        em.flush();
        em.clear();

        assertThat(documentRepository.findById(created.id()).orElseThrow().getTopicId()).isEqualTo(topicId);
    }

    @Test
    @DisplayName("승인할 때 주제가 이미 없으면 null로 두고 승인은 성공한다")
    void approveWithMissingTopicLeavesNull() {
        GeneratedDocumentDraft draft = savedDraft();
        draft.recordTopic(999_999L);

        DocumentDetailResponse created = llmDocumentService.approve(draft.getId());

        assertThat(documentRepository.findById(created.id()).orElseThrow().getTopicId()).isNull();
    }

    @Test
    @DisplayName("주제를 지워도 문서는 남고 주제 id만 null이 된다")
    void deletingTopicKeepsDocument() {
        Long topicId = savedTopic();
        Document doc = documentRepository.save(Document.create(TestDomains.SYSTEM_DESIGN, "연결 확인",
                "topic-link-" + UUID.randomUUID().toString().substring(0, 8), "본문", null, Set.of()));
        doc.linkTopic(topicId);
        em.flush();

        topicRepository.deleteById(topicId);
        em.flush();
        em.clear();

        Document reloaded = documentRepository.findById(doc.getId()).orElseThrow();
        assertThat(reloaded.getTopicId()).isNull();
    }

    @Test
    @DisplayName("topicId가 null이면 파일에 그 칸을 쓰지 않는다")
    void topicIdIsNotWrittenWhenNull() throws Exception {
        GeneratedDocumentFile file = new GeneratedDocumentFile("테스트", "2026-11-28", "2026-11-28T00:00:00Z",
                TestDomains.SYSTEM_DESIGN, "m", null, null);

        assertThat(FileFormats.toJson(objectMapper, FileFormats.Kind.DOCUMENTS, file)).doesNotContain("topicId");
        assertThat(FileFormats.toJson(objectMapper, FileFormats.Kind.DOCUMENTS, file.withTopicId(5L)))
                .contains("\"topicId\"");
    }

    private Long savedTopic() {
        return topicRepository.save(TopicQueueItem.fresh(TestDomains.SYSTEM_DESIGN,
                "연결 확인 주제 " + UUID.randomUUID(), null, 1)).getId();
    }

    private GeneratedDocumentDraft savedDraft() {
        String slug = "topic-link-" + UUID.randomUUID().toString().substring(0, 8);
        return draftRepository.save(GeneratedDocumentDraft.pending(TestDomains.SYSTEM_DESIGN, "연결 확인", slug,
                validContent("연결 확인"), "[]", "test-model"));
    }

    private String validContent(String heading) {
        return """
                # %s

                > **글의 주제**
                >
                > 확인용 글의 주제.

                ## 핵심 요약
                - **요점** — 결론 한 문장.

                ## 바탕이 되는 개념
                상위 개념을 처음부터 설명한다.

                ## 무엇인가
                한 문장 정의.

                ## 왜 필요한가
                내용.

                ## 용어 한눈에

                | 용어 | 한 줄 뜻 | 언제 쓰나 |
                |---|---|---|
                | 요점 | 한 줄 뜻. | 이럴 때. |

                ## 실무에서는 이렇게 쓴다
                - 이런 상황에서 이렇게 쓴다.

                ## 자주 하는 오해
                **"믿기 쉬운 문장이다"**
                그렇지 않다. 맞는 사실은 이것이다.

                ## 한 줄로 정리하면
                "한 줄."
                """.formatted(heading);
    }
}
