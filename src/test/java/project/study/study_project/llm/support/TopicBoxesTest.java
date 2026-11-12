package project.study.study_project.llm.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TopicBoxesTest {

    private static final String BOX = "첫 문단 첫 줄.\n이어지는 줄.\n\n둘째 문단.";

    @Test
    void 문단_글을_인용_상자로_감싼다() {
        assertThat(TopicBoxes.render(BOX)).isEqualTo("""
                > **글의 주제**
                >
                > 첫 문단 첫 줄.
                > 이어지는 줄.
                >
                > 둘째 문단.""");
    }

    @Test
    void 모델이_쓴_상자를_사람_글로_바꾼다() {
        String doc = """
                # 제목

                > **글의 주제**
                >
                > 모델이 고쳐 쓴 문장.

                ## 핵심 요약
                """;

        String out = TopicBoxes.apply(doc, BOX);

        assertThat(out).doesNotContain("모델이 고쳐 쓴 문장");
        assertThat(out).startsWith("# 제목\n\n> **글의 주제**\n>\n> 첫 문단 첫 줄.");
        assertThat(out).contains("> 둘째 문단.\n\n## 핵심 요약");
        assertThat(TopicBoxes.matches(out, BOX)).isTrue();
    }

    @Test
    void 상자가_없으면_H1_아래에_넣는다() {
        String out = TopicBoxes.apply("# 제목\n\n## 핵심 요약\n", BOX);

        assertThat(out).startsWith("# 제목\n\n> **글의 주제**\n");
        assertThat(out).contains("> 둘째 문단.\n\n## 핵심 요약");
    }

    @Test
    void 꺼낸_글은_줄_앞뒤_공백_차이를_보지_않는다() {
        String doc = TopicBoxes.apply("# 제목\n", "  첫 문단 첫 줄.  \n이어지는 줄.\n\n\n둘째 문단.");

        assertThat(TopicBoxes.extract(doc)).isEqualTo(BOX);
        assertThat(TopicBoxes.matches(doc, BOX)).isTrue();
    }

    @Test
    void 한_글자라도_다르면_다르다고_본다() {
        String doc = TopicBoxes.apply("# 제목\n", BOX).replace("둘째 문단.", "둘째 문단이다.");

        assertThat(TopicBoxes.matches(doc, BOX)).isFalse();
        assertThat(TopicBoxes.matches("# 제목\n", BOX)).as("상자가 없음").isFalse();
    }

    @Test
    void 빈_글이면_본문을_그대로_둔다() {
        assertThat(TopicBoxes.apply("# 제목\n", "  ")).isEqualTo("# 제목\n");
        assertThat(TopicBoxes.paragraphs(null)).isEqualTo(List.of());
    }

    @Test
    void 검사기는_사람_글과_다른_상자를_막는다() {
        String doc = TopicBoxes.apply("# 제목\n", BOX).replace("둘째 문단.", "다른 문단.");

        List<DraftCheck> checks = DocumentDraftValidator.validate("제목", "title", doc, BOX);

        assertThat(checks).anyMatch(c -> c.isBlocking() && c.message().contains("글의 주제"));
        assertThat(DocumentDraftValidator.validate("제목", "title", doc, null))
                .noneMatch(c -> c.message().contains("「글의 주제」"));
    }
}
