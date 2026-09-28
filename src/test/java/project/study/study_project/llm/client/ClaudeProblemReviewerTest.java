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
    @DisplayName("중급 라벨에 지문 조건이 있으면, 급이 중급으로 나와도 따로 지적한다")
    void intermediateWithConditionIsFlagged() {
        GeneratedProblemItem item = new GeneratedProblemItem(SCENE, "", "해설", List.of(
                new GeneratedChoice("살균기", true), new GeneratedChoice("정규식", false),
                new GeneratedChoice("innerHTML", false), new GeneratedChoice("이스케이프", false)));
        var shown = ClaudeProblemReviewer.showable(List.of(item));
        Judgement j = new Judgement(1, "이유", shown.get(0).correctNo(), 0, "", "스키마는 표의 모양과 배치를 가리킨다.", "",
                false, "요건", "원본은 훼손 없이 남아야 한다", "셋 다 오해", 0);

        List<Finding> findings = ClaudeProblemReviewer.compare(shown, null, new JudgementBatch(List.of(j)),
                Difficulty.INTERMEDIATE, DOC);

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.type()).isEqualTo(FindingType.DIFFICULTY_MISMATCH);
            assertThat(f.message()).contains("조건 없이 묻는다", "원본은 훼손 없이");
        });
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
            assertThat(f.message()).contains("중급", "고급", "원본은 훼손 없이", "2개");
        });
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
