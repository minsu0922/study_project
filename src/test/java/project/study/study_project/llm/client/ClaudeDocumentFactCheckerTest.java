package project.study.study_project.llm.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 사실 검수기의 인용 확인 테스트 — Claude를 부르지 않는다. */
class ClaudeDocumentFactCheckerTest {

    private static final String DOC = """
            ## 재시작 순서

            적재가 끝날 때까지 **접속이 되지 않는다**는 점이 중요하다.
            `everysec`은 1초마다
            fsync를 부른다.
            """;

    private static FactCheckFinding finding(String quote) {
        return new FactCheckFinding(quote, FactCheckFinding.Kind.FACT_ERROR, "이유", "정정",
                FactCheckFinding.Confidence.HIGH);
    }

    @Test
    @DisplayName("원문에 있는 인용은 남기고, 지어낸 인용은 버린다")
    void dropsQuotesNotInDocument() {
        List<FactCheckFinding> kept = ClaudeDocumentFactChecker.keepQuotedInDocument(List.of(
                finding("적재가 끝날 때까지 **접속이 되지 않는다**는 점이 중요하다."),
                finding("적재 중에는 서버가 꺼져 있다.")), DOC);

        assertThat(kept).extracting(FactCheckFinding::quote)
                .containsExactly("적재가 끝날 때까지 **접속이 되지 않는다**는 점이 중요하다.");
    }

    @Test
    @DisplayName("강조·백틱을 빼거나 줄바꿈을 공백으로 옮긴 인용도 원문으로 인정한다")
    void toleratesMarkupAndLineBreaks() {
        List<FactCheckFinding> kept = ClaudeDocumentFactChecker.keepQuotedInDocument(List.of(
                finding("적재가 끝날 때까지 접속이 되지 않는다"),
                finding("everysec은 1초마다 fsync를 부른다.")), DOC);

        assertThat(kept).hasSize(2);
    }

    @Test
    @DisplayName("비었거나 null인 인용은 버린다 — 검수자가 찾아갈 곳이 없다")
    void dropsBlankQuotes() {
        List<FactCheckFinding> kept = ClaudeDocumentFactChecker.keepQuotedInDocument(List.of(
                finding(null), finding("  ")), DOC);

        assertThat(kept).isEmpty();
    }

    @Test
    @DisplayName("정의 없는 용어 항목은 입문편에만 붙는다 — 심화편은 입문편에서 푼 용어를 다시 풀지 않는다")
    void undefinedTermRuleOnlyForBeginner() {
        assertThat(ClaudeDocumentFactChecker.systemPromptFor(DocumentEdition.BEGINNER)).contains("UNDEFINED_TERM");
        assertThat(ClaudeDocumentFactChecker.systemPromptFor(DocumentEdition.ADVANCED)).doesNotContain("UNDEFINED_TERM");
    }

    @Test
    @DisplayName("공식 문서 검색 규칙은 검색을 켰을 때만 붙는다")
    void webSearchRuleOnlyWhenEnabled() {
        assertThat(ClaudeDocumentFactChecker.systemPromptFor(DocumentEdition.BEGINNER, true))
                .contains("[공식 문서로 확인하기]", "sourceUrl");
        assertThat(ClaudeDocumentFactChecker.systemPromptFor(DocumentEdition.BEGINNER, false))
                .doesNotContain("[공식 문서로 확인하기]");
        assertThat(ClaudeDocumentFactChecker.OFFICIAL_DOMAINS).contains("redis.io", "docs.spring.io");
    }

    @Test
    @DisplayName("프롬프트가 찾을 것 두 가지와 원문 복사 규칙을 담고 있다")
    void promptKeepsCoreRules() {
        assertThat(ClaudeDocumentFactChecker.SYSTEM_PROMPT)
                .contains("FACT_ERROR", "INTERNAL_MISMATCH", "글자 그대로 복사", "빈 목록");
    }
}
