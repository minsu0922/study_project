package project.study.study_project.llm.review;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QuoteMatchTest {

    @Test
    @DisplayName("공백·줄바꿈·강조 표시가 달라도 같은 글로 본다")
    void ignoresWhitespaceAndEmphasis() {
        assertThat(QuoteMatch.in("포트 번호가\n다르다", "두 연결은 **포트 번호가 다르다**.")).isTrue();
    }

    @Test
    @DisplayName("여러 글 중 하나에만 있어도 찾는다")
    void findsInAnyText() {
        assertThat(QuoteMatch.in("443", null, "다른 글", "서버 포트 443")).isTrue();
    }

    @Test
    @DisplayName("빈 인용은 어디에도 없는 것으로 본다")
    void blankQuoteIsNeverFound() {
        assertThat(QuoteMatch.in("  ", "아무 글")).isFalse();
        assertThat(QuoteMatch.in(null, "아무 글")).isFalse();
    }

    @Test
    @DisplayName("없는 글은 찾지 못한다")
    void missingQuote() {
        assertThat(QuoteMatch.in("UDP", "TCP 연결")).isFalse();
    }
}
