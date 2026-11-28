package project.study.study_project.llm.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.TestDomains;
import project.study.study_project.llm.client.GeneratedDocumentItem;
import project.study.study_project.llm.dto.GeneratedDocumentFile;
import project.study.study_project.llm.support.TopicQueue;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentBatchTopicIdTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 28);

    @Test
    @DisplayName("대기열에서 고른 주제의 id를 문서 파일에 적는다")
    void fileCarriesPickedTopicId() {
        TopicQueue.Picked picked = new TopicQueue.Picked(0, TestDomains.NETWORK, "TCP", null, null, null, 1137L);

        GeneratedDocumentFile file = DocumentBatch.documentFile(
                DATE, TestDomains.NETWORK, "claude-opus-5", null, null, picked);

        assertThat(file.topicId()).isEqualTo(1137L);
    }

    @Test
    @DisplayName("--topic으로 손수 준 주제는 id가 없어 칸을 비운다")
    void manualTopicWritesNoTopicId() {
        GeneratedDocumentFile file = DocumentBatch.documentFile(
                DATE, TestDomains.NETWORK, "claude-opus-5", null, null, null);

        assertThat(file.topicId()).isNull();
    }
}
