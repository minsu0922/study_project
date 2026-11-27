package project.study.study_project.llm.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.llm.client.GeneratedProblemItem;
import project.study.study_project.llm.client.GeneratedProblemItem.GeneratedChoice;
import project.study.study_project.llm.client.SourceDocument;
import project.study.study_project.llm.support.TypeMix;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 초급 유형 섞기(2026-11-27) — 배치가 구성을 정하고, 점검·검수가 문제마다 유형을 보는지. */
class TypeMixBatchTest {

    private static final SourceDocument DOC = new SourceDocument("cloud", "클라우드", """
            # 클라우드

            ## 요금을 멈추는 방법

            1. 가
            2. 나
            3. 다
            4. 라

            ## 자주 하는 오해

            **"꺼 두면 0원이다."**
            """);

    private static GeneratedProblemItem choice(String question, String explanation) {
        return new GeneratedProblemItem(question, "", "", explanation,
                List.of(new GeneratedChoice("정답", true, ""), new GeneratedChoice("오답1", false, "틀림"),
                        new GeneratedChoice("오답2", false, "틀림"), new GeneratedChoice("오답3", false, "틀림")),
                "", "제목", null, "").withType(ProblemType.MULTIPLE_CHOICE);
    }

    private static GeneratedProblemItem ox(String question) {
        return new GeneratedProblemItem(question, "", "X", "해설입니다.", List.of(), "", "제목", null, "")
                .withType(ProblemType.OX);
    }

    @Test
    @DisplayName("지목 없는 초급·중급에 근거 문서가 있을 때만 섞는다")
    void mixOnlyForUnpinnedBeginnerWithSource() {
        assertThat(DraftGeneratorCli.mixFor(Difficulty.BEGINNER, false, DOC, 7).isMixed()).isTrue();
        assertThat(DraftGeneratorCli.mixFor(Difficulty.INTERMEDIATE, false, DOC, 5).summary())
                .isEqualTo("객관식 4 · 순서 배열 1");
        assertThat(DraftGeneratorCli.mixFor(Difficulty.ADVANCED, false, DOC, 3)).isNull();
        assertThat(DraftGeneratorCli.mixFor(Difficulty.INTERMEDIATE, true, DOC, 5)).isNull();
        assertThat(DraftGeneratorCli.mixFor(Difficulty.BEGINNER, true, DOC, 7)).isNull();
        assertThat(DraftGeneratorCli.mixFor(Difficulty.BEGINNER, false, null, 7)).isNull();
    }

    @Test
    @DisplayName("수확 점검은 파일 유형이 아니라 문제에 적힌 유형으로 판정한다")
    void yieldUsesItemType() {
        YieldReporter.YieldCheck check = YieldReporter.checkYield(List.of(ox("꺼 두면 0원이다.")), 1,
                ProblemType.MULTIPLE_CHOICE, Difficulty.BEGINNER);

        assertThat(check.defects()).isEmpty();
        assertThat(check.usable()).isEqualTo(1);
    }

    @Test
    @DisplayName("구성과 받은 유형이 다르면 한 줄로 알린다")
    void mismatchReported() {
        TypeMix mix = new TypeMix(Map.of(ProblemType.MULTIPLE_CHOICE, 1, ProblemType.MATCHING, 1, ProblemType.OX, 1),
                Map.of());

        assertThat(DraftGeneratorCli.mixMismatchOf(mix, List.of(choice("q1", "e"), ox("q2"))))
                .contains("짝짓기 요청 1 → 0");
        assertThat(DraftGeneratorCli.mixMismatchOf(
                new TypeMix(Map.of(ProblemType.MULTIPLE_CHOICE, 1, ProblemType.OX, 1), Map.of()),
                List.of(choice("q1", "e"), ox("q2")))).isNull();
    }

    @Test
    @DisplayName("AI 검수에는 객관식만 보내고, 고친 결과를 제자리에 되돌린다")
    void reviewOnlyChoices() {
        List<GeneratedProblemItem> kept = List.of(choice("q0", "옛 해설0"), ox("q1"), choice("q2", "옛 해설2"));

        DraftGeneratorCli.ReviewSplit split = DraftGeneratorCli.splitForReview(kept, ProblemType.MULTIPLE_CHOICE);
        assertThat(split.indexes()).containsExactly(0, 2);

        List<GeneratedProblemItem> reviewed = split.choices().stream()
                .map(p -> p.withExplanationAndChoices("새 " + p.explanation(), p.choices())).toList();
        List<GeneratedProblemItem> merged = DraftGeneratorCli.mergeReviewed(kept, split, reviewed);

        assertThat(merged).extracting(GeneratedProblemItem::explanation)
                .containsExactly("새 옛 해설0", "해설입니다.", "새 옛 해설2");
        assertThat(merged.get(1)).isSameAs(kept.get(1));
    }

    @Test
    @DisplayName("객관식이 하나도 없으면 검수할 것이 없다")
    void noChoicesSkipsReview() {
        assertThat(DraftGeneratorCli.splitForReview(List.of(ox("a"), ox("b")), ProblemType.MULTIPLE_CHOICE)
                .choices()).isEmpty();
    }
}
