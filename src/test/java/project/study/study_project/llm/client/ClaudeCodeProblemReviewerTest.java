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
    @DisplayName("모르고 풀기 요청에는 정답 표시·해설·오답 이유·실행 결과가 없고, 보기는 섞인다")
    void solvePromptHidesAnswer() {
        List<Target> targets = List.of(new Target(ITEM, "- 1번(정답)을 넣으면: 출력 「같다」"));
        List<List<Integer>> orders = ClaudeCodeProblemReviewer.ordersOf(targets);

        String prompt = ClaudeCodeProblemReviewer.buildSolvePrompt(targets, orders);

        assertThat(prompt).contains("[문제 1] 유형: 빈칸 코드", "  3 |         if (____) {", "s1.equals(s2)")
                .doesNotContain("정답", "오답 이유", "해설", "실행 결과", "오해했다", "초급");
        assertThat(orders.get(0)).containsExactlyInAnyOrder(0, 1, 2, 3);
        assertThat(ClaudeCodeProblemReviewer.ordersOf(targets)).as("같은 문제는 늘 같은 순서").isEqualTo(orders);
    }

    @Test
    @DisplayName("모르고 푼 답을 원래 번호로 되돌려 지적한다 — 틀리게 골랐으면 'AI 오답', 맞혔으면 풀이 의견")
    void mapsSolveBackToOriginalNumbers() {
        List<Target> targets = List.of(new Target(ITEM, ""), new Target(ITEM, ""));
        // 보인 순서: 1번 자리에 원래 2번(s1 == s2), 2번 자리에 원래 1번(정답)
        List<List<Integer>> orders = List.of(List.of(1, 0, 2, 3), List.of(1, 0, 2, 3));
        var batch = new ClaudeCodeProblemReviewer.SolveBatch(List.of(
                new ClaudeCodeProblemReviewer.Solve(1, 1, "1번은 참조를 비교한다고 봤다", "3행의 조건이 두 뜻으로 읽힌다",
                        Difficulty.ADVANCED, "직관과 반대다"),
                new ClaudeCodeProblemReviewer.Solve(2, 2, "2번이 내용을 비교한다", "", Difficulty.INTERMEDIATE, "")));

        List<Finding> findings = ClaudeCodeProblemReviewer.solveFindings(targets, orders, batch);

        assertThat(findings).extracting(Finding::problemIndex, Finding::type).containsExactly(
                tuple(0, FindingType.MISSOLVED), tuple(0, FindingType.AMBIGUOUS_QUESTION),
                tuple(0, FindingType.DIFFICULTY_MISMATCH), tuple(1, FindingType.SOLUTION));
        assertThat(findings.get(0).detail()).isEqualTo("AI가 고른 보기: 2번 「s1 == s2」\n푼 과정: 2번은 참조를 비교한다고 봤다");
        assertThat(findings.get(2).detail()).startsWith("매긴 급: 초급 → 느낀 급: 고급");
        assertThat(findings.get(3).detail()).isEqualTo("1번이 내용을 비교한다");
        assertThat(findings.get(3).type().grade()).isEqualTo(ProblemReview.Grade.OPINION);
    }

    @Test
    @DisplayName("모르고 풀기가 실패해도 대조 검수 지적은 낸다")
    void judgeRunsEvenIfSolveFails() {
        ClaudeCodeProblemReviewer reviewer = new ClaudeCodeProblemReviewer(
                prompt -> new JudgementBatch(List.of(new Judgement(1, "어긋남", "", List.of(), "", "", 0, "", "", "", ""))),
                prompt -> {
                    throw new IllegalStateException("API 오류");
                });

        assertThat(reviewer.review("주제", List.of(new Target(ITEM, "")))).singleElement()
                .satisfies(f -> assertThat(f.type()).isEqualTo(FindingType.EXPLANATION_MISMATCH));
    }

    @Test
    @DisplayName("생성 프롬프트의 난이도 절은 상수로 뺀 뒤에도 글자가 그대로다")
    void generatorPromptKeepsDifficultySection() {
        assertThat(ClaudeCodeProblemGenerator.SYSTEM_PROMPT).contains("""
                [난이도]
                - BEGINNER: 규칙 하나만 알면 푼다.
                - INTERMEDIATE: 규칙 둘이 한 코드에서 얽힌다.
                - ADVANCED: 직관과 반대로 나오는 자리다. 왜 그런지 설명할 수 있어야 맞힌다.

                [보기와 해설]""");
        assertThat(ClaudeCodeProblemReviewer.SOLVE_PROMPT).contains("- ADVANCED: 직관과 반대로 나오는 자리다.");
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
