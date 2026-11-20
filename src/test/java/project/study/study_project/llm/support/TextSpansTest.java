package project.study.study_project.llm.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 강조·백틱을 걷어 낸 글로 찾고 원문 자리로 되짚는다 — 독자 점검 제안을 본문에 꽂을 자리다. */
class TextSpansTest {

    @Test
    @DisplayName("강조·백틱이 빠진 인용도 원문 자리를 찾는다")
    void locatesAcrossEmphasis() {
        String md = "앞 문장. **리졸버**는 `53`번으로 묻는다. 뒤 문장.";
        int[] at = TextSpans.locateOnce(md, "리졸버는 53번으로 묻는다.").orElseThrow();
        assertThat(md.substring(at[0], at[1])).isEqualTo("**리졸버**는 `53`번으로 묻는다.");
    }

    @Test
    @DisplayName("줄바꿈을 공백으로 옮긴 인용도 찾는다")
    void locatesAcrossLineBreak() {
        String md = "첫 줄은\n여기서 이어진다. 끝.";
        int[] at = TextSpans.locateOnce(md, "첫 줄은 여기서 이어진다.").orElseThrow();
        assertThat(md.substring(at[0], at[1])).isEqualTo("첫 줄은\n여기서 이어진다.");
    }

    @Test
    @DisplayName("두 번 나오면 어디를 고칠지 몰라 찾지 않는다")
    void emptyWhenTwice() {
        assertThat(TextSpans.locateOnce("가다. 가다.", "가다.")).isEmpty();
    }

    @Test
    @DisplayName("없거나 빈 글은 찾지 않는다")
    void emptyWhenMissingOrBlank() {
        assertThat(TextSpans.locateOnce("가다.", "오다.")).isEmpty();
        assertThat(TextSpans.locateOnce("가다.", "  ")).isEmpty();
        assertThat(TextSpans.locateOnce("가다.", null)).isEmpty();
    }
}
