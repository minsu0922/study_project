package project.study.study_project.llm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import project.study.study_project.document.repository.DocumentRepository;
import project.study.study_project.llm.dto.ExistingDocumentsFile;
import project.study.study_project.llm.repository.GeneratedDocumentDraftRepository;
import project.study.study_project.tag.domain.Tag;
import project.study.study_project.tag.repository.TagRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 기존 문서 스냅샷 내보내기 테스트 — docs/15.
 *
 * <p>{@code RejectionNotesExporterTest}와 같은 규칙을 지킨다: <b>내용이 같으면 다시 쓰지 않는다</b>.
 * 이게 깨지면 앱을 켤 때마다 파일이 바뀌어 "커밋할 게 항상 있는" 상태가 되고, 정작 진짜
 * 새 문서가 생긴 날을 알아볼 수 없게 된다.
 *
 * <p>여기에 더해 이 클래스만의 관심사가 하나 있다 — <b>검수 대기 초안의 제목도 들어가는가</b>.
 * 빠지면 승인을 미루는 동안 배치가 대기함에 이미 있는 주제를 또 만든다.
 */
@ExtendWith(MockitoExtension.class)
class ExistingDocumentsExporterTest {

    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private GeneratedDocumentDraftRepository draftRepository;
    @Mock
    private TagRepository tagRepository;

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ExistingDocumentsExporter exporter;

    @BeforeEach
    void setUp() {
        exporter = new ExistingDocumentsExporter(
                documentRepository, draftRepository, tagRepository, objectMapper);
    }

    @Test
    @DisplayName("정식 문서와 검수 대기 초안의 제목이 함께 실린다 — 초안을 빼면 대기 중인 주제를 또 만든다")
    void includesPendingDraftTitles() throws Exception {
        given(List.of("OSI 7계층"), List.of("캐시 전략"), List.of("network", "cache"));

        assertThat(exporter.export(tempDir)).isTrue();

        ExistingDocumentsFile snapshot = read();
        assertThat(snapshot.titles()).containsExactly("OSI 7계층", "캐시 전략");
    }

    @Test
    @DisplayName("태그는 이름순으로 고정한다 — DB 순서에 맡기면 내용이 같아도 파일이 매번 바뀐다")
    void sortsTagsByName() throws Exception {
        given(List.of("OSI 7계층"), List.of(), List.of("tcp", "auth", "http"));

        exporter.export(tempDir);

        assertThat(read().tags()).containsExactly("auth", "http", "tcp");
    }

    @Test
    @DisplayName("문서도 태그도 없으면 파일을 만들지 않는다 — 빈 스냅샷은 프롬프트에 빈 블록만 남긴다")
    void doesNotWriteWhenEmpty() throws Exception {
        given(List.of(), List.of(), List.of());

        assertThat(exporter.export(tempDir)).isFalse();
        assertThat(tempDir.resolve(ExistingDocumentsExporter.FILE_NAME)).doesNotExist();
    }

    @Test
    @DisplayName("내용이 같으면 다시 쓰지 않는다 — 앱을 켤 때마다 git이 변경으로 보면 안 된다")
    void doesNotRewriteWhenUnchanged() throws Exception {
        given(List.of("OSI 7계층"), List.of(), List.of("network"));

        assertThat(exporter.export(tempDir)).as("처음에는 쓴다").isTrue();
        String first = Files.readString(tempDir.resolve(ExistingDocumentsExporter.FILE_NAME));

        assertThat(exporter.export(tempDir)).as("두 번째는 쓰지 않는다").isFalse();
        assertThat(Files.readString(tempDir.resolve(ExistingDocumentsExporter.FILE_NAME)))
                .isEqualTo(first);
    }

    @Test
    @DisplayName("문서가 새로 승인되면 다시 쓴다")
    void rewritesWhenTitlesChanged() throws Exception {
        given(List.of("OSI 7계층"), List.of(), List.of("network"));
        exporter.export(tempDir);

        given(List.of("OSI 7계층", "캐시 전략"), List.of(), List.of("network"));

        assertThat(exporter.export(tempDir)).isTrue();
        assertThat(read().titles()).hasSize(2);
    }

    @Test
    @DisplayName("기존 파일이 깨져 있으면 새로 쓴다 — 손으로 편집하다 깨뜨려도 복구된다")
    void rewritesWhenExistingFileIsBroken() throws Exception {
        Files.writeString(tempDir.resolve(ExistingDocumentsExporter.FILE_NAME), "{ 깨진 JSON");
        given(List.of("OSI 7계층"), List.of(), List.of("network"));

        assertThat(exporter.export(tempDir)).isTrue();
        assertThat(read().titles()).containsExactly("OSI 7계층");
    }

    /* ── 도우미 ──────────────────────────────────────────────── */

    private void given(List<String> documentTitles, List<String> draftTitles, List<String> tagNames) {
        lenient().when(documentRepository.findAllTitles()).thenReturn(documentTitles);
        lenient().when(draftRepository.findPendingTitles()).thenReturn(draftTitles);
        when(tagRepository.findAll()).thenReturn(tagNames.stream().map(Tag::of).toList());
    }

    private ExistingDocumentsFile read() throws Exception {
        return objectMapper.readValue(
                tempDir.resolve(ExistingDocumentsExporter.FILE_NAME).toFile(), ExistingDocumentsFile.class);
    }
}
