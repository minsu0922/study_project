package project.study.study_project.llm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import project.study.study_project.global.common.Domain;
import project.study.study_project.llm.domain.TopicQueueItem;
import project.study.study_project.llm.dto.TopicQueueFile;
import project.study.study_project.llm.repository.TopicQueueItemRepository;
import project.study.study_project.llm.support.TopicQueue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 주제 대기열 내보내기 테스트.
 *
 * <p>다른 내보내기와 같은 규칙을 지킨다 — <b>내용이 같으면 다시 쓰지 않는다</b>. 이게 깨지면
 * 앱을 켤 때마다 파일이 바뀌어 "커밋할 게 항상 있는" 상태가 되고, 정작 주제를 실제로 넣은 날을
 * 알아볼 수 없게 된다.
 *
 * <p>이 파일만의 관심사는 <b>배치가 그대로 읽을 수 있는가</b>다. 쓰는 쪽(여기)과 읽는 쪽
 * ({@link TopicQueue})이 형식을 각자 알고 있으므로, 실제로 왕복시켜 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class TopicQueueExporterTest {

    @Mock
    private TopicQueueItemRepository repository;

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private TopicQueueExporter exporter;

    @BeforeEach
    void setUp() {
        exporter = new TopicQueueExporter(repository, objectMapper);
    }

    @Test
    @DisplayName("대기 중인 주제를 순서대로 내보낸다 — 배치가 그대로 읽어 첫 줄을 꺼낸다")
    void exportsPendingTopicsInOrder() throws Exception {
        given(item(1L, Domain.BACKEND_FRAMEWORK, "@Transactional 전파 속성", "메모", 1),
                item(2L, Domain.OS, "컨텍스트 스위칭", null, 2));

        assertThat(exporter.export(tempDir)).isTrue();

        // 배치의 리더로 직접 읽어 본다 — 형식이 어긋나면 여기서 잡힌다
        TopicQueue queue = TopicQueue.read(tempDir);
        assertThat(queue.problems()).isEmpty();
        assertThat(queue.pendingCount()).isEqualTo(2);
        TopicQueue.Picked picked = queue.next();
        assertThat(picked.topic()).isEqualTo("@Transactional 전파 속성");
        assertThat(picked.domain()).isEqualTo(Domain.BACKEND_FRAMEWORK);
    }

    /**
     * id가 없으면 배치가 찍은 사용 표시를 어느 DB 행에 돌려줄지 알 수 없다. 그러면 그 주제는
     * 화면에서 영원히 "대기 중"으로 남고, 다음 내보내기가 다시 실어 보내 <b>같은 주제로 문서가
     * 반복해서</b> 나온다.
     */
    @Test
    @DisplayName("각 줄에 DB id가 실린다 — 이게 없으면 사용 표시가 돌아올 길이 없다")
    void includesDatabaseId() throws Exception {
        given(item(42L, Domain.OS, "컨텍스트 스위칭", null, 1));

        exporter.export(tempDir);

        assertThat(read().topics().get(0).id()).isEqualTo(42L);
    }

    @Test
    @DisplayName("이미 쓴 주제는 내보내지 않는다 — 파일의 목적은 '다음에 뭘 쓸까' 하나뿐이다")
    void excludesUsedTopics() throws Exception {
        // 리포지터리가 대기 중인 것만 돌려주므로 여기서는 빈 목록이 온다.
        // 그래도 파일은 (이미 있다면) 갱신되어야 한다 — 아래 테스트가 그 경우를 본다.
        given();
        Files.writeString(tempDir.resolve(TopicQueue.FILE_NAME),
                "{\"topics\":[{\"id\":1,\"domain\":\"OS\",\"topic\":\"페이지 폴트\",\"usedAt\":\"2026-08-19\"}]}");

        assertThat(exporter.export(tempDir)).isTrue();
        assertThat(read().topics()).isEmpty();
    }

    @Test
    @DisplayName("내용이 같으면 다시 쓰지 않는다 — 켤 때마다 파일이 바뀌면 진짜 변경을 못 알아본다")
    void doesNotRewriteWhenUnchanged() throws Exception {
        given(item(1L, Domain.OS, "컨텍스트 스위칭", null, 1));

        assertThat(exporter.export(tempDir)).isTrue();
        assertThat(exporter.export(tempDir)).as("두 번째 호출은 아무것도 하지 않아야 한다").isFalse();
    }

    @Test
    @DisplayName("주제가 하나도 없고 파일도 없으면 만들지 않는다 — 커밋할 것도 없는 빈 파일은 혼란만 준다")
    void doesNotCreateEmptyFile() throws Exception {
        given();

        assertThat(exporter.export(tempDir)).isFalse();
        assertThat(Files.exists(tempDir.resolve(TopicQueue.FILE_NAME))).isFalse();
    }

    /* ── 테스트 재료 ─────────────────────────────────────────── */

    private void given(TopicQueueItem... items) {
        when(repository.findByUsedAtIsNullOrderBySortOrderAsc()).thenReturn(List.of(items));
    }

    private TopicQueueFile read() throws Exception {
        return objectMapper.readValue(tempDir.resolve(TopicQueue.FILE_NAME).toFile(), TopicQueueFile.class);
    }

    private TopicQueueItem item(Long id, Domain domain, String topic, String memo, int sortOrder) {
        TopicQueueItem item = TopicQueueItem.pending(domain, topic, memo, sortOrder);
        ReflectionTestUtils.setField(item, "id", id);
        return item;
    }
}
