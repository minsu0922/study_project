package project.study.study_project.llm.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.llm.client.FactCheckFinding;
import project.study.study_project.llm.client.FactCheckFinding.Confidence;
import project.study.study_project.llm.client.FactCheckFinding.Kind;
import project.study.study_project.llm.client.GeneratedProblemItem;
import project.study.study_project.llm.client.ProblemReview.Finding;
import project.study.study_project.llm.client.ProblemReview.FindingType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 문서 지적과 문제의 근거 인용을 잇는 규칙 — Claude를 부르지 않는다. */
class FlaggedSourceRuleTest {

    private static final String DOC = """
            # Redis 영속화

            ## 무엇인가
            RDB는 스냅샷을 파일로 남긴다. 적재가 끝날 때까지 **접속이 되지 않는다.**

            ## 실제로는 어디에서 만나는가
            AOF는 쓰기 명령을 차례로 덧붙인다.
            """;

    private static final FactCheckFinding LOADING = new FactCheckFinding(
            "적재가 끝날 때까지 접속이 되지 않는다.", Kind.FACT_ERROR,
            "적재 중에도 접속은 된다", "명령에 LOADING 에러를 돌려준다", Confidence.HIGH);

    private static GeneratedProblemItem quoting(String sourceQuote) {
        return new GeneratedProblemItem("질문", "", "해설", List.of(), sourceQuote);
    }

    @Test
    @DisplayName("근거 문장이 지적된 문장과 같은 문단에 있으면 잇고, 이유와 바로잡은 내용을 근거로 싣는다")
    void linksProblemQuotingFlaggedParagraph() {
        List<Finding> findings = FlaggedSourceRule.link(
                List.of(quoting("AOF는 쓰기 명령을 차례로 덧붙인다."), quoting("RDB는 스냅샷을 파일로 남긴다.")),
                DOC, List.of(LOADING));

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.problemIndex()).isEqualTo(1);
            assertThat(f.type()).isEqualTo(FindingType.SOURCE_FLAGGED);
            assertThat(f.detail()).isEqualTo("""
                    지적된 문장: 「적재가 끝날 때까지 접속이 되지 않는다.」
                    이유: 적재 중에도 접속은 된다
                    바로잡으면: 명령에 LOADING 에러를 돌려준다""");
        });
    }

    @Test
    @DisplayName("정의 없는 용어 지적은 잇지 않는다 — 문제의 정답을 틀리게 하지 않는다")
    void ignoresUndefinedTerm() {
        var undefined = new FactCheckFinding("RDB는 스냅샷을 파일로 남긴다.", Kind.UNDEFINED_TERM,
                "스냅샷을 풀지 않았다", "스냅샷: 한 시점의 사본", Confidence.LOW);

        assertThat(FlaggedSourceRule.link(List.of(quoting("RDB는 스냅샷을 파일로 남긴다.")), DOC, List.of(undefined)))
                .isEmpty();
    }

    @Test
    @DisplayName("지적된 문장이 본문에 더 없으면 잇지 않는다 — 지적을 보고 문서를 고친 뒤다")
    void ignoresFindingWhoseQuoteIsGone() {
        String fixed = DOC.replace("적재가 끝날 때까지 **접속이 되지 않는다.**", "적재 중에는 LOADING 에러를 돌려준다.");

        assertThat(FlaggedSourceRule.link(List.of(quoting("RDB는 스냅샷을 파일로 남긴다.")), fixed, List.of(LOADING)))
                .isEmpty();
    }

    @Test
    @DisplayName("지적이 없는 문서와 근거 인용이 빈 문제는 그냥 지나간다")
    void handlesMissingInputs() {
        assertThat(FlaggedSourceRule.link(List.of(quoting("RDB는 스냅샷을 파일로 남긴다.")), DOC, null)).isEmpty();
        assertThat(FlaggedSourceRule.link(List.of(quoting("")), DOC, List.of(LOADING))).isEmpty();
    }

    @Test
    @DisplayName("깨진 '바로잡으면'은 근거에 싣지 않는다")
    void dropsGarbledCorrection() {
        var garbled = new FactCheckFinding(LOADING.quote(), Kind.FACT_ERROR, "적재 중에도 접속은 된다",
                "\"\ta\ta \ttb\\\"\" \ty\t\" \t\t\t\t — \ty\t\t\tb", Confidence.HIGH);

        assertThat(FlaggedSourceRule.link(List.of(quoting("RDB는 스냅샷을 파일로 남긴다.")), DOC, List.of(garbled)))
                .singleElement().satisfies(f -> assertThat(f.detail()).doesNotContain("바로잡으면"));
        assertThat(FlaggedSourceRule.isGarbled("명령에 LOADING 에러를 돌려준다")).isFalse();
    }
}
