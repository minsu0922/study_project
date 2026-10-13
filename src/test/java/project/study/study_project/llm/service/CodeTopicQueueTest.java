package project.study.study_project.llm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import project.study.study_project.TestDomains;
import project.study.study_project.domainsetting.support.DefaultDomains;
import project.study.study_project.llm.domain.TopicKind;
import project.study.study_project.llm.domain.TopicQueueItem;
import project.study.study_project.llm.dto.AdminTopicQueueRequest;
import project.study.study_project.llm.dto.TopicQueueFile;
import project.study.study_project.llm.repository.TopicQueueItemRepository;
import project.study.study_project.llm.support.TopicQueue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 코드 문제 주제(V40)가 개념 문서 주제와 섞이지 않는지 본다.
 * 섞이면 자바 문법 주제로 개념 문서가 생성되거나, 문서 주제로 코드 문제 배치가 돈다.
 */
@ExtendWith(MockitoExtension.class)
class CodeTopicQueueTest {

    @Mock
    private TopicQueueItemRepository repository;
    @Mock
    private ApplicationEventPublisher events;
    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private TopicQueueService service() {
        return new TopicQueueService(repository, events, DefaultDomains.catalog());
    }

    private TopicQueueItem codeTopic(Long id, String topic, int sortOrder) {
        TopicQueueItem item = TopicQueueItem.fresh(TopicKind.CODE, TestDomains.LANGUAGE_RUNTIME, topic, null, sortOrder);
        ReflectionTestUtils.setField(item, "id", id);
        return item;
    }

    private TopicQueueItem captureSaved() {
        ArgumentCaptor<TopicQueueItem> captor = ArgumentCaptor.forClass(TopicQueueItem.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("종류를 적어 추가하면 그 종류로 저장하고, 비우면 개념 문서 주제다")
    void addKeepsRequestedKind() {
        when(repository.existsByDomainAndTopic(any(), any())).thenReturn(false);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service().add(new AdminTopicQueueRequest(TestDomains.LANGUAGE_RUNTIME, "==과 equals", null, TopicKind.CODE));

        assertThat(captureSaved().getKind()).isEqualTo(TopicKind.CODE);
        assertThat(TopicQueueItem.fresh(TestDomains.OS, "메모리", null, 1).getKind()).isEqualTo(TopicKind.CONCEPT);
    }

    @Test
    @DisplayName("목록과 다음 차례는 종류 안에서만 계산한다 — 개념 주제 조회가 코드 주제를 읽지 않는다")
    void listsOnlyRequestedKind() {
        when(repository.findAllByKindOrderBySortOrderAsc(TopicKind.CODE))
                .thenReturn(List.of(codeTopic(1L, "==과 equals", 5), codeTopic(2L, "초기화 순서", 9)));

        var items = service().getAll(TopicKind.CODE);

        assertThat(items).extracting(i -> i.order()).as("순번은 종류 안에서 1부터").containsExactly(1, 2);
        assertThat(items.get(0).next()).isTrue();
        assertThat(items.get(0).kind()).isEqualTo(TopicKind.CODE);
        verify(repository, never()).findAllByKindOrderBySortOrderAsc(TopicKind.CONCEPT);
    }

    @Test
    @DisplayName("코드 주제 파일에 손으로 적은 줄은 코드 주제로 들여온다")
    void adoptsHandWrittenLineAsCodeTopic() {
        when(repository.findMaxSortOrder()).thenReturn(3);
        when(repository.existsByDomainAndTopic(any(), any())).thenReturn(false);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service().syncFrom(new TopicQueueFile(null, List.of(
                new TopicQueueFile.Entry(null, "LANGUAGE_RUNTIME", "try-finally와 return", null, null, null))),
                TopicKind.CODE);

        assertThat(captureSaved().getKind()).isEqualTo(TopicKind.CODE);
    }

    @Test
    @DisplayName("코드 주제는 _code-topics.json으로 따로 나가고, 하나도 없으면 파일을 만들지 않는다")
    void exportsCodeTopicsToTheirOwnFile() throws Exception {
        CodeTopicQueueExporter exporter = new CodeTopicQueueExporter(repository, objectMapper);
        when(repository.findAllByKindOrderBySortOrderAsc(TopicKind.CODE)).thenReturn(List.of());
        assertThat(exporter.export(tempDir)).isFalse();
        assertThat(Files.exists(tempDir.resolve(TopicQueue.CODE_FILE_NAME))).isFalse();

        when(repository.findAllByKindOrderBySortOrderAsc(TopicKind.CODE))
                .thenReturn(List.of(codeTopic(7L, "==과 equals", 1)));
        assertThat(exporter.export(tempDir)).isTrue();

        TopicQueueFile written = objectMapper.readValue(
                tempDir.resolve(TopicQueue.CODE_FILE_NAME).toFile(), TopicQueueFile.class);
        assertThat(written.topics()).singleElement().satisfies(e -> {
            assertThat(e.id()).isEqualTo(7L);
            assertThat(e.topic()).isEqualTo("==과 equals");
        });
        assertThat(Files.exists(tempDir.resolve(TopicQueue.FILE_NAME))).as("개념 주제 파일은 건드리지 않는다").isFalse();
    }

    @Test
    @DisplayName("개념 주제 내보내기는 개념 주제만 읽는다")
    void conceptExporterReadsOnlyConceptTopics() throws Exception {
        when(repository.findAllByKindOrderBySortOrderAsc(TopicKind.CONCEPT)).thenReturn(List.of());

        new TopicQueueExporter(repository, objectMapper).export(tempDir);

        verify(repository, never()).findAllByKindOrderBySortOrderAsc(TopicKind.CODE);
    }
}
