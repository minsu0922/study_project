package project.study.study_project.llm.review;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.llm.client.GeneratedProblemItem;
import project.study.study_project.llm.client.GeneratedProblemItem.GeneratedChoice;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShownTest {

    static GeneratedProblemItem mc(String question) {
        return new GeneratedProblemItem(question, "", "해설", List.of(
                new GeneratedChoice("스키마", true),
                new GeneratedChoice("테이블", false),
                new GeneratedChoice("컬럼", false),
                new GeneratedChoice("행", false)));
    }

    @Test
    @DisplayName("보기 4개·정답 1개인 객관식만 보여 주고, 같은 문제는 늘 같은 순서로 섞는다")
    void showsOnlyWellFormedMultipleChoice() {
        GeneratedProblemItem ox = new GeneratedProblemItem("OX 문제", "O", "해설", List.of());
        List<Shown> shown = Shown.showable(List.of(ox, mc("스키마란?")));

        assertThat(shown).hasSize(1);
        assertThat(shown.get(0).index()).isEqualTo(1);
        assertThat(shown.get(0).order()).containsExactlyInAnyOrder(0, 1, 2, 3);
        assertThat(Shown.showable(List.of(mc("스키마란?"))).get(0).order()).isEqualTo(shown.get(0).order());
        assertThat(shown.get(0).choiceText(shown.get(0).correctNo())).isEqualTo("스키마");
    }

    @Test
    @DisplayName("근거 속 보기 번호를 원래 보기 순서로 바꾼다")
    void rewritesChoiceNumbersToOriginalOrder() {
        var s = new Shown(0, mc("스키마란?"), List.of(2, 0, 3, 1));

        assertThat(s.inOriginalNumbers("1번만 길고 4번은 2번과 겹친다")).isEqualTo("3번만 길고 2번은 1번과 겹친다");
        assertThat(s.inOriginalNumbers("12번 호출하고 5번은 그대로")).isEqualTo("12번 호출하고 5번은 그대로");
        assertThat(s.inOriginalNumbers(null)).isEmpty();
    }
}
