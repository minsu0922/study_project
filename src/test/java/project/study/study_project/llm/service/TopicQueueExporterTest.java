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
import project.study.study_project.TestDomains;
import project.study.study_project.global.common.DomainCode;
import project.study.study_project.llm.domain.TopicQueueItem;
import project.study.study_project.llm.dto.TopicQueueFile;
import project.study.study_project.llm.repository.TopicQueueItemRepository;
import project.study.study_project.llm.support.TopicQueue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 주제 범위 내보내기 테스트.
 *
 * <p>다른 내보내기와 같은 규칙을 지킨다 — <b>내용이 같으면 다시 쓰지 않는다</b>. 이게 깨지면
 * 앱을 켤 때마다 파일이 바뀌어 "커밋할 게 항상 있는" 상태가 되고, 정작 범위를 실제로 넣은 날을
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
    @DisplayName("범위를 순서대로 내보낸다 — 배치가 그대로 읽어 다음 차례를 고른다")
    void exportsRangesInOrder() throws Exception {
        given(item(1L, TestDomains.BACKEND_FRAMEWORK, "Spring 트랜잭션", "메모", 1),
                item(2L, TestDomains.OS, "메모리 관리", null, 2));

        assertThat(exporter.export(tempDir)).isTrue();

        // 배치의 리더로 직접 읽어 본다 — 형식이 어긋나면 여기서 잡힌다
        TopicQueue queue = TopicQueue.read(tempDir);
        assertThat(queue.problems()).isEmpty();
        assertThat(queue.size()).isEqualTo(2);
        TopicQueue.Picked picked = queue.next();
        assertThat(picked.topic()).isEqualTo("Spring 트랜잭션");
        assertThat(picked.domain()).isEqualTo(TestDomains.BACKEND_FRAMEWORK);
    }

    /**
     * id가 없으면 배치가 적은 사용 기록을 어느 DB 행에 돌려줄지 알 수 없다. 그러면 그 범위는
     * 화면에서 영원히 "아직 안 씀"으로 남고, 매번 다음 차례로 걸린다.
     */
    @Test
    @DisplayName("각 줄에 DB id가 실린다 — 이게 없으면 사용 기록이 돌아올 길이 없다")
    void includesDatabaseId() throws Exception {
        given(item(42L, TestDomains.OS, "메모리 관리", null, 1));

        exporter.export(tempDir);

        assertThat(read().topics().get(0).id()).isEqualTo(42L);
    }

    /**
     * 범위는 순환하므로 이미 쓴 줄도 <b>전부</b> 싣는다. 사용일은 싣지 않는다. 배치가 _topic-usage.json에
     * 적고 두 파일을 합쳐 고르므로, 여기 실으면 배치가 쓸 때마다 이 파일이 바뀌어 "보낼 변경"이 매일 뜬다.
     */
    @Test
    @DisplayName("이미 쓴 범위도 내보내되 사용일·횟수는 싣지 않는다 — 사용 기록은 배치만 쓰는 파일에 있다")
    void exportsUsedRangesWithoutUsage() throws Exception {
        given(used(1L, TestDomains.OS, "메모리 관리", 1, LocalDate.of(2026, 8, 19), 3));

        exporter.export(tempDir);

        TopicQueueFile.Entry entry = read().topics().get(0);
        assertThat(entry.id()).isEqualTo(1L);
        assertThat(entry.lastUsedAt()).isNull();
        assertThat(entry.usedCount()).isNull();
    }

    @Test
    @DisplayName("아직 안 쓴 범위에는 기록 칸을 아예 넣지 않는다 — 고쳐도 소용없는 칸은 함정이다")
    void omitsEmptyUsageColumns() throws Exception {
        given(item(1L, TestDomains.OS, "메모리 관리", null, 1));

        exporter.export(tempDir);

        // 안내문(note)이 두 칸의 이름을 설명하고 있어 파일 전체 문자열로는 판정할 수 없다.
        // 줄(entry) 쪽에 값이 없다는 것이 확인하려는 바다.
        TopicQueueFile.Entry entry = read().topics().get(0);
        assertThat(entry.lastUsedAt()).isNull();
        assertThat(entry.usedCount()).isNull();
    }

    @Test
    @DisplayName("내용이 같으면 다시 쓰지 않는다 — 켤 때마다 파일이 바뀌면 진짜 변경을 못 알아본다")
    void doesNotRewriteWhenUnchanged() throws Exception {
        given(item(1L, TestDomains.OS, "메모리 관리", null, 1));

        assertThat(exporter.export(tempDir)).isTrue();
        assertThat(exporter.export(tempDir)).as("두 번째 호출은 아무것도 하지 않아야 한다").isFalse();
    }

    @Test
    @DisplayName("범위가 하나도 없고 파일도 없으면 만들지 않는다 — 커밋할 것도 없는 빈 파일은 혼란만 준다")
    void doesNotCreateEmptyFile() throws Exception {
        given();

        assertThat(exporter.export(tempDir)).isFalse();
        assertThat(Files.exists(tempDir.resolve(TopicQueue.FILE_NAME))).isFalse();
    }

    /* ── 테스트 재료 ─────────────────────────────────────────── */

    private void given(TopicQueueItem... items) {
        when(repository.findAllByKindOrderBySortOrderAsc(any())).thenReturn(List.of(items));
    }

    private TopicQueueFile read() throws Exception {
        return objectMapper.readValue(tempDir.resolve(TopicQueue.FILE_NAME).toFile(), TopicQueueFile.class);
    }

    private TopicQueueItem item(Long id, DomainCode domain, String topic, String memo, int sortOrder) {
        TopicQueueItem item = TopicQueueItem.fresh(domain, topic, memo, sortOrder);
        ReflectionTestUtils.setField(item, "id", id);
        // 내보낸 파일을 배치가 그대로 고를 수 있어야 하므로 글의 주제까지 채운다
        item.editTopicBoxes("입문 글", "심화 글");
        return item;
    }

    private TopicQueueItem used(Long id, DomainCode domain, String topic, int sortOrder,
                                LocalDate lastUsedAt, int usedCount) {
        TopicQueueItem item = item(id, domain, topic, null, sortOrder);
        item.recordUse(lastUsedAt, usedCount);
        return item;
    }
}
