package project.study.study_project.llm.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.global.common.ProblemType;

import static org.assertj.core.api.Assertions.assertThat;

class TypeMixTest {

    private static final String STEPS = """
            ## 요금을 멈추는 방법

            1. 가
            2. 나
            3. 다
            4. 라
            """;
    private static final String GLOSSARY = """
            ## 용어 한눈에

            | 용어 | 뜻 |
            |---|---|
            | a | 1 |
            | b | 2 |
            | c | 3 |
            | d | 4 |
            """;
    private static final String MYTHS = """
            ## 자주 하는 오해

            **"꺼 두면 0원이다."**

            설명.
            """;

    @Test
    @DisplayName("재료가 다 있으면 객관식 4·OX 1·짝짓기 1·순서 배열 1")
    void allMaterial() {
        TypeMix mix = TypeMix.forBeginner(7, STEPS + GLOSSARY + MYTHS);

        assertThat(mix.isMixed()).isTrue();
        assertThat(mix.summary()).isEqualTo("객관식 4 · OX 1 · 짝짓기 1 · 순서 배열 1");
        assertThat(mix.sections().get(ProblemType.ORDERING)).containsExactly("요금을 멈추는 방법");
        assertThat(mix.sections().get(ProblemType.MATCHING)).containsExactly("용어 한눈에");
        assertThat(mix.sections().get(ProblemType.OX)).containsExactly("자주 하는 오해");
    }

    @Test
    @DisplayName("용어 표가 없으면 짝짓기 몫을 객관식으로 채운다")
    void noGlossary() {
        TypeMix mix = TypeMix.forBeginner(7, STEPS + MYTHS);

        assertThat(mix.summary()).isEqualTo("객관식 5 · OX 1 · 순서 배열 1");
        assertThat(mix.counts()).doesNotContainKey(ProblemType.MATCHING);
    }

    @Test
    @DisplayName("번호 목록이 중급 절에만 있으면 순서 배열을 넣지 않는다")
    void intermediateSectionDropped() {
        String md = STEPS.replace("요금을 멈추는 방법", "실무에서는 이렇게 쓴다") + GLOSSARY + MYTHS;

        TypeMix mix = TypeMix.forBeginner(7, md);

        assertThat(mix.summary()).isEqualTo("객관식 5 · OX 1 · 짝짓기 1");
    }

    @Test
    @DisplayName("개수가 적어도 객관식 하나는 남긴다")
    void smallCountKeepsOneChoice() {
        TypeMix mix = TypeMix.forBeginner(2, STEPS + GLOSSARY + MYTHS);

        assertThat(mix.counts().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(2);
        assertThat(mix.counts().get(ProblemType.MULTIPLE_CHOICE)).isEqualTo(1);
        assertThat(mix.summary()).isEqualTo("객관식 1 · OX 1");
    }

    @Test
    @DisplayName("재료가 없으면 섞지 않는다")
    void nothing() {
        TypeMix mix = TypeMix.forBeginner(7, "## 절\n\n문단만 있다.");

        assertThat(mix.isMixed()).isFalse();
        assertThat(mix.summary()).isEqualTo("객관식 7");
    }
}
