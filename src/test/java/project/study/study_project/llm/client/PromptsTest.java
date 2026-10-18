package project.study.study_project.llm.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptsTest {

    private static final Path DIR = Path.of("src/main/resources/prompts");

    @Test
    @DisplayName("프롬프트 파일은 모두 줄바꿈으로 끝난다. 읽을 때 떼는 줄바꿈이 없으면 마지막 글자가 잘린다")
    void everyFileEndsWithNewline() throws Exception {
        List<Path> files;
        try (Stream<Path> s = Files.walk(DIR)) {
            files = s.filter(Files::isRegularFile).toList();
        }
        assertThat(files).isNotEmpty();
        for (Path f : files) {
            String text = Files.readString(f, StandardCharsets.UTF_8).replace("\r\n", "\n");
            assertThat(text).as(f.toString()).endsWith("\n");
            String rel = DIR.relativize(f).toString().replace('\\', '/');
            assertThat(Prompts.load(rel)).as(rel).isNotBlank().doesNotContain("\r");
        }
    }

    @Test
    @DisplayName("프롬프트를 쓰는 클래스가 모두 뜬다. 경로가 틀리면 여기서 깨진다")
    void constantsLoad() {
        assertThat(ClaudeProblemGenerator.SYSTEM_PROMPT).isNotBlank();
        assertThat(ClaudeProblemGenerator.BEGINNER_EXAMPLE).isNotBlank();
        assertThat(ClaudeDocumentGenerator.BEGINNER_SYSTEM_PROMPT).isNotBlank();
        assertThat(ClaudeDocumentGenerator.ADVANCED_SYSTEM_PROMPT).isNotBlank();
        assertThat(ClaudeCodeProblemGenerator.SYSTEM_PROMPT).contains(ClaudeCodeProblemGenerator.DIFFICULTY_GUIDE);
        assertThat(ClaudeCodeProblemReviewer.SOLVE_PROMPT).endsWith(ClaudeCodeProblemGenerator.DIFFICULTY_GUIDE);
        assertThat(ClaudeDocumentFactChecker.WEB_SEARCH_RULE).doesNotContain("%d");
        assertThat(ClaudeProblemReviewer.JUDGE_PROMPT).isNotBlank();
        assertThat(ClaudeRationaleGenerator.SYSTEM_PROMPT).isNotBlank();
        assertThat(ClaudeTitleGenerator.SYSTEM_PROMPT).isNotBlank();
    }

    @Test
    @DisplayName("없는 파일은 경로를 담아 실패한다")
    void missingFile() {
        assertThatThrownBy(() -> Prompts.load("nope/none.txt"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prompts/nope/none.txt");
    }
}
