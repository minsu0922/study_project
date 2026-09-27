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
import project.study.study_project.llm.client.ProblemReview.Level;

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
        return new Judgement(no, "이유", correctNo, 0, "", "스키마는 표의 모양과 배치를 가리킨다.", "", "용어 정의",
                Level.BEGINNER);
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
                        new Judgement(1, "이유", wrong0, 0, "", "", "", "이유", Level.BEGINNER),
                        new Judgement(2, "이유", c1, other1, "둘 다 맞다", "지어낸 문장", "되받음", "이유", Level.ADVANCED),
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

    @Test
    @DisplayName("난이도 정의는 생성 프롬프트의 그 절만 잘라 온다")
    void slicesDifficultyDefinitionFromGeneratorPrompt() {
        String definition = ClaudeProblemReviewer.difficultyDefinition();

        assertThat(definition).startsWith("[난이도가 뜻하는 것").contains("초급은", "중급은", "고급은")
                .doesNotContain("[중급이 묻는 네 형태]");
        assertThat(ClaudeProblemReviewer.judgePrompt()).contains(definition);
    }
}
