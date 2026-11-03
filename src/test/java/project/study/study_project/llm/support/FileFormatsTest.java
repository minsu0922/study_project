package project.study.study_project.llm.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import project.study.study_project.llm.dto.TopicUsageFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** generated/ 파일의 형식 버전(docs/14 "형식 버전"). */
class FileFormatsTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("쓸 때 formatVersion을 맨 앞에 찍는다")
    void stampsVersionFirst() throws Exception {
        // 줄바꿈은 운영체제를 따른다(Windows는 \r\n). 내용만 본다
        String json = FileFormats.toJson(JsonMappers.STRICT, FileFormats.Kind.TOPIC_USAGE,
                new TopicUsageFile("설명", List.of())).replace("\r\n", "\n");

        assertThat(json).startsWith("{\n  \"formatVersion\" : " + FileFormats.Kind.TOPIC_USAGE.current() + ",");
        assertThat(json).contains("\"note\" : \"설명\"");
    }

    @Test
    @DisplayName("찍은 파일을 엄격한 매퍼로 읽어도 버전 칸 때문에 실패하지 않는다")
    void readsStampedFileStrictly() throws Exception {
        Path file = dir.resolve("_topic-usage.json");
        Files.writeString(file, FileFormats.toJson(JsonMappers.STRICT, FileFormats.Kind.TOPIC_USAGE,
                new TopicUsageFile("설명", List.of(new TopicUsageFile.Usage(1L, "OS", "주제", "2026-11-03", 1)))));

        TopicUsageFile read = FileFormats.read(JsonMappers.STRICT, file, FileFormats.Kind.TOPIC_USAGE, TopicUsageFile.class);

        assertThat(read.usage()).singleElement().satisfies(u -> assertThat(u.topic()).isEqualTo("주제"));
    }

    @Test
    @DisplayName("버전이 없는 옛 파일은 1로 보고 읽는다")
    void readsLegacyFileAsVersionOne() throws Exception {
        Path file = dir.resolve("_topic-usage.json");
        Files.writeString(file, "{ \"usage\": [] }");

        assertThat(FileFormats.read(JsonMappers.STRICT, file, FileFormats.Kind.TOPIC_USAGE, TopicUsageFile.class).usage())
                .isEmpty();
    }

    @Test
    @DisplayName("이 코드보다 새 버전이면 읽지 않고 무엇을 하라는지 알린다")
    void refusesNewerVersion() throws Exception {
        Path file = dir.resolve("_topic-usage.json");
        int newer = FileFormats.Kind.TOPIC_USAGE.current() + 1;
        Files.writeString(file, "{ \"formatVersion\": " + newer + ", \"usage\": [], \"brandNewField\": 1 }");

        assertThatThrownBy(() -> FileFormats.read(JsonMappers.STRICT, file, FileFormats.Kind.TOPIC_USAGE, TopicUsageFile.class))
                .isInstanceOf(FileFormats.NewerFormatException.class)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("_topic-usage.json")
                .hasMessageContaining("형식 버전 " + newer)
                .hasMessageContaining("최신으로 맞추세요");
    }
}
