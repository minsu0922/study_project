package project.study.study_project.document.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.TestDomains;
import project.study.study_project.document.dto.DocumentDetailResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 문서 화면의 머리말 — 제목과 설명을 본문에서 뽑는 규칙(2026-10-10 신설).
 *
 * <p>화면이 제목을 JS로 채워서, JS를 안 돌리는 검색 엔진과 메신저에는 30편이 전부
 * "문서 — csquiz"였다. 여기서 재는 것은 뽑아낸 글이 <b>그대로 검색 결과에 나가도 되는가</b>다.
 */
class DocumentPageMetaTest {

    private static DocumentDetailResponse doc(String title, String contentMd, String edition) {
        return new DocumentDetailResponse(1L, TestDomains.DATABASE, "데이터베이스", title, "slug",
                contentMd, null, List.of(), null, null, edition, edition == null ? null : "slug-pair");
    }

    @Test
    @DisplayName("마무리 절의 문장을 설명으로 쓴다 — 글 전체를 줄인 자리라 검색 결과에 가장 맞다")
    void usesTheClosingSummary() {
        String md = """
                # 제목

                ## 핵심 요약
                - **첫째 요점** — 결론 한 문장.

                ## 한 줄로 정리하면

                "팬텀 리드는 **같은 조건**으로 두 번 조회할 때 새 행이 끼어드는 현상이다. `SERIALIZABLE`만 막는다."
                """;

        assertThat(DocumentPageMeta.descriptionOf(md))
                .as("따옴표·굵은 글씨·백틱은 검색 결과에서 기호로만 보인다")
                .isEqualTo("팬텀 리드는 같은 조건으로 두 번 조회할 때 새 행이 끼어드는 현상이다. SERIALIZABLE만 막는다.");
    }

    @Test
    @DisplayName("심화편은 면접 한 줄 요약을 쓴다")
    void usesTheInterviewSummaryForAdvanced() {
        String md = "# 제목\n\n## 어떤 때 통하지 않는가\n항목들.\n\n## 면접 한 줄 요약\n\"범위를 잠가야 막힌다.\"\n";

        assertThat(DocumentPageMeta.descriptionOf(md)).isEqualTo("범위를 잠가야 막힌다.");
    }

    @Test
    @DisplayName("마무리 절이 없는 옛 문서는 핵심 요약의 첫 줄을 쓴다 — 여섯 줄을 이어 붙이지 않는다")
    void fallsBackToTheFirstKeyPoint() {
        String md = "# 제목\n\n## 핵심 요약\n- **첫째 요점** — 결론 한 문장.\n- **둘째 요점** — 다른 결론.\n\n## 본론\n글.\n";

        assertThat(DocumentPageMeta.descriptionOf(md)).isEqualTo("첫째 요점 — 결론 한 문장.");
    }

    @Test
    @DisplayName("아는 절이 하나도 없으면 첫 문단을 쓴다 — 손으로 올린 문서도 설명은 있어야 한다")
    void fallsBackToTheFirstParagraph() {
        assertThat(DocumentPageMeta.descriptionOf("# 제목\n\n읽기 경로를 설명하는 [문서](https://example.com)다.\n\n둘째 문단."))
                .isEqualTo("읽기 경로를 설명하는 문서다.");
    }

    /** 코드블록 안의 {@code ## } 주석이 절 제목으로 읽히면 SQL 한 줄이 설명으로 나간다. */
    @Test
    @DisplayName("코드블록 안의 '## 한 줄로 정리하면'은 절로 보지 않는다")
    void ignoresHeadingsInsideCode() {
        String md = "# 제목\n\n```bash\n## 한 줄로 정리하면\necho done\n```\n\n## 핵심 요약\n- 진짜 요점이다.\n";

        assertThat(DocumentPageMeta.descriptionOf(md)).isEqualTo("진짜 요점이다.");
    }

    @Test
    @DisplayName("길면 문장 경계에서 끊는다 — 말이 끊긴 채 검색 결과에 나가지 않는다")
    void cutsAtASentenceBoundary() {
        String first = "가".repeat(80) + "다.";
        String second = "나".repeat(80) + "다.";

        assertThat(DocumentPageMeta.descriptionOf("## 한 줄로 정리하면\n\"" + first + " " + second + "\""))
                .as("둘을 합치면 상한을 넘으므로 첫 문장만 싣는다")
                .isEqualTo(first);
    }

    @Test
    @DisplayName("첫 문장부터 상한을 넘으면 줄임표로 자른다")
    void truncatesAnOverlongFirstSentence() {
        String description = DocumentPageMeta.descriptionOf("## 한 줄로 정리하면\n" + "가".repeat(400) + "다.");

        assertThat(description).hasSize(DocumentPageMeta.DESCRIPTION_MAX).endsWith("…");
    }

    @Test
    @DisplayName("본문이 비면 설명 태그를 아예 안 넣는다 — 빈 설명은 없는 것만 못하다")
    void omitsDescriptionWhenBodyIsEmpty() {
        assertThat(DocumentPageMeta.headOf(doc("제목", "", null)))
                .contains("<title>제목 — csquiz</title>")
                .doesNotContain("name=\"description\"");
    }

    /** 입문편과 심화편은 제목이 글자까지 같다 — 편을 안 붙이면 검색 결과에 같은 제목이 두 줄 뜬다. */
    @Test
    @DisplayName("짝이 있는 문서는 제목에 편을 붙인다")
    void addsEditionToTheTitle() {
        assertThat(DocumentPageMeta.headOf(doc("팬텀 리드", "본문이다.", "심화편")))
                .contains("<title>팬텀 리드 · 심화편 — csquiz</title>")
                .contains("<meta property=\"og:title\" content=\"팬텀 리드 · 심화편\">");
    }

    /** 제목과 본문은 모델이 쓴 글이다. 따옴표 하나로 속성이 닫히면 뒤의 글이 태그로 읽힌다. */
    @Test
    @DisplayName("제목과 설명의 꺾쇠·따옴표를 이스케이프한다 — 속성 밖으로 새면 태그가 된다")
    void escapesTitleAndDescription() {
        String head = DocumentPageMeta.headOf(
                doc("List<String>과 \"제네릭\"", "값을 \"><script>alert(1)</script> 로 닫는다.", null));

        assertThat(head)
                .doesNotContain("<script>")
                .doesNotContain("List<String>")
                .contains("List&lt;String&gt;")
                .contains("&quot;&gt;&lt;script&gt;");
    }

    /**
     * 갈아 끼울 자리는 화면 파일의 제목 줄 그 자체다. 누가 그 줄을 고치면 아무것도 못 끼우고
     * 원본이 그대로 나가는데, 화면은 멀쩡히 뜨므로 아무도 모른다.
     */
    @Test
    @DisplayName("document.html에 갈아 끼울 제목 줄이 정확히 한 번 있다 — 바뀌면 메타 태그가 조용히 사라진다")
    void templateStillHasTheMarker() throws IOException {
        String template = Files.readString(
                Path.of("src/main/resources/static/document.html"), StandardCharsets.UTF_8);

        assertThat(template.split(java.util.regex.Pattern.quote(DocumentPageMeta.TITLE_MARKER), -1))
                .hasSize(2);
    }

    @Test
    @DisplayName("없는 문서는 제목을 두고 검색에서만 뺀다")
    void notFoundPageIsNotIndexed() {
        assertThat(DocumentPageMeta.applyNotFound("<head>" + DocumentPageMeta.TITLE_MARKER + "</head>"))
                .contains(DocumentPageMeta.TITLE_MARKER)
                .contains("<meta name=\"robots\" content=\"noindex\">");
    }
}
