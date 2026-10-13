package project.study.study_project.llm.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.llm.client.GeneratedProblemItem.GeneratedChoice;
import project.study.study_project.llm.client.ProblemReview.ChoiceOnlyAnswer;
import project.study.study_project.llm.client.ProblemReview.ChoiceOnlyBatch;
import project.study.study_project.llm.client.ProblemReview.Finding;
import project.study.study_project.llm.client.ProblemReview.FindingType;
import project.study.study_project.llm.client.ProblemReview.Judgement;
import project.study.study_project.llm.client.ProblemReview.JudgementBatch;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 문제 검수기의 대조 로직 테스트 — Claude를 부르지 않는다.
 * 번호를 섞어 보여 주므로, 화면 번호와 원래 보기를 잘못 짝지으면 멀쩡한 문제가 전부 "정답 의심"이 된다.
 */
class ClaudeProblemReviewerTest {

    private static final String DOC = "스키마는 표의 모양과 배치를 가리킨다.";

    private static GeneratedProblemItem mc(String question) {
        return new GeneratedProblemItem(question, "", "해설", List.of(
                new GeneratedChoice("스키마", true),
                new GeneratedChoice("테이블", false),
                new GeneratedChoice("컬럼", false),
                new GeneratedChoice("행", false)));
    }

    /** 정답 번호를 맞힌 판정 — 여기서 한 칸만 바꿔 각 지적을 만든다. */
    private static Judgement fine(int no, int correctNo) {
        return new Judgement(no, "이유", correctNo, 0, "", "스키마는 표의 모양과 배치를 가리킨다.", "",
                true, "", "", "없음", 0);
    }

    @Test
    @DisplayName("보기 4개·정답 1개인 객관식만 검수하고, 같은 문제는 늘 같은 순서로 섞는다")
    void showsOnlyWellFormedMultipleChoice() {
        GeneratedProblemItem ox = new GeneratedProblemItem("OX 문제", "O", "해설", List.of());
        List<ClaudeProblemReviewer.Shown> shown = ClaudeProblemReviewer.showable(List.of(ox, mc("스키마란?")));

        assertThat(shown).hasSize(1);
        assertThat(shown.get(0).index()).isEqualTo(1);
        assertThat(shown.get(0).order()).containsExactlyInAnyOrder(0, 1, 2, 3);
        assertThat(ClaudeProblemReviewer.showable(List.of(mc("스키마란?"))).get(0).order())
                .isEqualTo(shown.get(0).order());
        assertThat(shown.get(0).choiceText(shown.get(0).correctNo())).isEqualTo("스키마");
    }

    @Test
    @DisplayName("정답을 맞히고 근거가 문서에 있고 난이도가 같으면 지적이 없다")
    void noFindingsWhenAllAgree() {
        var shown = ClaudeProblemReviewer.showable(List.of(mc("스키마란?")));
        int correct = shown.get(0).correctNo();

        List<Finding> findings = ClaudeProblemReviewer.compare(shown,
                new ChoiceOnlyBatch(List.of(new ChoiceOnlyAnswer(1, "", 0))),
                new JudgementBatch(List.of(fine(1, correct))), Difficulty.BEGINNER, DOC);

        assertThat(findings).isEmpty();
    }

    @Test
    @DisplayName("각 어긋남을 해당 지적으로 바꾼다")
    void mapsEachDisagreementToFinding() {
        var shown = ClaudeProblemReviewer.showable(List.of(mc("스키마란?"), mc("스키마는?"), mc("스키마의 뜻은?")));
        int c0 = shown.get(0).correctNo();
        int c1 = shown.get(1).correctNo();
        int c2 = shown.get(2).correctNo();
        int wrong0 = c0 == 1 ? 2 : 1;
        int other1 = c1 == 1 ? 2 : 1;

        List<Finding> findings = ClaudeProblemReviewer.compare(shown,
                new ChoiceOnlyBatch(List.of(new ChoiceOnlyAnswer(3, "하나만 짧다", c2))),
                new JudgementBatch(List.of(
                        new Judgement(1, "이유", wrong0, 0, "", "", "", true, "", "", "없음", 0),
                        new Judgement(2, "이유", c1, other1, "둘 다 맞다", "지어낸 문장", "되받음", false, "", "", "없음", 0),
                        fine(3, c2))),
                Difficulty.BEGINNER, DOC);

        assertThat(findings).extracting(Finding::problemIndex, Finding::type).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple(0, FindingType.ANSWER_MISMATCH),
                org.assertj.core.groups.Tuple.tuple(0, FindingType.NO_SUPPORT),
                org.assertj.core.groups.Tuple.tuple(1, FindingType.OTHER_CORRECT),
                org.assertj.core.groups.Tuple.tuple(1, FindingType.QUESTION_REVEALS),
                org.assertj.core.groups.Tuple.tuple(1, FindingType.NO_SUPPORT),
                org.assertj.core.groups.Tuple.tuple(1, FindingType.DIFFICULTY_MISMATCH),
                org.assertj.core.groups.Tuple.tuple(2, FindingType.CHOICE_CUE_LEAK));
    }

    @Test
    @DisplayName("보기만 보고 정답을 골라도 단서를 대지 못하면 누설로 치지 않는다 — 넷 중 하나는 우연히 맞는다")
    void luckyGuessWithoutCueIsNotLeak() {
        var shown = ClaudeProblemReviewer.showable(List.of(mc("스키마란?")));
        int correct = shown.get(0).correctNo();

        List<Finding> findings = ClaudeProblemReviewer.compare(shown,
                new ChoiceOnlyBatch(List.of(new ChoiceOnlyAnswer(1, "", correct))),
                new JudgementBatch(List.of(fine(1, correct))), Difficulty.BEGINNER, DOC);

        assertThat(findings).isEmpty();
    }

    private static GeneratedProblemItem withLengths(int answer, int o1, int o2, int o3) {
        return new GeneratedProblemItem("스키마란?", "", "해설", List.of(
                new GeneratedChoice("가".repeat(answer), true),
                new GeneratedChoice("나".repeat(o1), false),
                new GeneratedChoice("다".repeat(o2), false),
                new GeneratedChoice("라".repeat(o3), false)));
    }

    @Test
    @DisplayName("AI가 단서를 대도 정답이 오답 평균의 1.3배보다 짧으면 누설로 치지 않는다")
    void cueWithoutLengthGapIsNotLeak() {
        // 2026-10-10 초안의 실제 길이: 정답 53자, 오답 45·46·43자(1.19배) — "15분 수치를 담았다"는 지적이었다
        var shown = ClaudeProblemReviewer.showable(List.of(withLengths(53, 45, 46, 43)));
        int correct = shown.get(0).correctNo();

        List<Finding> findings = ClaudeProblemReviewer.compare(shown,
                new ChoiceOnlyBatch(List.of(new ChoiceOnlyAnswer(1, "하나만 구체적이다", correct))),
                new JudgementBatch(List.of(fine(1, correct))), Difficulty.BEGINNER, DOC);

        assertThat(findings).isEmpty();
    }

    @Test
    @DisplayName("길이 문턱은 오답 평균의 1.3배이고, 딱 1.3배는 지적한다")
    void lengthThresholdIsInclusive() {
        assertThat(ClaudeProblemReviewer.answerStandsOutByLength(withLengths(39, 30, 30, 30))).isTrue();
        assertThat(ClaudeProblemReviewer.answerStandsOutByLength(withLengths(38, 30, 30, 30))).isFalse();
        assertThat(ClaudeProblemReviewer.answerStandsOutByLength(withLengths(39, 10, 30, 50)))
                .as("가장 긴 오답이 정답보다 길어도 평균으로 잰다").isTrue();
    }

    /* ── 난이도: 관찰 셋으로 코드가 정한다 ── */

    private static final String SCENE = "사내 위키에서 사용자가 HTML 서식을 직접 써야 하고, 원본은 훼손 없이 남아야 한다. 이 조건에서 옳은 판단은?";

    private static Judgement observed(boolean asksDefinition, String conditionQuote, int elsewhereCount) {
        return new Judgement(1, "이유", 1, 0, "", "", "", asksDefinition, "조건", conditionQuote, "오답별 상황",
                elsewhereCount);
    }

    @Test
    @DisplayName("지문에 조건이 있고 다른 조건에서 맞는 오답이 둘 이상이면 고급이다")
    void conditionAndPlausibleDistractorsMeanAdvanced() {
        assertThat(ClaudeProblemReviewer.levelOf(SCENE, observed(false, "원본은 훼손 없이 남아야 한다", 2)))
                .isEqualTo(Difficulty.ADVANCED);
        assertThat(ClaudeProblemReviewer.levelOf(SCENE, observed(false, "원본은 훼손 없이 남아야 한다", 1)))
                .as("오답이 대부분 오해형이면 조건이 있어도 중급이다").isEqualTo(Difficulty.INTERMEDIATE);
    }

    @Test
    @DisplayName("조건 인용이 지문에 없으면 조건이 없는 것으로 본다 — 지어낸 조건으로 고급이 되지 않는다")
    void fabricatedConditionDoesNotCount() {
        assertThat(ClaudeProblemReviewer.levelOf(SCENE, observed(false, "초당 요청이 1만 건이다", 3)))
                .isEqualTo(Difficulty.INTERMEDIATE);
    }

    @Test
    @DisplayName("조건 없이 뜻만 물으면 초급, 조건 없이 원리를 물으면 중급이다")
    void noConditionSplitsBeginnerAndIntermediate() {
        assertThat(ClaudeProblemReviewer.levelOf("스키마란?", observed(true, "", 0))).isEqualTo(Difficulty.BEGINNER);
        assertThat(ClaudeProblemReviewer.levelOf("키를 지우도록 권하는 이유는?", observed(false, "", 3)))
                .isEqualTo(Difficulty.INTERMEDIATE);
    }

    @Test
    @DisplayName("8자 미만 조건 인용은 조건으로 치지 않는다")
    void shortConditionQuoteDoesNotCount() {
        assertThat(ClaudeProblemReviewer.levelOf(SCENE, observed(false, "이 조건에서", 3)))
                .isEqualTo(Difficulty.INTERMEDIATE);
    }

    @Test
    @DisplayName("다른 조건에서 맞는 오답 수는 0~3으로 자른다")
    void clampsElsewhereCount() {
        assertThat(ClaudeProblemReviewer.elsewhereCountOf(observed(false, "", 4))).isEqualTo(3);
        assertThat(ClaudeProblemReviewer.elsewhereCountOf(observed(false, "", -1))).isZero();
    }

    @Test
    @DisplayName("중급 지문에 조건만 있고 오답이 오해형이면 지적하지 않는다 — 배경 장면으로 시작하는 중급이 흔하다")
    void intermediateWithSceneOnlyIsNotFlagged() {
        GeneratedProblemItem item = new GeneratedProblemItem(SCENE, "", "해설", List.of(
                new GeneratedChoice("살균기", true), new GeneratedChoice("정규식", false),
                new GeneratedChoice("innerHTML", false), new GeneratedChoice("이스케이프", false)));
        var shown = ClaudeProblemReviewer.showable(List.of(item));
        Judgement j = new Judgement(1, "이유", shown.get(0).correctNo(), 0, "", "스키마는 표의 모양과 배치를 가리킨다.", "",
                false, "요건", "원본은 훼손 없이 남아야 한다", "셋 다 오해", 0);

        List<Finding> findings = ClaudeProblemReviewer.compare(shown, null, new JudgementBatch(List.of(j)),
                Difficulty.INTERMEDIATE, DOC);

        assertThat(findings).isEmpty();
    }

    @Test
    @DisplayName("난이도가 어긋나면 지적에 조건 인용과 오답 개수를 함께 적는다")
    void difficultyFindingCarriesObservations() {
        GeneratedProblemItem item = new GeneratedProblemItem(SCENE, "", "해설", List.of(
                new GeneratedChoice("살균기", true), new GeneratedChoice("정규식", false),
                new GeneratedChoice("innerHTML", false), new GeneratedChoice("이스케이프", false)));
        var shown = ClaudeProblemReviewer.showable(List.of(item));
        int correct = shown.get(0).correctNo();
        Judgement j = new Judgement(1, "이유", correct, 0, "", "스키마는 표의 모양과 배치를 가리킨다.", "",
                false, "요건", "원본은 훼손 없이 남아야 한다", "4번은 서식 요구가 없으면 맞다", 2);

        List<Finding> findings = ClaudeProblemReviewer.compare(shown, null, new JudgementBatch(List.of(j)),
                Difficulty.INTERMEDIATE, DOC);

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.type()).isEqualTo(FindingType.DIFFICULTY_MISMATCH);
            // 결론은 한 문장이고 조사가 맞아야 한다("중급로"로 나간 적이 있다)
            assertThat(f.message()).isEqualTo("중급으로 냈지만 고급으로 보입니다");
            assertThat(f.detail().lines()).hasSize(3);
            assertThat(f.detail()).contains("「원본은 훼손 없이 남아야 한다」", "2개", "서식 요구가 없으면 맞다");
        });
    }

    private static List<Finding> difficultyFindings(String question, Difficulty labeled, boolean asksDefinition,
                                                    String conditionQuote, int elsewhereCount) {
        GeneratedProblemItem item = new GeneratedProblemItem(question, "", "해설", List.of(
                new GeneratedChoice("살균기", true), new GeneratedChoice("정규식", false),
                new GeneratedChoice("innerHTML", false), new GeneratedChoice("이스케이프", false)));
        var shown = ClaudeProblemReviewer.showable(List.of(item));
        Judgement j = new Judgement(1, "이유", shown.get(0).correctNo(), 0, "", "스키마는 표의 모양과 배치를 가리킨다.", "",
                asksDefinition, "요건", conditionQuote, "오답별 상황", elsewhereCount);
        return ClaudeProblemReviewer.compare(shown, null, new JudgementBatch(List.of(j)), labeled, DOC);
    }

    @Test
    @DisplayName("물음 문장을 조건으로 옮겨 와 급이 올라간 경우, 조건이 아니라고 보면 낸 급과 같으니 지적하지 않는다")
    void questionSentenceAsConditionDoesNotPromote() {
        // 2026-10-10 #7325 — 중급으로 낸 문제를 물음 문장 때문에 고급으로 봤다
        assertThat(difficultyFindings(SCENE, Difficulty.INTERMEDIATE, false, "이 조건에서 옳은 판단은?", 3)).isEmpty();
    }

    @Test
    @DisplayName("물음 문장을 조건으로 쳐도 안 쳐도 낸 급과 다르면 그대로 지적한다")
    void questionSentenceStillFlaggedWhenBothReadingsDisagree() {
        assertThat(difficultyFindings(SCENE, Difficulty.ADVANCED, false, "이 조건에서 옳은 판단은?", 0))
                .singleElement().satisfies(f -> assertThat(f.message()).isEqualTo("고급으로 냈지만 중급으로 보입니다"));
    }

    @Test
    @DisplayName("AI가 든 조건 문장이 지문에 없어 버렸으면 그 문장을 근거에 남긴다")
    void droppedConditionQuoteIsShown() {
        assertThat(difficultyFindings(SCENE, Difficulty.ADVANCED, false, "초당 요청이 1만 건이다", 3))
                .singleElement().satisfies(f -> assertThat(f.detail().lines().findFirst().orElseThrow())
                        .isEqualTo("지문에 답을 가르는 조건: 없음 (AI가 든 문장 「초당 요청이 1만 건이다」은 지문에 그대로 없거나 너무 짧아 버림)"));
        assertThat(difficultyFindings("스키마란?", Difficulty.INTERMEDIATE, true, "", 0))
                .singleElement().satisfies(f -> assertThat(f.detail()).startsWith("지문에 답을 가르는 조건: 없음\n"));
    }

    /* ── 지적 문구: 결론과 근거, 보기 번호 ── */

    /**
     * AI는 섞인 순서로 보기를 본다. 그 번호를 그대로 내보내면 검수함에서 엉뚱한 보기를 가리킨다.
     */
    @Test
    @DisplayName("근거 속 보기 번호를 원래 보기 순서로 바꾼다")
    void rewritesChoiceNumbersToOriginalOrder() {
        var s = new ClaudeProblemReviewer.Shown(0, mc("스키마란?"), List.of(2, 0, 3, 1));

        assertThat(s.inOriginalNumbers("1번만 길고 4번은 2번과 겹친다")).isEqualTo("3번만 길고 2번은 1번과 겹친다");
        assertThat(s.inOriginalNumbers("12번 호출하고 5번은 그대로")).as("보기 번호가 아닌 것은 그대로 둔다")
                .isEqualTo("12번 호출하고 5번은 그대로");
        assertThat(s.inOriginalNumbers(null)).isEmpty();
    }

    @Test
    @DisplayName("보기 단서 누설은 결론을 고정 문장으로, AI가 든 단서를 근거로 나눠 적는다")
    void cueLeakSplitsConclusionAndDetail() {
        var shown = ClaudeProblemReviewer.showable(List.of(mc("스키마란?")));
        int correct = shown.get(0).correctNo();

        List<Finding> findings = ClaudeProblemReviewer.compare(shown,
                new ChoiceOnlyBatch(List.of(new ChoiceOnlyAnswer(1, correct + "번만 짧다", correct))),
                new JudgementBatch(List.of(fine(1, correct))), Difficulty.BEGINNER, DOC);

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.message()).isEqualTo("질문을 가리고 보기만 봐도 정답을 고를 수 있습니다");
            // mc()는 정답을 첫 보기에 둔다 — 섞인 번호가 무엇이든 원래 번호는 1번이다
            assertThat(f.detail()).isEqualTo("1번만 짧다");
        });
    }

    /* ── 의견: 지적과 따로 싣는다 ── */

    @Test
    @DisplayName("풀이·어색한 문장·가독성·총평을 의견 등급으로 싣고, 문제에 없는 문장을 어색하다고 하면 버린다")
    void opinionsCarrySolutionAndNotes() {
        var shown = ClaudeProblemReviewer.showable(List.of(mc("스키마란 무엇인가?"), mc("스키마의 뜻은?")));
        int c0 = shown.get(0).correctNo();
        int c1 = shown.get(1).correctNo();

        List<Finding> opinions = ClaudeProblemReviewer.opinionsOf(shown, new JudgementBatch(List.of(
                new Judgement(1, c0 + "번이 정의와 맞는다", c0, 0, "", "", "", true, "", "", "없음", 0,
                        "스키마란 무엇인가?", "스키마는 무엇을 가리키는가?", "", "정의를 아는지 가려낸다"),
                new Judgement(2, "이유", c1, 0, "", "", "", true, "", "", "없음", 0,
                        "문제에 없는 문장", "고친 문장", "보기가 짧아 막힘이 없다", ""))));

        assertThat(opinions).allSatisfy(f -> assertThat(f.type().grade()).isEqualTo(ProblemReview.Grade.OPINION));
        assertThat(opinions).extracting(Finding::problemIndex, Finding::type, Finding::detail).containsExactly(
                org.assertj.core.groups.Tuple.tuple(0, FindingType.SOLUTION, "1번이 정의와 맞는다"),
                org.assertj.core.groups.Tuple.tuple(0, FindingType.AWKWARD_SENTENCE,
                        "지금: 「스키마란 무엇인가?」\n고치면: 스키마는 무엇을 가리키는가?"),
                org.assertj.core.groups.Tuple.tuple(0, FindingType.OVERALL, "정의를 아는지 가려낸다"),
                org.assertj.core.groups.Tuple.tuple(1, FindingType.SOLUTION, "이유"),
                org.assertj.core.groups.Tuple.tuple(1, FindingType.READABILITY, "보기가 짧아 막힘이 없다"));
    }

    @Test
    @DisplayName("검수 AI가 다른 보기를 골랐으면 풀이를 의견으로 싣지 않는다 — 정답 의심의 근거에 이미 있다")
    void noSolutionOpinionWhenAnswerDiffers() {
        var shown = ClaudeProblemReviewer.showable(List.of(mc("스키마란?")));
        int wrong = shown.get(0).correctNo() == 1 ? 2 : 1;

        assertThat(ClaudeProblemReviewer.opinionsOf(shown, new JudgementBatch(List.of(fine(1, wrong))))).isEmpty();
    }

    /* ── 정답과 해설을 열어 본 뒤: 해설 검수와 고친 문장 ── */

    private static final GeneratedProblemItem LONG_ANSWER = new GeneratedProblemItem("풀이 설정을 길게 잡으면 아끼는 것은?", "",
            "연결을 재사용하면 핸드셰이크를 다시 하지 않는다.", List.of(
            new GeneratedChoice("가".repeat(60), true, "", null),
            new GeneratedChoice("나".repeat(30), false, "종료 비용도 줄어든다.", null),
            new GeneratedChoice("다".repeat(30), false, "", null),
            new GeneratedChoice("라".repeat(30), false, "", null)));

    private static Finding cueLeak() {
        return new Finding(0, FindingType.CHOICE_CUE_LEAK, "질문을 가리고 보기만 봐도 정답을 고를 수 있습니다", "1번만 길다");
    }

    @Test
    @DisplayName("다 푼 뒤 보여 주는 글은 보기를 원래 순서로 적고 정답·해설·검수자 풀이·지적을 싣는다")
    void afterSolvePromptShowsAnswerAndFlags() {
        var shown = ClaudeProblemReviewer.showable(List.of(LONG_ANSWER));
        int correct = shown.get(0).correctNo();

        String prompt = ClaudeProblemReviewer.buildAfterSolvePrompt(shown,
                new JudgementBatch(List.of(new Judgement(1, correct + "번이 맞다", correct, 0, "", "", "",
                        false, "", "", "없음", 0))), List.of(cueLeak()));

        assertThat(prompt).contains("1) " + "가".repeat(60) + "  ← 표시된 정답", "2) " + "나".repeat(30),
                "   오답 풀이: 종료 비용도 줄어든다.", "해설: 연결을 재사용하면", "검수자 풀이: 1번이 맞다",
                "지적: [보기 단서 누설] 1번만 길다");
    }

    @Test
    @DisplayName("해설이 어긋난다는 답은 결함 지적이 되고, 길이 차를 없애는 제안은 단서 누설 지적에 붙는다")
    void afterSolveAddsExplanationFindingAndSuggestion() {
        var shown = ClaudeProblemReviewer.showable(List.of(LONG_ANSWER));
        var judged = new JudgementBatch(List.of(fine(1, shown.get(0).correctNo())));

        List<Finding> findings = ClaudeProblemReviewer.applyAfterSolve(shown, judged, List.of(cueLeak()),
                new ProblemReview.AfterSolveBatch(List.of(new ProblemReview.AfterSolve(1,
                        "해설은 종료 비용이 그대로라고 하는데 오답 풀이는 줄어든다고 한다",
                        ProblemReview.FixTarget.CHOICE, 1, "가".repeat(32)))));

        assertThat(findings).hasSize(2);
        assertThat(findings.get(0).suggestion())
                .isEqualTo(new ProblemReview.Suggestion(ProblemReview.FixTarget.CHOICE, 0, "가".repeat(32)));
        assertThat(findings.get(1).type()).isEqualTo(FindingType.EXPLANATION_MISMATCH);
        assertThat(findings.get(1).type().grade()).isEqualTo(ProblemReview.Grade.DEFECT);
    }

    @Test
    @DisplayName("고친 뒤에도 정답이 1.3배 이상 길거나, 지적이 없는 문제의 제안은 버린다")
    void afterSolveDropsUselessSuggestions() {
        var shown = ClaudeProblemReviewer.showable(List.of(LONG_ANSWER));
        var judged = new JudgementBatch(List.of(fine(1, shown.get(0).correctNo())));
        var stillLong = new ProblemReview.AfterSolveBatch(List.of(new ProblemReview.AfterSolve(1, "",
                ProblemReview.FixTarget.CHOICE, 1, "가".repeat(50))));
        var onQuestion = new ProblemReview.AfterSolveBatch(List.of(new ProblemReview.AfterSolve(1, "",
                ProblemReview.FixTarget.QUESTION, 0, "고친 질문")));

        assertThat(ClaudeProblemReviewer.applyAfterSolve(shown, judged, List.of(cueLeak()), stillLong))
                .singleElement().satisfies(f -> assertThat(f.suggestion()).isNull());
        assertThat(ClaudeProblemReviewer.applyAfterSolve(shown, judged, List.of(cueLeak()), onQuestion))
                .as("질문 제안은 질문 누설 지적에만 붙는다")
                .singleElement().satisfies(f -> assertThat(f.suggestion()).isNull());
    }

    @Test
    @DisplayName("검수 AI가 다른 보기를 골랐으면 해설 어긋남을 따로 지적하지 않는다 — 기준으로 삼을 풀이가 없다")
    void noExplanationFindingWithoutTrustedSolution() {
        var shown = ClaudeProblemReviewer.showable(List.of(LONG_ANSWER));
        int wrong = shown.get(0).correctNo() == 1 ? 2 : 1;

        assertThat(ClaudeProblemReviewer.applyAfterSolve(shown, new JudgementBatch(List.of(fine(1, wrong))), List.of(),
                new ProblemReview.AfterSolveBatch(List.of(new ProblemReview.AfterSolve(1, "어긋난다",
                        ProblemReview.FixTarget.NONE, 0, ""))))).isEmpty();
    }

    @Test
    @DisplayName("제안 칸이 없는 옛 지적 JSON도 읽히고, 제안은 JSON으로 오간다")
    void findingJsonRoundTrip() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        Finding old = mapper.readValue(
                "{\"problemIndex\":1,\"type\":\"CHOICE_CUE_LEAK\",\"message\":\"m\",\"detail\":\"d\"}", Finding.class);
        Finding withFix = cueLeak().withSuggestion(
                new ProblemReview.Suggestion(ProblemReview.FixTarget.CHOICE, 0, "짧게"));

        assertThat(old.suggestion()).isNull();
        assertThat(mapper.readValue(mapper.writeValueAsString(withFix), Finding.class)).isEqualTo(withFix);
    }

    @Test
    @DisplayName("난이도 정의는 생성 프롬프트의 그 절만 잘라 온다")
    void slicesDifficultyDefinitionFromGeneratorPrompt() {
        String definition = ClaudeProblemReviewer.difficultyDefinition();

        assertThat(definition).startsWith("[난이도가 뜻하는 것").contains("초급은", "중급은", "고급은")
                .doesNotContain("[중급이 묻는 네 형태]");
        assertThat(ClaudeProblemReviewer.judgePrompt()).contains(definition);
    }
}
