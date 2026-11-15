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
    @DisplayName("요청 설명을 꺼낸다")
    void extractReadsDescriptions() {
        assertThat(FigureRequests.extract(BODY)).containsExactly("봉투가 바뀐다");
    }
}
