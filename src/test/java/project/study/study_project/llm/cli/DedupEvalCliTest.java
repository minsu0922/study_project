package project.study.study_project.llm.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.llm.client.DedupProposal;
import project.study.study_project.llm.client.GeneratedDocumentItem;
import project.study.study_project.llm.dto.DedupRemoval;
import project.study.study_project.llm.support.DedupGuard;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 중복 제거 평가 보고서 — 켜기 전에 사람이 지운 문장과 재료 변화를 읽을 수 있는지. */
class DedupEvalCliTest {

    private static final String KEPT = "서비스는 할 일을 정한다.";
    private static final String DUP = "할 일을 정하는 쪽은 서비스다.";

    private static DedupEvalCli.Row applied() {
        return new DedupEvalCli.Row("2026-11-21.json", "입문편", true, null, 1000, 950, 2, 2, 17, 17, true,
                List.of(new DedupRemoval(DUP, KEPT, "본론", "같은 말")), List.of(new DedupGuard.Rejected("x", "다른 후보와 겹침")));
    }

    @Test
    @DisplayName("편마다 분량·경고·표 행·재료를 한 줄로 쓰고, 지운 문장을 사람이 읽게 남긴다")
    void rendersRowsAndRemovedSentences() {
        DedupEvalCli.Row kept = new DedupEvalCli.Row("2026-11-21.json", "심화편", false, "지울 | 문장 없음", 2000, 2000,
                1, 1, 16, 16, false, List.of(), List.of());

        String report = DedupEvalCli.render(List.of(applied(), kept));

        assertThat(report)
                .contains("| 2026-11-21.json | 입문편 | 채택 1건 | 1,000 → 950 | 5.0% | 2 → 2 | 17 → 17 | 같음 | 1 |")
                .contains("원본 유지 — 지울 \\| 문장 없음")
                .contains("**달라짐**")
                .contains("채택 1/2편, 지운 문장 1건")
                .contains("- 지움: " + DUP).contains("- 남김: " + KEPT)
                .contains("- 다른 후보와 겹침: 1건");
    }

    @Test
    @DisplayName("기본 표본은 이름순 마지막 파일이고, 파일이 적으면 있는 만큼만 본다")
    void picksLatestFiles() {
        List<Path> files = List.of(Path.of("2026-11-02.json"), Path.of("2026-11-21.json"), Path.of("2026-10-31.json"));

        assertThat(DedupEvalCli.latest(files, 2)).containsExactly(Path.of("2026-11-02.json"), Path.of("2026-11-21.json"));
        assertThat(DedupEvalCli.latest(files, 9)).hasSize(3);
        assertThatThrownBy(() -> DedupEvalCli.latest(files, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("한 편을 평가하면 전후 분량과 지운 문장이 행에 실린다")
    void evaluatesOneEdition() {
        String padding = "이 문단은 분량을 맞추려고 넣은 평범한 설명 문장이다. ".repeat(12).strip();
        String body = "# 제목\n\n> **글의 주제**\n>\n> 주제다.\n\n## 본론\n\n첫 문장이다. " + KEPT
                + " 둘째 문장이다. " + DUP + " 끝 문장이다.\n\n" + padding + "\n";
        GeneratedDocumentItem doc = new GeneratedDocumentItem("제목", "title-slug", body, List.of("tag"));

        DedupEvalCli.Row row = DedupEvalCli.evaluate("a.json", doc,
                (title, content, edition) -> List.of(new DedupProposal(DUP, KEPT, "같은 말")));

        assertThat(row.applied()).isTrue();
        assertThat(row.charsAfter()).isLessThan(row.charsBefore());
        assertThat(row.removals()).hasSize(1);
        assertThat(row.materialKept()).isTrue();
    }

    @Test
    @DisplayName("재료 절이 사라지면 달라진 것으로 잡는다")
    void detectsLostMaterial() {
        assertThat(DedupEvalCli.sameMaterial("## 바탕이 되는 개념\n내용", "## 바탕이 되는 개념\n짧은 내용")).isTrue();
        assertThat(DedupEvalCli.sameMaterial("## 바탕이 되는 개념\n내용", "내용")).isFalse();
    }
}
