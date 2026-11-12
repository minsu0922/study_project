package project.study.study_project.llm.client;

import org.junit.jupiter.api.Test;
import project.study.study_project.TestDomains;
import project.study.study_project.llm.support.TopicBoxes;
import project.study.study_project.llm.support.TopicBrief;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 사람이 쓴 글의 주제(V48)가 프롬프트에 실리고, 결과 상자가 그 글로 바뀌는지. API는 부르지 않는다. */
class ClaudeDocumentGeneratorTopicBoxTest {

    private final ClaudeDocumentGenerator generator = new ClaudeDocumentGenerator("claude-opus-5");

    private static final TopicBrief MEMO = TopicBrief.parse("핵심 개념: 서브넷\n설명할 것: 왜 나누는가?");

    @Test
    void 입문편_프롬프트에_입문편_글만_싣는다() {
        TopicBrief brief = TopicBrief.withTopicBoxes(MEMO, "입문편 상자 글.", "심화편 상자 글.");

        String prompt = generator.buildPrompt(TestDomains.NETWORK, "서브넷", brief, List.of(), List.of());

        assertThat(prompt).contains("[글의 주제 — 사람이 쓴 글]").contains("입문편 상자 글.")
                .doesNotContain("심화편 상자 글.").contains("[작성 지시]");
        assertThat(prompt).as("질문의 뼈대는 글의 주제 하나다")
                .contains("「글의 주제」가 던진 질문을 본문이 답하는 순서의 뼈대로 삼는다")
                .doesNotContain("\"설명할 것\"의 질문들을");
    }

    @Test
    void 심화편_프롬프트에_심화편_글만_싣는다() {
        TopicBrief brief = TopicBrief.withTopicBoxes(MEMO, "입문편 상자 글.", "심화편 상자 글.");
        GeneratedDocumentItem beginner = new GeneratedDocumentItem("서브넷", "subnet", "# 서브넷\n본문", List.of("network"));

        String prompt = generator.buildAdvancedPrompt(TestDomains.NETWORK, beginner, brief, List.of());

        assertThat(prompt).contains("[글의 주제 — 사람이 쓴 글]").contains("심화편 상자 글.")
                .doesNotContain("입문편 상자 글.");
    }

    @Test
    void 메모가_지시_형식이_아니어도_글의_주제는_싣는다() {
        TopicBrief brief = TopicBrief.withTopicBoxes(null, "입문편 상자 글.", "심화편 상자 글.");

        String prompt = generator.buildPrompt(TestDomains.NETWORK, "서브넷", brief, List.of(), List.of());

        assertThat(prompt).contains("입문편 상자 글.").doesNotContain("[작성 지시]");
    }

    @Test
    void 결과_상자를_사람_글로_바꿔_끼운다() {
        GeneratedDocumentItem item = new GeneratedDocumentItem("서브넷", "subnet",
                "# 서브넷\n\n> **글의 주제**\n>\n> 모델이 고친 글.\n\n## 핵심 요약\n", List.of("network"));

        GeneratedDocumentItem out = ClaudeDocumentGenerator.withTopicBox(item, "사람이 쓴 글.");

        assertThat(out.contentMd()).doesNotContain("모델이 고친 글.");
        assertThat(TopicBoxes.matches(out.contentMd(), "사람이 쓴 글.")).isTrue();
        assertThat(out.title()).isEqualTo("서브넷");
        assertThat(ClaudeDocumentGenerator.withTopicBox(item, null)).isSameAs(item);
    }
}
