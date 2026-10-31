package project.study.study_project.llm.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.llm.client.GeneratedProblemItem;
import project.study.study_project.llm.client.QuestionKind;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 차트 자료 검사. 여기서 막는 결함 가운데 둘(값 개수 어긋남, 천 단위 쉼표)은 mermaid가
 * 오류 없이 틀린 차트를 그리는 것을 2026-10-31에 브라우저로 확인했다.
 */
class ChartExhibitRuleTest {

    /** 생성 프롬프트 [차트 자료] 절의 본보기. 본보기가 검사에 걸리면 모델도 같은 꼴로 틀린다. */
    private static final String PROMPT_EXAMPLE = """
            xychart-beta
              title "주문 API 응답 시간"
              x-axis ["12:00", "12:05", "12:10", "12:15"]
              y-axis "ms" 0 --> 3000
              line [180, 210, 1900, 2700]
            """;

    private static GeneratedProblemItem withExhibit(String exhibit) {
        return new GeneratedProblemItem("지문", exhibit, "", "해설", List.of(
                new GeneratedProblemItem.GeneratedChoice("가", true),
                new GeneratedProblemItem.GeneratedChoice("나", false),
                new GeneratedProblemItem.GeneratedChoice("다", false),
                new GeneratedProblemItem.GeneratedChoice("라", false)),
                "", "제목", QuestionKind.CAUSE, "");
    }

    @Test
    @DisplayName("프롬프트 본보기 차트는 프롬프트에 실제로 있고, 검사를 통과하고, 그림(mermaid)으로 저장된다")
    void promptExamplePasses() throws Exception {
        try (var in = getClass().getResourceAsStream("/prompts/problem/system.txt")) {
            assertThat(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                    .contains("line [180, 210, 1900, 2700]");
        }
        ChartExhibitRule.Chart chart = ChartExhibitRule.parse(PROMPT_EXAMPLE.strip());

        assertThat(chart.problems()).isEmpty();
        assertThat(chart.categories()).hasSize(4);
        assertThat(chart.points()).isEqualTo(4);
        assertThat(withExhibit(PROMPT_EXAMPLE).exhibitLanguage()).isEqualTo("mermaid");
    }

    @Test
    @DisplayName("값 개수가 가로축 칸 수와 다르면 잡는다 — 화면은 오류 없이 빈칸을 그린다")
    void flagsCountMismatch() {
        var chart = ChartExhibitRule.parse("xychart-beta\n x-axis [\"a\", \"b\", \"c\"]\n line [1, 2]");

        assertThat(chart.problems()).anySatisfy(p -> assertThat(p).contains("값이 2개인데 가로축 칸은 3개"));
    }

    @Test
    @DisplayName("천 단위 쉼표는 값을 쪼개 개수가 어긋난다")
    void thousandsSeparatorBreaksCount() {
        var chart = ChartExhibitRule.parse("xychart-beta\n x-axis [\"a\", \"b\"]\n line [1,000, 2]");

        assertThat(chart.problems()).anySatisfy(p -> assertThat(p).contains("값이 3개인데 가로축 칸은 2개"));
    }

    @Test
    @DisplayName("따옴표 없는 가로축 라벨과 제목은 차트를 깨뜨린다")
    void flagsUnquotedLabels() {
        var chart = ChartExhibitRule.parse("xychart-beta\n title 응답 시간\n x-axis [12:00, 12:05]\n line [1, 2]");

        assertThat(chart.problems()).anySatisfy(p -> assertThat(p).contains("제목은 따옴표"));
        assertThat(chart.problems()).anySatisfy(p -> assertThat(p).contains("가로축 라벨은 하나하나 따옴표"));
    }

    @Test
    @DisplayName("세로축 범위 밖 값, 숫자 아닌 값, 모르는 줄을 잡는다")
    void flagsRangeAndJunk() {
        var chart = ChartExhibitRule.parse("""
                xychart-beta
                  x-axis ["a", "b"]
                  y-axis "ms" 0 --> 100
                  line [50, 150]
                  bar [1, 2ms]
                  legend 응답
                """);

        assertThat(chart.problems()).anySatisfy(p -> assertThat(p).contains("선 값 150가 세로축 범위 0~100 밖"));
        assertThat(chart.problems()).anySatisfy(p -> assertThat(p).contains("숫자만"));
        assertThat(chart.problems()).anySatisfy(p -> assertThat(p).contains("차트 문법으로 읽히지 않는다"));
    }

    @Test
    @DisplayName("계열 둘은 막대 하나·선 하나여야 한다 — 범례가 없어 선 둘은 구분이 안 된다")
    void seriesMustDifferInKind() {
        var twoLines = ChartExhibitRule.parse("xychart-beta\n x-axis [\"a\", \"b\"]\n line [1, 2]\n line [3, 4]");
        var barAndLine = ChartExhibitRule.parse("xychart-beta\n x-axis [\"a\", \"b\"]\n bar [1, 2]\n line [3, 4]");

        assertThat(twoLines.problems()).anySatisfy(p -> assertThat(p).contains("같은 종류 계열이 둘"));
        assertThat(barAndLine.problems()).isEmpty();
    }

    @Test
    @DisplayName("초·중급 차트는 점 5개·계열 1개, 고급은 점 8개까지다")
    void sizeLimitsByDifficulty() {
        String sixPoints = "xychart-beta\n x-axis [\"1\", \"2\", \"3\", \"4\", \"5\", \"6\"]\n bar [1, 2, 3, 4, 5, 6]\n line [1, 1, 1, 1, 1, 1]";
        GeneratedProblemItem item = withExhibit(sixPoints);

        assertThat(ProblemItemRule.qualityWarningsOf(item, Difficulty.INTERMEDIATE, true))
                .anySatisfy(w -> assertThat(w).contains("차트 점이 많음 (6개, 기준 5개"))
                .anySatisfy(w -> assertThat(w).contains("중급 차트에 계열이 2개"));
        assertThat(ProblemItemRule.qualityWarningsOf(item, Difficulty.ADVANCED, true))
                .noneSatisfy(w -> assertThat(w).contains("차트"));

        String ninePoints = "xychart-beta\n x-axis [\"1\", \"2\", \"3\", \"4\", \"5\", \"6\", \"7\", \"8\", \"9\"]\n"
                + " line [1, 2, 3, 4, 5, 6, 7, 8, 9]";
        assertThat(ProblemItemRule.qualityWarningsOf(withExhibit(ninePoints), Difficulty.ADVANCED, true))
                .anySatisfy(w -> assertThat(w).contains("차트 점이 많음 (9개, 기준 8개"));
    }
}
