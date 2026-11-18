package project.study.study_project.llm.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 윤문 평가 보고서 — 켜기 전에 볼 세 숫자(채택률, 형식 경고, 사실 지적)가 보이는지. */
class PolishEvalCliTest {

    private static final PolishEvalCli.Row APPLIED =
            new PolishEvalCli.Row("2026-09-03.json", "입문편", true, null, 3, 2, 4, 3);
    private static final PolishEvalCli.Row KEPT =
            new PolishEvalCli.Row("2026-09-03.json", "심화편", false, "수치가 다릅니다: [300]", 5, 5, 6, 6);

    @Test
    @DisplayName("편마다 한 줄, 끝에 채택 수와 경고·지적 합계 전후를 쓴다")
    void rendersRowsAndTotals() {
        String report = PolishEvalCli.render(List.of(APPLIED, KEPT));

        assertThat(report).contains("| 2026-09-03.json | 입문편 | 채택 |");
        assertThat(report).contains("| 2026-09-03.json | 심화편 | 원본 유지 — 수치가 다릅니다: [300] |");
        assertThat(report).contains("채택 1/2").contains("형식 경고 8 → 7").contains("사실 지적 10 → 9");
    }

    @Test
    @DisplayName("사실 검수를 건너뛴 행은 사실 칸을 '-'로 쓰고 합계에서도 뺀다")
    void skippedFactsShowDash() {
        PolishEvalCli.Row noFacts = new PolishEvalCli.Row("2026-09-11.json", "입문편", true, null, 1, 1, null, null);

        String report = PolishEvalCli.render(List.of(noFacts));

        assertThat(report).contains("| 1 | 1 | - | - |").contains("사실 지적 건너뜀");
    }
}
