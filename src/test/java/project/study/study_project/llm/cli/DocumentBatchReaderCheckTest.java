package project.study.study_project.llm.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import project.study.study_project.TestDomains;
import project.study.study_project.llm.client.DocumentFactChecker;
import project.study.study_project.llm.client.DocumentReaderCheck;
import project.study.study_project.llm.client.FactCheckFinding;
import project.study.study_project.llm.client.GeneratedDocumentItem;
import project.study.study_project.llm.client.ReaderFinding;
import project.study.study_project.llm.dto.GeneratedDocumentFile;
import project.study.study_project.llm.support.FileFormats;
import project.study.study_project.llm.support.JsonMappers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 사실 검수와 나란히 도는 독자 점검 — 한쪽이 실패해도 다른 쪽 결과와 문서 파일은 남아야 한다. */
class DocumentBatchReaderCheckTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 20);
    private static final FactCheckFinding FACT = new FactCheckFinding(
            "정의.", FactCheckFinding.Kind.FACT_ERROR, "틀렸다", "고친다", FactCheckFinding.Confidence.HIGH);
    private static final ReaderFinding READER = new ReaderFinding(ReaderFinding.Kind.BLOCK,
            ReaderFinding.Type.UNDEFINED_TERM, ReaderFinding.Weight.PAUSE, "무엇인가", "정의.", "", "모른다",
            new ReaderFinding.Suggestion(ReaderFinding.Op.NEED_HUMAN, "", "", "물음"));

    private static GeneratedDocumentFile file() {
        return new GeneratedDocumentFile("테스트", DATE.toString(), DATE + "T00:00:00Z", TestDomains.NETWORK, "m",
                new GeneratedDocumentItem("제목", "s", "# 입문편\n\n## 무엇인가\n\n정의.\n", List.of("net")),
                new GeneratedDocumentItem("제목", "s-advanced", "# 심화편\n\n## 이 글을 읽기 전에\n\n정의.\n", List.of("net")));
    }

    private static Path write(Path tmp, GeneratedDocumentFile file) throws Exception {
        Path out = Files.createDirectories(tmp.resolve("documents")).resolve(DATE + ".json");
        Files.writeString(out, FileFormats.toJson(JsonMappers.STRICT, FileFormats.Kind.DOCUMENTS, file));
        return out;
    }

    private static GeneratedDocumentFile read(Path out) throws Exception {
        return FileFormats.read(JsonMappers.STRICT, out, FileFormats.Kind.DOCUMENTS, GeneratedDocumentFile.class);
    }

    private static final DocumentFactChecker ONE_FACT = (title, content, edition) -> List.of(FACT);

    @Test
    @DisplayName("독자 점검이 입문편에서 실패해도 사실 검수 지적은 실리고, 그 편만 null이다")
    void readerFailureKeepsFactFindingsAndNullsThatEdition(@TempDir Path tmp) throws Exception {
        Path out = write(tmp, file());
        DocumentReaderCheck flaky = (title, content, edition, earlier, beginner) -> {
            if (beginner == null) {
                throw new IllegalStateException("독자 점검 호출 실패");
            }
            return List.of(READER);
        };

        DocumentBatch.checkAndAttach(out, file(), DATE, ONE_FACT, flaky, List.of());

        GeneratedDocumentFile after = read(out);
        assertThat(after.factFindings()).hasSize(2);
        assertThat(after.readerFindings().beginner()).isNull();
        assertThat(after.readerFindings().advanced()).containsExactly(READER);
    }

    @Test
    @DisplayName("심화편 점검에는 입문편 본문과 코스 앞 글 이름이 넘어간다")
    void advancedGetsBeginnerAndEarlierTopics(@TempDir Path tmp) throws Exception {
        Path out = write(tmp, file());
        List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        DocumentReaderCheck spy = (title, content, edition, earlier, beginner) -> {
            seen.add(edition + "|" + earlier + "|" + (beginner == null ? "-" : beginner.contains("# 입문편")));
            return List.of();
        };

        DocumentBatch.checkAndAttach(out, file(), DATE, ONE_FACT, spy, List.of("포트"));

        assertThat(seen).containsExactlyInAnyOrder("BEGINNER|[포트]|-", "ADVANCED|[포트]|true");
        assertThat(read(out).readerFindings().beginner()).isEmpty();
    }

    @Test
    @DisplayName("독자 점검이 꺼져 있으면 파일에 그 칸이 생기지 않는다")
    void disabledLeavesFileWithoutReaderFindings(@TempDir Path tmp) throws Exception {
        Path out = write(tmp, file());

        DocumentBatch.checkAndAttach(out, file(), DATE, ONE_FACT, null, List.of());

        GeneratedDocumentFile after = read(out);
        assertThat(after.factFindings()).hasSize(2);
        assertThat(after.readerFindings()).isNull();
    }
}
