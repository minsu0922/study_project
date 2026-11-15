package project.study.study_project.document.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FigureRequestsTest {

    private static final String BODY = """
            앞 문장.

            ```mermaid
            flowchart LR
              %% 손그림: 봉투가 바뀐다
              A["a"] --> B["b"]
            ```
            """;

    @Test
    @DisplayName("요청 줄만 지우고 그림의 나머지 줄과 앞뒤 글은 그대로 둔다")
    void stripRemovesOnlyRequestLines() {
        assertThat(FigureRequests.strip(BODY)).isEqualTo("""
                앞 문장.

                ```mermaid
                flowchart LR
                  A["a"] --> B["b"]
                ```
                """);
    }

    @Test
    @DisplayName("요청이 없거나 본문이 없으면 그대로 돌려준다")
    void stripLeavesOtherBodiesAlone() {
        assertThat(FigureRequests.strip("100% 손그림: 이라는 글")).isEqualTo("100% 손그림: 이라는 글");
        assertThat(FigureRequests.strip(null)).isNull();
    }

    @Test
    @DisplayName("문제 손그림은 우리 폴더의 SVG 한 줄만 받는다 — 바깥 주소는 그림으로 보지 않는다")
    void problemImagePathAcceptsOnlyOurFolder() {
        assertThat(FigureRequests.problemImagePathOf(" ![봉투](/images/problems/a-1.svg) "))
                .isEqualTo("/images/problems/a-1.svg");
        assertThat(FigureRequests.problemImagePathOf("![봉투](https://evil.example/a.svg)")).isNull();
        assertThat(FigureRequests.problemImagePathOf("![봉투](/images/docs/a.svg)")).isNull();
        assertThat(FigureRequests.problemImagePathOf("![봉투](/images/problems/a.svg)\n둘째 줄")).isNull();
    }

    @Test
    @DisplayName("손그림 글자 옮기기는 설명과 SVG의 text를 위에서부터 싣는다")
    void problemImageTextReadsSvgText() {
        assertThat(FigureRequests.problemImageTextOf("![봉투가 바뀐다](/images/problems/test-figure.svg)"))
                .isEqualTo("설명: 봉투가 바뀐다\n노트북\n공유기");
        assertThat(FigureRequests.problemImageTextOf("![없는 그림](/images/problems/no-such.svg)"))
                .as("파일을 못 읽어도 설명은 남긴다").isEqualTo("설명: 없는 그림");
        assertThat(FigureRequests.problemImageTextOf("flowchart LR")).isNull();
    }

    @Test
    @DisplayName("요청 설명을 꺼낸다")
    void extractReadsDescriptions() {
        assertThat(FigureRequests.extract(BODY)).containsExactly("봉투가 바뀐다");
    }
}
