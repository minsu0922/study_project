package project.study.study_project.llm.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.llm.client.FactCheckFinding;
import project.study.study_project.llm.client.FactCheckFinding.Confidence;
import project.study.study_project.llm.client.FactCheckFinding.Kind;
import project.study.study_project.llm.client.GeneratedDocumentItem;
import project.study.study_project.llm.dto.GeneratedDocumentFile;
import project.study.study_project.TestDomains;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 사실 검수 지적이 문서 파일에 실려 문제를 만드는 날까지 가는지 — docs/22 §6. */
class DocumentFactFindingsTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 13);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final FactCheckFinding FINDING = new FactCheckFinding(
            "정의.", Kind.FACT_ERROR, "틀렸다", "이렇게 고친다", Confidence.HIGH);

    private static GeneratedDocumentFile file() {
        return new GeneratedDocumentFile("테스트", DATE.toString(), DATE + "T00:00:00Z", TestDomains.NETWORK,
                "test-model",
                new GeneratedDocumentItem("제목", "test-slug", "# 입문편\n\n## 무엇인가\n정의.\n", List.of("net")),
                null);
    }

    private static Path write(Path tmp, GeneratedDocumentFile file) throws Exception {
        Path docDir = Files.createDirectories(tmp.resolve("documents"));
        Path out = docDir.resolve(DATE + ".json");
        MAPPER.writeValue(out.toFile(), file);
        return out;
    }

    @Test
    @DisplayName("문서 파일에 실은 지적을 문제 생성 쪽이 근거 문서와 함께 읽는다")
    void findingsTravelFromDocumentFileToResolvedSource(@TempDir Path tmp) throws Exception {
        Path out = write(tmp, file());

        DocumentBatch.attachFactFindings(out, file(), List.of(FINDING));

        var resolved = SourceDocumentFinder.findSourceDocument(tmp, DATE, Difficulty.BEGINNER,
                ProblemType.MULTIPLE_CHOICE);
        assertThat(resolved).isNotNull();
        assertThat(resolved.factFindings()).containsExactly(FINDING);
        assertThat(Files.exists(out.resolveSibling(out.getFileName() + ".tmp"))).isFalse();
    }

    @Test
    @DisplayName("지적 칸이 없는 옛 문서 파일도 읽히고, 지적은 null이다")
    void oldFileWithoutFindingsStillParses(@TempDir Path tmp) throws Exception {
        Path out = write(tmp, file());
        var tree = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(out.toFile());
        tree.remove("factFindings");
        MAPPER.writeValue(out.toFile(), tree);

        var resolved = SourceDocumentFinder.findSourceDocument(tmp, DATE, Difficulty.BEGINNER,
                ProblemType.MULTIPLE_CHOICE);

        assertThat(resolved).isNotNull();
        assertThat(resolved.factFindings()).isNull();
    }

    private static GeneratedDocumentItem item(String slug, String content) {
        return new GeneratedDocumentItem("제목", slug, content, List.of("net"));
    }

    @Test
    @DisplayName("윤문본과 원본을 함께 실은 파일이 엄격한 읽기로 왕복한다")
    void polishedFileRoundTripsStrictly(@TempDir Path tmp) throws Exception {
        GeneratedDocumentFile polished = file().withPolished(
                item("test-slug", "# 입문편\n\n## 무엇인가\n고친 정의.\n"), null,
                item("test-slug", "# 입문편\n\n## 무엇인가\n정의.\n"), null);
        Path out = tmp.resolve("x.json");
        Files.writeString(out, project.study.study_project.llm.support.FileFormats.toJson(
                project.study.study_project.llm.support.JsonMappers.STRICT,
                project.study.study_project.llm.support.FileFormats.Kind.DOCUMENTS, polished));

        GeneratedDocumentFile read = project.study.study_project.llm.support.FileFormats.read(
                project.study.study_project.llm.support.JsonMappers.STRICT, out,
                project.study.study_project.llm.support.FileFormats.Kind.DOCUMENTS, GeneratedDocumentFile.class);

        assertThat(read.document().contentMd()).contains("고친 정의.");
        assertThat(read.originalDocument().contentMd()).contains("\n정의.");
        assertThat(read.originalAdvancedDocument()).isNull();
    }

    @Test
    @DisplayName("원본 칸이 없는 옛 문서 파일도 읽히고, 원본 칸은 null이다")
    void oldFileWithoutOriginalsParses(@TempDir Path tmp) throws Exception {
        Path out = write(tmp, file());
        var tree = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(out.toFile());
        tree.remove("originalDocument");
        tree.remove("originalAdvancedDocument");
        MAPPER.writeValue(out.toFile(), tree);

        GeneratedDocumentFile read = project.study.study_project.llm.support.JsonMappers.STRICT
                .readValue(out.toFile(), GeneratedDocumentFile.class);

        assertThat(read.originalDocument()).isNull();
        assertThat(read.originalAdvancedDocument()).isNull();
    }

    @Test
    @DisplayName("윤문한 파일에 사실 검수 지적을 붙여도 원본 칸이 남는다")
    void findingsKeepOriginals() {
        GeneratedDocumentFile polished = file().withPolished(
                item("test-slug", "고친 본문"), null, item("test-slug", "원고"), null);

        GeneratedDocumentFile withFindings = polished.withFactFindings(List.of(FINDING));

        assertThat(withFindings.originalDocument().contentMd()).isEqualTo("원고");
        assertThat(withFindings.factFindings()).containsExactly(FINDING);
    }

    private static final project.study.study_project.llm.client.ReaderFinding READER = new project.study.study_project.llm.client.ReaderFinding(
            project.study.study_project.llm.client.ReaderFinding.Kind.BLOCK,
            project.study.study_project.llm.client.ReaderFinding.Type.UNDEFINED_TERM,
            project.study.study_project.llm.client.ReaderFinding.Weight.PAUSE, "무엇인가", "정의.", "", "모른다",
            new project.study.study_project.llm.client.ReaderFinding.Suggestion(
                    project.study.study_project.llm.client.ReaderFinding.Op.REPLACE, "정의.", "정의다.", "본문"));

    @Test
    @DisplayName("독자 점검 칸이 없는 옛 문서 파일도 엄격한 읽기로 읽히고, 그 칸은 null이다")
    void oldFileWithoutReaderFindingsStillReads(@TempDir Path tmp) throws Exception {
        Path out = write(tmp, file());
        var tree = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(out.toFile());
        tree.remove("readerFindings");
        MAPPER.writeValue(out.toFile(), tree);

        assertThat(readStrict(out).readerFindings()).isNull();
    }

    @Test
    @DisplayName("독자 점검을 실은 파일이 엄격한 읽기로 왕복하고, 사실 검수·윤문을 붙여도 칸이 남는다")
    void readerFindingsRoundTripAndSurviveOtherSteps(@TempDir Path tmp) throws Exception {
        var reader = new project.study.study_project.llm.dto.ReaderFindings(List.of(READER), null);
        GeneratedDocumentFile file = file().withReaderFindings(reader);

        assertThat(readStrict(write(tmp, file)).readerFindings()).isEqualTo(reader);
        assertThat(file.withFactFindings(List.of(FINDING)).readerFindings()).isEqualTo(reader);
        assertThat(file.withPolished(item("test-slug", "고친 본문"), null, item("test-slug", "원고"), null)
                .readerFindings()).isEqualTo(reader);
    }

    private static GeneratedDocumentFile twoEditions() {
        return new GeneratedDocumentFile("테스트", DATE.toString(), DATE + "T00:00:00Z", TestDomains.NETWORK,
                "test-model", item("test-slug", "입문 원고"), item("test-slug-advanced", "심화 원고"));
    }

    private static BothEditions.Pair<PolishOutcome> outcomes(boolean beginnerApplied, boolean advancedApplied) {
        return new BothEditions.Pair<>(
                beginnerApplied ? new PolishOutcome(item("test-slug", "입문 윤문"), true, null)
                        : new PolishOutcome(item("test-slug", "입문 원고"), false, "수치가 다릅니다"),
                advancedApplied ? new PolishOutcome(item("test-slug-advanced", "심화 윤문"), true, null)
                        : new PolishOutcome(item("test-slug-advanced", "심화 원고"), false, "수치가 다릅니다"));
    }

    private static GeneratedDocumentFile readStrict(Path out) throws Exception {
        return project.study.study_project.llm.support.FileFormats.read(
                project.study.study_project.llm.support.JsonMappers.STRICT, out,
                project.study.study_project.llm.support.FileFormats.Kind.DOCUMENTS, GeneratedDocumentFile.class);
    }

    @Test
    @DisplayName("윤문본을 채택하면 파일 본문 칸에 윤문본, 원본 칸에 원고가 실린다")
    void appliedPolishIsWrittenWithOriginals(@TempDir Path tmp) throws Exception {
        Path out = write(tmp, twoEditions());

        GeneratedDocumentFile result = DocumentBatch.applyPolish(out, twoEditions(), outcomes(true, true));

        GeneratedDocumentFile read = readStrict(out);
        assertThat(read).isEqualTo(result);
        assertThat(read.document().contentMd()).isEqualTo("입문 윤문");
        assertThat(read.originalDocument().contentMd()).isEqualTo("입문 원고");
        assertThat(read.advancedDocument().contentMd()).isEqualTo("심화 윤문");
        assertThat(read.originalAdvancedDocument().contentMd()).isEqualTo("심화 원고");
        assertThat(Files.exists(out.resolveSibling(out.getFileName() + ".tmp"))).isFalse();
    }

    @Test
    @DisplayName("두 편 다 원본 유지면 파일을 다시 쓰지 않는다")
    void nothingAppliedLeavesFile(@TempDir Path tmp) throws Exception {
        Path out = write(tmp, twoEditions());
        String before = Files.readString(out);

        GeneratedDocumentFile result = DocumentBatch.applyPolish(out, twoEditions(), outcomes(false, false));

        assertThat(Files.readString(out)).isEqualTo(before);
        assertThat(result).isEqualTo(twoEditions());
    }

    @Test
    @DisplayName("입문편만 채택하면 심화편은 원고 그대로이고 심화편 원본 칸은 null이다")
    void onlyBeginnerApplied(@TempDir Path tmp) throws Exception {
        Path out = write(tmp, twoEditions());

        DocumentBatch.applyPolish(out, twoEditions(), outcomes(true, false));

        GeneratedDocumentFile read = readStrict(out);
        assertThat(read.document().contentMd()).isEqualTo("입문 윤문");
        assertThat(read.advancedDocument().contentMd()).isEqualTo("심화 원고");
        assertThat(read.originalAdvancedDocument()).isNull();
    }

    @Test
    @DisplayName("윤문한 파일에 사실 검수 지적을 붙여도 원본 칸이 파일에 남는다")
    void findingsAfterPolishKeepOriginalsOnDisk(@TempDir Path tmp) throws Exception {
        Path out = write(tmp, twoEditions());
        GeneratedDocumentFile polished = DocumentBatch.applyPolish(out, twoEditions(), outcomes(true, false));

        DocumentBatch.attachFactFindings(out, polished, List.of(FINDING));

        GeneratedDocumentFile read = readStrict(out);
        assertThat(read.originalDocument().contentMd()).isEqualTo("입문 원고");
        assertThat(read.factFindings()).containsExactly(FINDING);
    }

    @Test
    @DisplayName("두 편 다 검수하지 못하면 null, 한 편이라도 했으면 그 지적만 싣는다")
    void mergesEditionFindings() {
        assertThat(DocumentBatch.mergeFactFindings(null, null)).isNull();
        assertThat(DocumentBatch.mergeFactFindings(List.of(), null)).isEmpty();
        assertThat(DocumentBatch.mergeFactFindings(null, List.of(FINDING))).containsExactly(FINDING);
    }
}
