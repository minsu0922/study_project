package project.study.study_project.llm.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import project.study.study_project.TestDomains;
import project.study.study_project.llm.client.GeneratedDocumentItem;
import project.study.study_project.llm.dto.GeneratedDocumentFile;
import project.study.study_project.llm.support.JsonMappers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 검수함에서 고친 본문이 배치 파일에 옮겨지는지 본다. 실제 파일을 임시 폴더에 만들어 읽고 쓴다. */
class DocumentFileSyncTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("slug와 고치기 전 본문이 같은 편만 바꾸고, 짝 편과 나머지 칸은 그대로 둔다")
    void replacesMatchingEditionOnly() throws Exception {
        Path file = writeFile("2026-10-19.json",
                item("thread", "# 스레드\n\n틀린 문장."), item("thread-advanced", "# 심화\n\n틀린 문장."));
        String before = Files.readString(file);

        var synced = sync().replaceContent("thread-advanced", "# 심화\n\n틀린 문장.", "# 심화\n\n고친 문장.");

        assertThat(synced).contains("2026-10-19.json");
        GeneratedDocumentFile after = JsonMappers.STRICT.readValue(file.toFile(), GeneratedDocumentFile.class);
        assertThat(after.advancedDocument().contentMd()).isEqualTo("# 심화\n\n고친 문장.");
        assertThat(after.document().contentMd()).isEqualTo("# 스레드\n\n틀린 문장.");
        assertThat(after.date()).isEqualTo("2026-10-19");
        assertThat(Files.readString(file)).as("본문 글자만 바뀌고 줄 배치는 그대로다")
                .isEqualTo(before.replace("# 심화\\n\\n틀린 문장.", "# 심화\\n\\n고친 문장."));
        assertThat(Files.exists(file.resolveSibling("2026-10-19.json.tmp"))).isFalse();
    }

    @Test
    @DisplayName("이 앱보다 새 형식의 문서 파일은 고치지 않는다 — 모르는 칸을 버린 채 다시 쓰면 그 칸이 사라진다")
    void leavesNewerFormatFile() throws Exception {
        Path file = writeFile("2026-10-21.json", item("cache", "# 캐시\n\n원문."), null);
        String newer = Files.readString(file).replaceFirst("\\{",
                "{\n  \"formatVersion\" : " + (project.study.study_project.llm.support.FileFormats.Kind.DOCUMENTS.current() + 1)
                        + ",\n  \"brandNewField\" : 1,");
        Files.writeString(file, newer);

        assertThat(sync().replaceContent("cache", "# 캐시\n\n원문.", "# 캐시\n\n고침.")).isEmpty();
        assertThat(Files.readString(file)).isEqualTo(newer);
    }

    @Test
    @DisplayName("본문이 이미 달라졌거나 slug가 없으면 파일을 건드리지 않는다")
    void leavesFileWhenNothingMatches() throws Exception {
        Path file = writeFile("2026-10-20.json", item("cache", "# 캐시\n\n원문."), null);
        String before = Files.readString(file);

        assertThat(sync().replaceContent("cache", "# 캐시\n\n다른 본문.", "고침")).isEmpty();
        assertThat(sync().replaceContent("nope", "# 캐시\n\n원문.", "고침")).isEmpty();
        assertThat(Files.readString(file)).isEqualTo(before);
    }

    @Test
    @DisplayName("본문 글자를 그대로 못 찾는 파일은 통째로 다시 써서라도 고친다")
    void rewritesWholeFileWhenTextDiffers() throws Exception {
        Path dir = Files.createDirectories(root.resolve("documents"));
        Path file = dir.resolve("2026-10-21.json");
        // 손으로 쓴 파일처럼 한글을 \\u로 적어, 글자 그대로 찾기가 안 되게 한다
        Files.writeString(file, """
                {"date":"2026-10-21","domain":"%s","model":"m",
                 "document":{"title":"t","slug":"net","contentMd":"\\uD2C0\\uB9B0 \\uBB38\\uC7A5","tags":[]}}
                """.formatted(TestDomains.SYSTEM_DESIGN.value()));

        assertThat(sync().replaceContent("net", "틀린 문장", "고친 문장")).contains("2026-10-21.json");
        assertThat(JsonMappers.STRICT.readValue(file.toFile(), GeneratedDocumentFile.class).document().contentMd())
                .isEqualTo("고친 문장");
    }

    private DocumentFileSync sync() {
        return new DocumentFileSync(root.toString());
    }

    private GeneratedDocumentItem item(String slug, String content) {
        return new GeneratedDocumentItem("제목", slug, content, List.of("x"));
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
