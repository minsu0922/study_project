package project.study.study_project.llm.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.llm.client.ClaudeCodeProblemReviewer.Judgement;
import project.study.study_project.llm.client.ClaudeCodeProblemReviewer.JudgementBatch;
import project.study.study_project.llm.client.ClaudeCodeProblemReviewer.RationaleIssue;
import project.study.study_project.llm.client.ClaudeCodeProblemReviewer.Target;
import project.study.study_project.llm.client.CodeProblem.Choice;
import project.study.study_project.llm.client.CodeProblem.Item;
import project.study.study_project.llm.client.CodeProblem.Kind;
import project.study.study_project.llm.client.ProblemReview.Finding;
import project.study.study_project.llm.client.ProblemReview.FindingType;
import project.study.study_project.llm.client.ProblemReview.FixTarget;
import project.study.study_project.llm.client.ProblemReview.Suggestion;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/** 코드 문제 AI 검수 — 모델은 부르지 않는다. 요청 글의 모양과 답을 지적으로 옮기는 규칙을 본다. */
class ClaudeCodeProblemReviewerTest {

    private static final Item ITEM = new Item(Kind.CODE_BLANK, Difficulty.BEGINNER, "두 문자열의 내용 비교",
            "실행했을 때 \"같다\"가 출력되도록 빈칸에 들어갈 코드로 알맞은 것은?",
            "public class Main {\n    public static void main(String[] args) {\n        if (____) {\n        }\n    }\n}",
            "같다", 0, "",
            List.of(new Choice("s1.equals(s2)", true, ""),
                    new Choice("s1 == s2", false, "==이 내용을 비교한다고 오해했다. \"다르다\"가 출력된다."),
                    new Choice("s1 = s2", false, "대입식도 조건이 된다고 오해했다. 컴파일되지 않는다."),
                    new Choice("s1.compareTo(s2)", false, "boolean을 돌려준다고 오해했다. 컴파일되지 않는다.")),
            "3행에서 equals로 내용을 비교한다.");

    private static Judgement judgement(int no) {
        return new Judgement(no, "", "", List.of(), "", "", 0, "", "", "", "");
    }

    @Test
    @DisplayName("요청 글에 줄 번호가 붙은 코드, 정답 표시, 오답 이유, 실행 결과가 실린다")
    void promptCarriesCodeAnswerAndRunFacts() {
        String prompt = ClaudeCodeProblemReviewer.buildPrompt("==과 equals",
                List.of(new Target(ITEM, "- 1번(정답)을 넣으면: 출력 「같다」\n- 2번을 넣으면: 출력 「다르다」")));

        assertThat(prompt)
                .contains("주제: ==과 equals")
                .contains("[문제 1] 유형: 빈칸 코드")
                .contains("  3 |         if (____) {")
                .contains("1. (정답) s1.equals(s2)")
                .contains("   오답 이유: ==이 내용을 비교한다고 오해했다.")
                .contains("실행 결과:\n- 1번(정답)을 넣으면: 출력 「같다」");
    }

    @Test
    @DisplayName("모델의 답을 종류별 지적으로 옮기고, 결론 문장은 코드가 정한다")
    void mapsJudgementToFindings() {
        Judgement j = new Judgement(1, "해설의 3행은 비교가 아니라 if문이다", "3행의 if문에서 equals로 내용을 비교한다.",
                List.of(new RationaleIssue(3, "실제로는 컴파일된다", "대입식도 조건이 된다고 오해했다. 실제로는 컴파일된다."),
                        new RationaleIssue(4, "int를 돌려준다", "")),
                "캐시 상한은 JVM 옵션으로 바뀐다",
                "연산자 우선순위만 알면 풀린다", 4, "boolean이 아닌 꼴이라 바로 지워진다",
                "\"같다\"가 출력되도록", "\"같다\"를 출력하려면", "보기 2와 3이 한 글자 차이라 헷갈린다");

        List<Finding> findings = ClaudeCodeProblemReviewer.findingsOf(List.of(new Target(ITEM, "")),
                new JudgementBatch(List.of(j)));

        assertThat(findings).extracting(Finding::problemIndex, Finding::type, Finding::message).containsExactly(
                tuple(0, FindingType.EXPLANATION_MISMATCH, "해설이 실제 실행과 어긋납니다"),
                tuple(0, FindingType.RATIONALE_MISMATCH, "오답 이유가 실제 실행과 어긋납니다"),
                tuple(0, FindingType.RATIONALE_MISMATCH, "오답 이유가 실제 실행과 어긋납니다"),
                tuple(0, FindingType.ENVIRONMENT_DEPENDENT, "실행 환경에 따라 답이 바뀔 수 있습니다"),
                tuple(0, FindingType.OFF_TOPIC, "주제를 몰라도 풀리거나 다른 것이 답을 가릅니다"),
                tuple(0, FindingType.CHOICE_CUE_LEAK, "코드를 따라가지 않아도 지워지는 오답이 있습니다"),
                tuple(0, FindingType.AWKWARD_SENTENCE, "어색하거나 뜻이 흐린 문장이 있습니다"),
                tuple(0, FindingType.READABILITY, "읽다가 막히는 곳이 있습니다"));
        assertThat(findings.get(0).suggestion())
                .isEqualTo(new Suggestion(FixTarget.EXPLANATION, null, "3행의 if문에서 equals로 내용을 비교한다."));
        assertThat(findings.get(1).detail()).isEqualTo("3번: 실제로는 컴파일된다");
        assertThat(findings.get(1).suggestion())
                .isEqualTo(new Suggestion(FixTarget.RATIONALE, 2, "대입식도 조건이 된다고 오해했다. 실제로는 컴파일된다."));
        assertThat(findings.get(2).suggestion()).as("고친 글이 없으면 지적만 한다").isNull();
        assertThat(findings.get(5).detail()).isEqualTo("4번: boolean이 아닌 꼴이라 바로 지워진다");
        assertThat(findings.get(6).suggestion()).as("어색한 부분만 바꾼 질문 전체").isEqualTo(new Suggestion(FixTarget.QUESTION, null,
                "실행했을 때 \"같다\"를 출력하려면 빈칸에 들어갈 코드로 알맞은 것은?"));
    }

    @Test
    @DisplayName("어색한 문장 제안은 해설·오답 이유에도 붙지만, 보기 글(코드·출력)에는 붙지 않는다")
    void awkwardSuggestionNeverTouchesChoiceText() {
        assertThat(ClaudeCodeProblemReviewer.awkwardSuggestion(ITEM, "equals로 내용을", "equals로 문자열 내용을"))
                .isEqualTo(new Suggestion(FixTarget.EXPLANATION, null, "3행에서 equals로 문자열 내용을 비교한다."));
        assertThat(ClaudeCodeProblemReviewer.awkwardSuggestion(ITEM, "대입식도", "대입식까지"))
                .extracting(Suggestion::target, Suggestion::choiceIndex).containsExactly(FixTarget.RATIONALE, 2);
        assertThat(ClaudeCodeProblemReviewer.awkwardSuggestion(ITEM, "s1.compareTo(s2)", "s1.compareTo(s2) == 0")).isNull();
    }

    @Test
    @DisplayName("정답이나 없는 보기를 가리킨 지적, 문제에 없는 인용, 없는 문제 번호는 버린다")
    void dropsInvalidReferences() {
        Judgement j = new Judgement(1, "", "", List.of(new RationaleIssue(1, "정답을 오답이라 함", ""), new RationaleIssue(9, "없는 보기", "")),
                "", "", 1, "정답 번호", "문제에 없는 문장", "고친 문장", "");

        List<Finding> findings = ClaudeCodeProblemReviewer.findingsOf(List.of(new Target(ITEM, "")),
                new JudgementBatch(List.of(j, judgement(7))));

        assertThat(findings).isEmpty();
    }

    @Test
    @DisplayName("검수기는 모델 대신 넣은 답으로 돌아간다 — 문제가 없으면 부르지도 않는다")
    void callsCallerOnlyWithTargets() {
        int[] calls = {0};
        ClaudeCodeProblemReviewer reviewer = new ClaudeCodeProblemReviewer(prompt -> {
            calls[0]++;
            return new JudgementBatch(List.of(new Judgement(1, "어긋남", "", List.of(), "", "", 0, "", "", "", "")));
        });

        assertThat(reviewer.review("주제", List.of())).isEmpty();
        assertThat(calls[0]).isZero();
        assertThat(reviewer.review("주제", List.of(new Target(ITEM, "")))).singleElement()
                .satisfies(f -> assertThat(f.type()).isEqualTo(FindingType.EXPLANATION_MISMATCH));
    }
}
