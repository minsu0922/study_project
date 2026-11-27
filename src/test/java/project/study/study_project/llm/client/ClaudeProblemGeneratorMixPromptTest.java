package project.study.study_project.llm.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.DomainCode;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.llm.support.TypeMix;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 초급 유형 섞기(2026-11-27) — 섞은 날에만 유형 구성이 실리고, 안 섞은 날은 글자까지 그대로인지. */
class ClaudeProblemGeneratorMixPromptTest {

    private final ClaudeProblemGenerator generator = new ClaudeProblemGenerator("claude-opus-5");

    private static final SourceDocument DOC = new SourceDocument(
            "cloud", "클라우드", "# 클라우드\n\n## 바탕이 되는 개념\n서버는 요청에 답한다.");

    private static final TypeMix MIX = new TypeMix(
            Map.of(ProblemType.MULTIPLE_CHOICE, 4, ProblemType.OX, 1, ProblemType.MATCHING, 1, ProblemType.ORDERING, 1),
            Map.of(ProblemType.OX, List.of("자주 하는 오해"),
                    ProblemType.MATCHING, List.of("용어 한눈에"),
                    ProblemType.ORDERING, List.of("요금을 멈추는 방법", "왜 필요한가")));

    private String prompt(TypeMix mix) {
        return generator.buildPrompt(DomainCode.of("CLOUD_INFRA"), Difficulty.BEGINNER, ProblemType.MULTIPLE_CHOICE,
                7, List.of(), List.of(), DOC, null, mix);
    }

    @Test
    @DisplayName("섞은 날은 유형 구성과 유형별 형식·재료 절이 실리고 단일 '- 유형:' 줄은 없다")
    void mixedPromptListsComposition() {
        String prompt = prompt(MIX);

        assertThat(prompt).contains("- 유형 구성: 객관식 4 · OX 1 · 짝짓기 1 · 순서 배열 1");
        assertThat(prompt).contains("문제마다 type에 그 유형을 적는다");
        assertThat(prompt).contains("순서 배열 — choices에");
        assertThat(prompt).contains("짝짓기 — choices의 한 항목이 한 쌍이다");
        assertThat(prompt).contains("「요금을 멈추는 방법」·「왜 필요한가」");
        assertThat(prompt).contains("「용어 한눈에」");
        assertThat(prompt).contains("객관식으로 바꾸지 말고 덜 낸다");
        assertThat(prompt).doesNotContain("- 유형: ");
    }

    @Test
    @DisplayName("OX는 오해 문장(정답 X)과 바로잡은 사실(정답 O) 중 하나로 내라고 적는다")
    void oxRuleAsksOneOfTwo() {
        String prompt = prompt(MIX);

        assertThat(prompt).contains("「자주 하는 오해」").contains("정답 X").contains("정답 O");
    }

    @Test
    @DisplayName("중급 섞은 날은 순서 배열이 형태 배분의 순서 몫을 맡는다고 적고, 초급에는 그 줄이 없다")
    void intermediateOrderingTakesSequenceSlot() {
        TypeMix mix = new TypeMix(Map.of(ProblemType.MULTIPLE_CHOICE, 4, ProblemType.ORDERING, 1),
                Map.of(ProblemType.ORDERING, List.of("요금을 멈추는 방법")));
        String intermediate = generator.buildPrompt(DomainCode.of("CLOUD_INFRA"), Difficulty.INTERMEDIATE,
                ProblemType.MULTIPLE_CHOICE, 5, List.of(), List.of(), DOC, null, mix);

        assertThat(intermediate).contains("- 유형 구성: 객관식 4 · 순서 배열 1")
                .contains("순서 배열 문제의 questionKind는 SEQUENCE")
                .contains("객관식은 비교 2·인과 1·판정 1");
        assertThat(prompt(MIX)).doesNotContain("questionKind는 SEQUENCE");
    }

    private static GeneratedProblemItem typed(ProblemType t) {
        return new GeneratedProblemItem("q", "", "", "e", List.of(), "", "t", null, "").withType(t);
    }

    @Test
    @DisplayName("안 섞은 날은 모델이 적은 유형을 비운다 — 요청한 유형보다 모델 말이 이기면 안 된다")
    void unmixedDropsModelType() {
        assertThat(ClaudeProblemGenerator.normalizeTypes(List.of(typed(ProblemType.OX)), null))
                .extracting(GeneratedProblemItem::type).containsOnlyNulls();
    }

    @Test
    @DisplayName("섞은 날은 구성에 있는 유형만 남기고 나머지는 비운다")
    void mixedKeepsOnlyPlannedTypes() {
        assertThat(ClaudeProblemGenerator.normalizeTypes(
                List.of(typed(ProblemType.OX), typed(ProblemType.ESSAY), typed(ProblemType.MATCHING)), MIX))
                .extracting(GeneratedProblemItem::type)
                .containsExactly(ProblemType.OX, null, ProblemType.MATCHING);
    }

    @Test
    @DisplayName("안 섞은 날(구성 없음·객관식만)은 지금 프롬프트와 글자까지 같다")
    void unmixedPromptUnchanged() {
        String before = generator.buildPrompt(DomainCode.of("CLOUD_INFRA"), Difficulty.BEGINNER,
                ProblemType.MULTIPLE_CHOICE, 7, List.of(), List.of(), DOC, null);
        TypeMix choicesOnly = new TypeMix(Map.of(ProblemType.MULTIPLE_CHOICE, 7), Map.of());

        assertThat(prompt(null)).isEqualTo(before);
        assertThat(prompt(choicesOnly)).isEqualTo(before);
    }
}
