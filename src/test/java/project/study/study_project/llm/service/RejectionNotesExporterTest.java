package project.study.study_project.llm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import project.study.study_project.llm.client.RejectionNote;
import project.study.study_project.llm.dto.RejectionNotesFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 거절 사례 스냅샷 내보내기 테스트 — docs/14.
 *
 * <p>핵심은 <b>"내용이 같으면 다시 쓰지 않는다"</b>는 규칙이다. 이게 깨지면 앱을 켤 때마다
 * 파일이 바뀌어 git이 매번 변경으로 인식하고, 그러면 "커밋할 게 항상 있는" 상태가 되어
 * <b>정작 진짜 새 거절이 생긴 날을 알아볼 수 없다</b>. 이 기능의 사용성이 통째로 무너지는 지점이라
 * 테스트로 못 박는다.
 */
@ExtendWith(MockitoExtension.class)
class RejectionNotesExporterTest {

    @Mock
    private LlmProblemService llmProblemService;

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RejectionNotesExporter exporter;

    @BeforeEach
    void setUp() {
        exporter = new RejectionNotesExporter(llmProblemService, objectMapper);
    }

    @Test
    @DisplayName("거절 이력이 있으면 스냅샷 파일을 만든다")
    void writesSnapshotWhenNotesExist() throws Exception {
        when(llmProblemService.findRecentRejectionNotes()).thenReturn(List.of(
                new RejectionNote("다음 중 옳지 않은 것은?", "부정형 문제라 검수 비용이 크다")));

        boolean written = exporter.export(tempDir);

        assertThat(written).isTrue();
        Path file = tempDir.resolve(RejectionNotesExporter.FILE_NAME);
        assertThat(file).exists();

        RejectionNotesFile snapshot = objectMapper.readValue(file.toFile(), RejectionNotesFile.class);
        assertThat(snapshot.notes()).singleElement()
                .extracting(RejectionNote::reason).isEqualTo("부정형 문제라 검수 비용이 크다");
    }

    @Test
    @DisplayName("거절 이력이 없으면 파일을 만들지 않는다 — 빈 스냅샷은 프롬프트에 빈 블록만 남긴다")
    void doesNotWriteWhenNoNotes() throws Exception {
        when(llmProblemService.findRecentRejectionNotes()).thenReturn(List.of());

        boolean written = exporter.export(tempDir);

        assertThat(written).isFalse();
        assertThat(tempDir.resolve(RejectionNotesExporter.FILE_NAME)).doesNotExist();
    }

    @Test
    @DisplayName("내용이 같으면 다시 쓰지 않는다 — 앱을 켤 때마다 git이 변경으로 보면 안 된다")
    void doesNotRewriteWhenUnchanged() throws Exception {
        when(llmProblemService.findRecentRejectionNotes()).thenReturn(List.of(
                new RejectionNote("질문", "사유")));

        assertThat(exporter.export(tempDir)).as("처음에는 쓴다").isTrue();

        Path file = tempDir.resolve(RejectionNotesExporter.FILE_NAME);
        String firstContent = Files.readString(file);

        assertThat(exporter.export(tempDir)).as("두 번째는 쓰지 않는다").isFalse();
        assertThat(Files.readString(file)).as("파일 내용이 그대로여야 한다").isEqualTo(firstContent);
    }

    @Test
    @DisplayName("새 거절이 생기면 다시 쓴다")
    void rewritesWhenNotesChanged() throws Exception {
        when(llmProblemService.findRecentRejectionNotes()).thenReturn(List.of(
                new RejectionNote("질문1", "사유1")));
        exporter.export(tempDir);

        when(llmProblemService.findRecentRejectionNotes()).thenReturn(List.of(
                new RejectionNote("질문2", "사유2"),
                new RejectionNote("질문1", "사유1")));

        assertThat(exporter.export(tempDir)).isTrue();

        RejectionNotesFile snapshot = objectMapper.readValue(
                tempDir.resolve(RejectionNotesExporter.FILE_NAME).toFile(), RejectionNotesFile.class);
        assertThat(snapshot.notes()).hasSize(2);
    }

    @Test
    @DisplayName("기존 파일이 깨져 있으면 새로 쓴다 — 손으로 편집하다 깨뜨려도 복구된다")
    void rewritesWhenExistingFileIsBroken() throws Exception {
        Files.writeString(tempDir.resolve(RejectionNotesExporter.FILE_NAME), "{ 깨진 JSON");
        when(llmProblemService.findRecentRejectionNotes()).thenReturn(List.of(
                new RejectionNote("질문", "사유")));

        assertThat(exporter.export(tempDir)).isTrue();

        RejectionNotesFile snapshot = objectMapper.readValue(
                tempDir.resolve(RejectionNotesExporter.FILE_NAME).toFile(), RejectionNotesFile.class);
        assertThat(snapshot.notes()).hasSize(1);
    }
}
