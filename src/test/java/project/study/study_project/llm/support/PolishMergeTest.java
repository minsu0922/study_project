package project.study.study_project.llm.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** 윤문본을 본문 덩어리마다 받으려고 문서를 경계 줄로 나누고 다시 잇는다(2026-11-26). */
class PolishMergeTest {

    private static final String DOC = """
            # 이름 풀이

            > **글의 주제**
            >
            > 주소창에 이름을 치면 쇼핑몰이 뜬다.
            ## 무엇인가

            리졸버는 답을 300초 동안 저장한다.

            ```bash
            # 주석은 소제목이 아니다
            nslookup shop.example.com
            ```
            > **결론 문장이다.**

            ## 용어 한눈에

            | 용어 | 뜻 |
            |---|---|
            | 리졸버 | 대신 묻는 서버 |
            """;

    @Test
    @DisplayName("나눈 뒤 원고 덩어리로만 다시 이으면 글자 하나 다르지 않다 — 경계 줄이 붙어 있어도 빈 줄이 생기지 않는다")
    void roundTripIsExact() {
        PolishMerge.Parts parts = PolishMerge.split(DOC);
        boolean[] none = new boolean[parts.prose().size()];

        assertThat(PolishMerge.assemble(parts, parts, none)).isEqualTo(DOC);
        assertThat(parts.prose()).hasSize(parts.anchors().size() + 1);
    }

    @Test
    @DisplayName("코드 블록 안의 # 줄은 소제목으로 보지 않고, 붙은 인용 줄은 한 덩어리다")
    void fenceAndQuoteAreSingleAnchors() {
        PolishMerge.Parts parts = PolishMerge.split(DOC);

        assertThat(parts.anchors()).extracting(a -> a.get(0)).containsExactly(
                "# 이름 풀이", "> **글의 주제**", "## 무엇인가", "```bash", "> **결론 문장이다.**", "## 용어 한눈에");
        assertThat(parts.anchors().get(1)).hasSize(3);
    }

    @Test
    @DisplayName("덩어리마다 윤문본과 원고를 골라 잇는다")
    void assemblesChosenProse() {
        String polished = DOC.replace("답을 300초 동안 저장한다.", "받은 답을 300초 동안 저장한다.")
                .replace("| 리졸버 | 대신 묻는 서버 |", "| 리졸버 | 대신 물어 주는 서버 |");
        PolishMerge.Parts o = PolishMerge.split(DOC);
        PolishMerge.Parts p = PolishMerge.split(polished);
        boolean[] use = new boolean[o.prose().size()];
        use[3] = true; // 「## 무엇인가」 아래

        String merged = PolishMerge.assemble(o, p, use);

        assertThat(PolishMerge.sameAnchors(o, p)).isTrue();
        assertThat(merged).contains("받은 답을 300초").contains("| 리졸버 | 대신 묻는 서버 |");
    }

    @Test
    @DisplayName("덩어리 대조는 위 소제목을 보고 「용어 한눈에」 표의 용어도 잡는다")
    void proseCheckSeesGlossaryHeading() {
        PolishMerge.Parts o = PolishMerge.split(DOC);
        PolishMerge.Parts p = PolishMerge.split(DOC.replace("| 리졸버 |", "| 해석기 |"));
        int last = o.prose().size() - 1;

        assertThat(PolishMerge.headingAbove(o, last)).isEqualTo("## 용어 한눈에");
        assertThat(PolishMerge.proseDifferences(o, p, last)).anySatisfy(d -> assertThat(d).contains("용어 한눈에"));
    }

    @Test
    @DisplayName("소제목이 바뀌면 경계 줄이 다르다")
    void changedHeadingBreaksAnchors() {
        assertThat(PolishMerge.sameAnchors(PolishMerge.split(DOC),
                PolishMerge.split(DOC.replace("## 무엇인가", "## 정의")))).isFalse();
        assertThat(Arrays.asList(PolishMerge.split("").prose().get(0).toArray())).containsExactly("");
    }
}
