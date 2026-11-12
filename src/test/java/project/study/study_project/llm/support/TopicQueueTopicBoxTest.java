package project.study.study_project.llm.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 개념 주제는 글의 주제(V48) 두 편을 다 써야 배치가 꺼낸다. 코드 문제 주제는 상관없다. */
class TopicQueueTopicBoxTest {

    @TempDir
    Path dir;

    @Test
    void 글의_주제를_다_쓴_주제만_꺼낸다() throws Exception {
        Files.writeString(dir.resolve(TopicQueue.FILE_NAME), """
                { "topics": [
                  { "domain": "OS", "topic": "안 쓴 주제" },
                  { "domain": "OS", "topic": "한 편만 쓴 주제", "beginnerTopicBox": "입문" },
                  { "domain": "OS", "topic": "다 쓴 주제", "beginnerTopicBox": "입문 글", "advancedTopicBox": "심화 글" }
                ] }
                """);

        TopicQueue queue = TopicQueue.read(dir);
        TopicQueue.Picked picked = queue.next();

        assertThat(picked.topic()).isEqualTo("다 쓴 주제");
        assertThat(picked.beginnerTopicBox()).isEqualTo("입문 글");
        assertThat(picked.advancedTopicBox()).isEqualTo("심화 글");
        assertThat(queue.boxlessCount()).isEqualTo(2);
    }

    @Test
    void 다_쓴_주제가_없으면_아무것도_꺼내지_않는다() throws Exception {
        Files.writeString(dir.resolve(TopicQueue.FILE_NAME), """
                { "topics": [ { "domain": "OS", "topic": "안 쓴 주제" } ] }
                """);

        TopicQueue queue = TopicQueue.read(dir);

        assertThat(queue.next()).isNull();
        assertThat(queue.boxlessCount()).isEqualTo(1);
    }

    @Test
    void 코드_문제_주제는_글의_주제가_없어도_꺼낸다() throws Exception {
        Files.writeString(dir.resolve(TopicQueue.CODE_FILE_NAME), """
                { "topics": [ { "domain": "JAVA", "topic": "HashMap 충돌" } ] }
                """);

        TopicQueue queue = TopicQueue.read(dir, TopicQueue.CODE_FILE_NAME);

        assertThat(queue.next().topic()).isEqualTo("HashMap 충돌");
        assertThat(queue.boxlessCount()).isZero();
    }

    @Test
    void 사용_기록을_찍어도_글의_주제가_남는다() {
        var entry = new project.study.study_project.llm.dto.TopicQueueFile.Entry(
                1L, "OS", "주제", null, null, null, null, "입문", "심화");

        var used = entry.usedOn("2026-11-12");

        assertThat(used.beginnerTopicBox()).isEqualTo("입문");
        assertThat(used.advancedTopicBox()).isEqualTo("심화");
        assertThat(used.hasTopicBoxes()).isTrue();
    }
}
