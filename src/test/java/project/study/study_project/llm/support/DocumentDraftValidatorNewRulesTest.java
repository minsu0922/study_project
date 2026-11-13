package project.study.study_project.llm.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 2026-11-13에 더한 규칙 셋 — 「글의 주제」 상자, 그림 파일, 심화편 장면 꼴. */
class DocumentDraftValidatorNewRulesTest {

    private static final String BOX = "> **글의 주제**\n>\n> 확인용 글의 주제.\n";

    private static List<String> messages(String doc) {
        return DocumentDraftValidator.validate("제목", "title", doc).stream().map(DraftCheck::message).toList();
    }

    @Test
    void 글의_주제_상자가_없으면_막는다() {
        List<DraftCheck> checks = DocumentDraftValidator.validate("제목", "title", "# 제목\n\n본문\n");

        assertThat(checks).anyMatch(c -> c.isBlocking() && c.message().contains("「글의 주제」 상자가 없습니다"));
        assertThat(messages("# 제목\n\n" + BOX + "\n본문\n")).noneMatch(m -> m.contains("「글의 주제」 상자가 없습니다"));
    }

    @Test
    void 없는_그림_파일을_부르면_막는다() {
        String doc = "# 제목\n\n" + BOX + "\n![없는 그림](/images/docs/no-such-figure.svg)\n";

        assertThat(DocumentDraftValidator.validate("제목", "title", doc))
                .anyMatch(c -> c.isBlocking() && c.message().contains("/images/docs/no-such-figure.svg"));
    }

    @Test
    void 있는_그림_파일은_조용하다() {
        String doc = "# 제목\n\n" + BOX + "\n![그림](/images/docs/cookie-flow.svg)\n";

        assertThat(messages(doc)).noneMatch(m -> m.contains("그림 파일이 없습니다"));
    }

    private static String advanced(String failureBody) {
        return "# 제목\n\n" + BOX + "\n## 이 글을 읽기 전에\n앞 글 요약.\n\n## 어떤 때 통하지 않는가\n여는 문장.\n\n"
                + failureBody + "\n## 면접에서 이렇게 물어본다\n질문.\n";
    }

    private static final String TAGGED = "#### %s\n\n- `증상` 터진다.\n- `원인` 이유다.\n- `대처` 막는다.\n\n";

    @Test
    void 장면_소제목이_없으면_알린다() {
        assertThat(messages(advanced(TAGGED.formatted("하나") + TAGGED.formatted("둘"))))
                .anyMatch(m -> m.contains("'### 장면 ①' 소제목이 없습니다"));
    }

    @Test
    void 장면의_첫_사례가_꼬리표면_알린다() {
        String body = "### 장면 ① 칸이 모자란다\n\n잇는 문장.\n\n" + TAGGED.formatted("하나") + TAGGED.formatted("둘");

        assertThat(messages(advanced(body))).anyMatch(m -> m.contains("'장면 ① 칸이 모자란다'의 첫 사례가 꼬리표로"));
    }

    @Test
    void 장면의_첫_사례가_이야기면_조용하다() {
        String body = "### 장면 ① 칸이 모자란다\n\n잇는 문장.\n\n#### 행사 날 결제가 실패하는 이유\n\n쇼핑몰이 0시에 행사를 연다.\n\n"
                + TAGGED.formatted("둘");

        assertThat(messages(advanced(body))).noneMatch(m -> m.contains("장면"));
    }
}
