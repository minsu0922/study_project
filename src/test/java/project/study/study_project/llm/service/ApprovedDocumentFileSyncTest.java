package project.study.study_project.llm.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import project.study.study_project.TestDomains;
import project.study.study_project.document.domain.Document;
import project.study.study_project.document.repository.DocumentRepository;
import project.study.study_project.global.event.DocumentContentChanged;
import project.study.study_project.llm.client.GeneratedDocumentItem;
import project.study.study_project.llm.dto.GeneratedDocumentFile;
import project.study.study_project.llm.support.JsonMappers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 정식 문서가 원본 배치 파일의 최신본이 되는지 본다. 실제 파일을 임시 폴더에 만들어 읽고 쓴다. */
class ApprovedDocumentFileSyncTest {

    @TempDir
    Path root;

    private final DocumentRepository repository = mock(DocumentRepository.class);

    @Test
    @DisplayName("앱이 뜨면 정식 문서와 다른 편만 파일을 고치고, 같은 파일과 배치 파일 없는 문서는 건드리지 않는다")
    void startupSyncsOnlyDriftedEditions() throws Exception {
        Path drifted = writeFile("2026-10-19.json", item("thread", "옛 본문"), item("thread-advanced", "심화 본문"));
        Path same = writeFile("2026-10-20.json", item("cache", "같은 본문"), null);
        String sameBefore = Files.readString(same);
        String driftedBefore = Files.readString(drifted);
        when(repository.findAll()).thenReturn(List.of(
                doc("thread", "고친 본문"), doc("thread-advanced", "심화 본문"),
                doc("cache", "같은 본문"), doc("hand-written", "손으로 등록한 문서")));

        new ApprovedDocumentFileSync(repository, sync()).run(null);

        GeneratedDocumentFile after = read(drifted);
        assertThat(after.document().contentMd()).isEqualTo("고친 본문");
        assertThat(after.advancedDocument().contentMd()).isEqualTo("심화 본문");
        assertThat(Files.readString(drifted)).as("본문 글자만 바뀌고 줄 배치는 그대로다")
                .isEqualTo(driftedBefore.replace("\"옛 본문\"", "\"고친 본문\""));
        assertThat(Files.readString(same)).isEqualTo(sameBefore);
    }

    @Test
    @DisplayName("문서 관리에서 slug와 제목을 바꾸면 옛 slug로 찾아 파일의 slug·제목·본문을 함께 바꾼다")
    void editFollowsSlugAndTitleChange() throws Exception {
        Path file = writeFile("2026-10-21.json", item("old-slug", "본문"), null);

        new ApprovedDocumentFileSync(repository, sync())
                .onDocumentContentChanged(new DocumentContentChanged("old-slug", "new-slug", "새 제목", "새 본문"));

        GeneratedDocumentItem after = read(file).document();
        assertThat(after.slug()).isEqualTo("new-slug");
        assertThat(after.title()).isEqualTo("새 제목");
        assertThat(after.contentMd()).isEqualTo("새 본문");
        assertThat(after.tags()).as("태그는 파일 것을 둔다").containsExactly("x");
    }

    @Test
    @DisplayName("배치 파일 폴더가 없어도 예외 없이 지나간다")
    void missingDirectoryIsQuiet() {
        when(repository.findAll()).thenReturn(List.of(doc("thread", "본문")));

        assertThatCode(() -> new ApprovedDocumentFileSync(repository,
                new DocumentFileSync(root.resolve("nope").toString())).run(null))
                .doesNotThrowAnyException();
    }

    private DocumentFileSync sync() {
        return new DocumentFileSync(root.toString());
    }

    private Document doc(String slug, String content) {
        return Document.create(TestDomains.SYSTEM_DESIGN, "제목", slug, content, null, Set.of());
    }

    private GeneratedDocumentItem item(String slug, String content) {
        return new GeneratedDocumentItem("제목", slug, content, List.of("x"));
    }

    private GeneratedDocumentFile read(Path file) throws Exception {
        return JsonMappers.STRICT.readValue(file.toFile(), GeneratedDocumentFile.class);
    }

    private Path writeFile(String name, GeneratedDocumentItem doc, GeneratedDocumentItem advanced) throws Exception {
        Path dir = Files.createDirectories(root.resolve("documents"));
        Path file = dir.resolve(name);
        GeneratedDocumentFile batch = new GeneratedDocumentFile("테스트용", name.replace(".json", ""),
                "2026-10-19T00:00:00Z", TestDomains.SYSTEM_DESIGN, "claude-opus-5", doc, advanced);
        Files.writeString(file, JsonMappers.STRICT.writerWithDefaultPrettyPrinter().writeValueAsString(batch));
        return file;
    }
}
