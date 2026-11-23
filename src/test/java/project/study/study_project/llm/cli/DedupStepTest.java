package project.study.study_project.llm.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import project.study.study_project.TestDomains;
import project.study.study_project.llm.client.DedupProposal;
import project.study.study_project.llm.client.DocumentDeduplicator;
import project.study.study_project.llm.client.GeneratedDocumentItem;
import project.study.study_project.llm.dto.DedupRemoval;
import project.study.study_project.llm.dto.GeneratedDocumentFile;
import project.study.study_project.llm.support.FileFormats;
import project.study.study_project.llm.support.JsonMappers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DedupStepTest {

    private static final String KEPT = "서비스는 할 일을 정한다.";
    private static final String DUP = "할 일을 정하는 쪽은 서비스다.";
    private static final String PADDING = "이 문단은 분량을 맞추려고 넣은 평범한 설명 문장이다. ".repeat(12);
    private static final String BODY = "# 제목\n\n> **글의 주제**\n>\n> 주제다.\n\n## 본론\n\n첫 문장이다. " + KEPT
            + " 둘째 문장이다. " + DUP + " 끝 문장이다.\n\n" + PADDING.strip() + "\n";

    private static GeneratedDocumentItem item(String body) {
        return new GeneratedDocumentItem("제목", "title-slug", body, List.of("tag"));
    }

    private static DocumentDeduplicator returning(DedupProposal... proposals) {
        return (title, contentMd, edition) -> List.of(proposals);
    }

    @Test
    @DisplayName("통과한 문장을 지운 편을 채택하고 지운 기록을 남긴다")
    void appliesAcceptedRemoval() {
        DedupOutcome outcome = DedupStep.decide(returning(new DedupProposal(DUP, KEPT, "같은 말")), item(BODY), null);

        assertThat(outcome.applied()).isTrue();
        assertThat(outcome.chosen().contentMd()).doesNotContain(DUP).contains(KEPT);
        assertThat(outcome.removals()).containsExactly(new DedupRemoval(DUP, KEPT, "본론", "같은 말"));
        assertThat(DedupStep.summaryLine("입문편", outcome)).contains("중복 1건 제거");
    }

    @Test
    @DisplayName("호출이 실패하면 원본을 쓰고 job을 멈추지 않는다")
    void keepsOriginalWhenCallFails() {
        DocumentDeduplicator failing = (title, contentMd, edition) -> {
            throw new IllegalStateException("요금 한도");
        };

        DedupOutcome outcome = DedupStep.decide(failing, item(BODY), null);

        assertThat(outcome.applied()).isFalse();
        assertThat(outcome.chosen().contentMd()).isEqualTo(BODY);
        assertThat(outcome.reason()).contains("호출 실패");
    }

    @Test
    @DisplayName("통과한 후보가 없으면 원본을 쓰고 버린 까닭을 요약에 남긴다")
    void keepsOriginalWhenNothingPasses() {
        DedupOutcome outcome = DedupStep.decide(returning(new DedupProposal("없는 문장이다.", KEPT, "x")), item(BODY), null);

        assertThat(outcome.applied()).isFalse();
        assertThat(DedupStep.summaryLine("심화편", outcome)).contains("버린 제안 1건").contains("찾지 못함");
        assertThat(DedupStep.rejectedDetails("심화편", outcome)).singleElement().asString()
                .contains("심화편").contains("찾지 못함").contains("없는 문장이다.");
    }

    @Test
    @DisplayName("지울 분량이 10%를 넘으면 그 편은 원본을 쓴다")
    void keepsOriginalWhenTooMuchRemoved() {
        String longDup = "할 일을 정하는 쪽은 서비스이고 규칙을 따지는 쪽도 서비스이며 판단을 맡는 쪽 역시 서비스다.";
        String body = "# 제목\n\n> **글의 주제**\n>\n> 주제다.\n\n## 본론\n\n첫 문장이다. " + KEPT + " 둘째 문장이다. " + longDup + " 끝이다.\n";

        DedupOutcome outcome = DedupStep.decide(returning(new DedupProposal(longDup, KEPT, "같은 말")), item(body), null);

        assertThat(outcome.applied()).isFalse();
        assertThat(outcome.reason()).contains("상한");
    }

    @Test
    @DisplayName("중복 제거 뒤 윤문해도 원본 칸에는 처음 생성한 글이 남고, 기록이 파일에 실린다")
    void keepsFirstOriginalThroughPolish(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("2026-11-22.json");
        GeneratedDocumentItem generated = item(BODY);
        GeneratedDocumentFile file = new GeneratedDocumentFile("테스트", "2026-11-22", "2026-11-22T00:00:00Z",
                TestDomains.NETWORK, "m", generated, null);
        Files.writeString(out, FileFormats.toJson(JsonMappers.STRICT, FileFormats.Kind.DOCUMENTS, file));
        DedupOutcome dedup = DedupStep.decide(returning(new DedupProposal(DUP, KEPT, "같은 말")), generated, null);

        GeneratedDocumentFile deduped = DocumentBatch.applyDedup(out, file, new BothEditions.Pair<>(dedup, null));
        GeneratedDocumentItem polishedItem = item(deduped.document().contentMd().replace("끝 문장이다.", "끝 문장."));
        GeneratedDocumentFile polished = DocumentBatch.applyPolish(out, deduped,
                new BothEditions.Pair<>(new PolishOutcome(polishedItem, true, null), null));

        GeneratedDocumentFile read = FileFormats.read(JsonMappers.STRICT, out, FileFormats.Kind.DOCUMENTS,
                GeneratedDocumentFile.class);
        assertThat(polished.document().contentMd()).contains("끝 문장.");
        assertThat(read.originalDocument().contentMd()).isEqualTo(BODY);
        assertThat(read.dedupRemovals().beginner()).hasSize(1);
        assertThat(read.dedupRemovals().advanced()).isNull();
    }

    @Test
    @DisplayName("윤문이 원본을 유지해도 중복 제거가 남긴 원본 칸을 지우지 않는다")
    void polishRejectionKeepsOriginal(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("2026-11-22.json");
        GeneratedDocumentItem generated = item(BODY);
        GeneratedDocumentItem advanced = new GeneratedDocumentItem("제목", "title-slug-advanced", BODY, List.of());
        GeneratedDocumentFile file = new GeneratedDocumentFile("테스트", "2026-11-22", "2026-11-22T00:00:00Z",
                TestDomains.NETWORK, "m", generated, advanced);
        Files.writeString(out, FileFormats.toJson(JsonMappers.STRICT, FileFormats.Kind.DOCUMENTS, file));
        DedupOutcome dedup = DedupStep.decide(returning(new DedupProposal(DUP, KEPT, "같은 말")), generated, null);
        GeneratedDocumentFile deduped = DocumentBatch.applyDedup(out, file, new BothEditions.Pair<>(dedup, null));

        GeneratedDocumentItem polishedAdvanced = new GeneratedDocumentItem("제목", "title-slug-advanced", BODY + "\n", List.of());
        GeneratedDocumentFile polished = DocumentBatch.applyPolish(out, deduped, new BothEditions.Pair<>(
                new PolishOutcome(deduped.document(), false, "보존 대조 실패"),
                new PolishOutcome(polishedAdvanced, true, null)));

        assertThat(polished.originalDocument().contentMd()).isEqualTo(BODY);
        assertThat(polished.originalAdvancedDocument().contentMd()).isEqualTo(BODY);
    }

    @Test
    @DisplayName("중복 제거를 안 한 파일에는 기록 칸을 쓰지 않는다")
    void omitsRemovalsWhenNotApplied() throws Exception {
        GeneratedDocumentFile file = new GeneratedDocumentFile("테스트", "2026-11-22", "2026-11-22T00:00:00Z",
                TestDomains.NETWORK, "m", item(BODY), null);

        String json = FileFormats.toJson((ObjectMapper) JsonMappers.STRICT, FileFormats.Kind.DOCUMENTS, file);

        assertThat(json).doesNotContain("dedupRemovals");
    }
}
