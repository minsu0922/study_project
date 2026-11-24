package project.study.study_project.llm.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import project.study.study_project.TestDomains;
import project.study.study_project.llm.support.GenerationSchedule;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-11-24 모델 자동 선택을 없앴다. 대기열이 비면(파일 없음·깨짐) 그날 문서를 만들지 않는다.
 * 생성기를 만들기 전에 돌아가므로 API 키 없이도 돈다 — 이 테스트가 키 없이 통과하는 것 자체가 확인이다.
 */
class DocumentBatchEmptyQueueTest {

    @TempDir
    Path out;

    @Test
    @DisplayName("주제 대기열이 비면 문서를 만들지 않는다 — 글의 주제 없는 문서는 승인도 막힌다")
    void skipsWhenQueueIsEmpty() throws Exception {
        Map<String, String> opts = Map.of("out", out.toString(), "date", "2026-11-25");

        DocumentBatch.generateDocument(opts, "claude-opus-5", null,
                List.of(TestDomains.NETWORK), GenerationSchedule.DEFAULT_ANCHOR, false, false);

        assertThat(Files.exists(out.resolve(BatchOptions.DOCUMENT_SUBDIR).resolve("2026-11-25.json"))).isFalse();
    }
}
