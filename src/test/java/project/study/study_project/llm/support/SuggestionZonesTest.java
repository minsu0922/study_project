package project.study.study_project.llm.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 제안으로 고치면 안 되는 자리 — 윤문의 「건드리지 않는 것」과 같다. */
class SuggestionZonesTest {

    private static final String MD = """
            # 제목

            ## 소제목

            본문 문장이다. 다음 문장이다.

            ```bash
            dig shop.example.com
            ```

            ![그림 설명](/images/docs/x.svg)

            > **결론 상자 문장이다.**

            | 용어 | 뜻 |
            |---|---|
            | 리졸버 | 대신 묻는 서버 |

            - **리졸버(resolver)** — 대신 묻는 서버다.
            """;

    private static boolean forbidden(String piece) {
        int at = MD.indexOf(piece);
        return SuggestionZones.forbidden(MD, at, at + piece.length());
    }

    @Test
    @DisplayName("본문 문장과 정의 줄의 줄표 뒤 설명은 고쳐도 된다")
    void plainSentenceAllowed() {
        assertThat(forbidden("본문 문장이다.")).isFalse();
        assertThat(forbidden("대신 묻는 서버다.")).isFalse();
    }

    @Test
    @DisplayName("소제목·제목 줄은 고치지 않는다")
    void headingForbidden() {
        assertThat(forbidden("소제목")).isTrue();
        assertThat(forbidden("제목")).isTrue();
    }

    @Test
    @DisplayName("코드 블록 안과 그림 줄은 고치지 않는다")
    void codeAndFigureForbidden() {
        assertThat(forbidden("dig shop.example.com")).isTrue();
        assertThat(forbidden("그림 설명")).isTrue();
    }

    @Test
    @DisplayName("인용 상자·표 줄은 고치지 않는다")
    void quoteAndTableForbidden() {
        assertThat(forbidden("결론 상자 문장이다.")).isTrue();
        assertThat(forbidden("대신 묻는 서버 |")).isTrue();
    }

    @Test
    @DisplayName("용어 정의 줄의 앞부분(줄표까지)은 고치지 않는다")
    void definitionHeadForbidden() {
        assertThat(forbidden("리졸버(resolver)")).isTrue();
    }
}
