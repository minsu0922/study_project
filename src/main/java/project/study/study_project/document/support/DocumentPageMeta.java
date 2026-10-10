package project.study.study_project.document.support;

import project.study.study_project.document.dto.DocumentDetailResponse;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 문서 화면({@code document.html})의 {@code <head>}에 넣을 제목과 메타 태그를 만든다.
 *
 * <p><b>왜 서버에서 만드나.</b> 화면은 정적 HTML이고 제목과 본문은 JS가 API를 불러 채운다.
 * 그래서 JS를 돌리지 않는 쪽에는 문서 30편이 전부 {@code 문서 — csquiz}로 보였다 — 메신저의
 * 링크 미리보기가 그렇고, 검색 엔진도 JS 실행을 늘 해 주지는 않는다.
 *
 * <p><b>본문은 여기서 그리지 않는다.</b> 마크다운을 서버에서 HTML로 바꾸려면 화면의 marked·DOMPurify와
 * 같은 일을 하는 라이브러리가 한 벌 더 필요하고, 두 벌은 어긋난다. 머리말만 채워도 검색 결과의
 * 제목과 설명, 링크 미리보기는 해결된다.
 */
public final class DocumentPageMeta {

    /**
     * 템플릿에서 갈아 끼울 자리. {@code document.html}의 제목 줄과 글자까지 같아야 한다 —
     * 달라지면 아무것도 못 끼우고 원본이 그대로 나간다({@code DocumentPageMetaTest}가 지킨다).
     */
    public static final String TITLE_MARKER = "<title>문서 — csquiz</title>";

    /** 설명 길이 상한. 검색 결과가 보여 주는 길이가 이쯤이고, 넘는 부분은 어차피 잘린다. */
    static final int DESCRIPTION_MAX = 150;

    /**
     * 설명으로 쓸 한 줄이 들어 있는 절 — 앞에서부터 찾는다.
     *
     * <p>마무리 절이 먼저다. 프롬프트가 그 절을 "글 전체를 2~3문장으로 줄인 것"으로 쓰게 하므로
     * 검색 결과에 띄울 설명으로 가장 맞다. 그 절이 없는 옛 문서는 핵심 요약의 첫 줄을 쓴다.
     */
    private static final List<String> SUMMARY_SECTIONS =
            List.of("## 한 줄로 정리하면", "## 면접 한 줄 요약", "## 핵심 요약");

    private static final Pattern FENCED_CODE = Pattern.compile("(?s)```.*?```");
    private static final Pattern HEADING_LINE = Pattern.compile("(?m)^#{1,6}\\s.*$");
    private static final Pattern TABLE_LINE = Pattern.compile("(?m)^\\|.*$");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)]\\([^)]*\\)");
    /** 문장 끝 — 마침표·물음표·느낌표 뒤에 공백이나 글의 끝이 온다. "3.5배"의 점은 걸리지 않는다. */
    private static final Pattern SENTENCE_END = Pattern.compile("[.?!](?=\\s|$)");

    private DocumentPageMeta() {
    }

    /** 템플릿의 제목 줄을 이 문서의 제목과 메타 태그로 갈아 끼운다. 자리를 못 찾으면 원본 그대로. */
    public static String apply(String template, DocumentDetailResponse doc) {
        return template.replace(TITLE_MARKER, headOf(doc));
    }

    /**
     * 없는 문서의 머리말 — 제목은 그대로 두고 검색에서만 뺀다.
     *
     * <p>없는 주소가 "문서 — csquiz"라는 빈 화면으로 검색 결과에 남는 것을 막는다.
     */
    public static String applyNotFound(String template) {
        return template.replace(TITLE_MARKER,
                TITLE_MARKER + "\n<meta name=\"robots\" content=\"noindex\">");
    }

    static String headOf(DocumentDetailResponse doc) {
        String title = escape(titleOf(doc));
        String description = escape(descriptionOf(doc.contentMd()));
        StringBuilder head = new StringBuilder("<title>").append(title).append(" — csquiz</title>");
        // og:* 는 메신저·SNS의 링크 미리보기가 읽는다. og:url과 og:image는 넣지 않는다 —
        // 앞의 것은 배포 주소를 알아야 하고, 뒤의 것은 쓸 그림이 아직 없다.
        head.append("\n<meta property=\"og:type\" content=\"article\">");
        head.append("\n<meta property=\"og:site_name\" content=\"csquiz\">");
        head.append("\n<meta property=\"og:title\" content=\"").append(title).append("\">");
        if (!description.isEmpty()) {
            head.append("\n<meta name=\"description\" content=\"").append(description).append("\">");
            head.append("\n<meta property=\"og:description\" content=\"").append(description).append("\">");
        }
        return head.toString();
    }

    /**
     * 화면 제목. 짝이 있는 문서는 편을 붙인다 — 입문편과 심화편은 제목이 글자까지 같아서,
     * 안 붙이면 검색 결과에 같은 제목이 두 줄 뜬다.
     */
    static String titleOf(DocumentDetailResponse doc) {
        return doc.edition() == null ? doc.title() : doc.title() + " · " + doc.edition();
    }

    /**
     * 본문에서 설명 한 줄을 뽑는다. 뽑을 것이 없으면 빈 문자열.
     *
     * <p>문장 단위로 자른다. 150자에서 뚝 끊으면 검색 결과에 말이 끊긴 채 나가므로,
     * 상한 안에 들어가는 문장까지만 싣는다. 첫 문장부터 상한을 넘으면 그때만 줄임표로 자른다.
     */
    static String descriptionOf(String contentMd) {
        if (contentMd == null || contentMd.isBlank()) {
            return "";
        }
        // 코드블록부터 지운다. 그 안의 "## " 주석이 절 제목으로 읽히면 엉뚱한 자리를 요약으로 집는다.
        String body = FENCED_CODE.matcher(contentMd).replaceAll("");
        String source = null;
        for (String heading : SUMMARY_SECTIONS) {
            source = firstParagraphOf(sectionOf(body, heading));
            if (!source.isEmpty()) {
                break;
            }
        }
        if (source == null || source.isEmpty()) {
            source = firstParagraphOf(body);
        }
        return fit(plain(source));
    }

    /** {@code heading} 아래부터 다음 {@code ## } 절 앞까지. 그 절이 없으면 빈 문자열. */
    private static String sectionOf(String body, String heading) {
        Matcher m = Pattern.compile("(?m)^" + Pattern.quote(heading) + "\\s*$").matcher(body);
        if (!m.find()) {
            return "";
        }
        int next = body.indexOf("\n## ", m.end());
        return body.substring(m.end(), next < 0 ? body.length() : next);
    }

    /** 제목 줄과 표를 뺀 첫 문단. 목록이면 첫 항목 하나다. */
    private static String firstParagraphOf(String text) {
        String cleaned = TABLE_LINE.matcher(HEADING_LINE.matcher(text).replaceAll("")).replaceAll("");
        for (String block : cleaned.split("\\R\\s*\\R")) {
            String trimmed = block.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            // 목록은 첫 줄만 쓴다. 통째로 이으면 서로 다른 요점 여섯이 한 문장처럼 붙는다.
            return trimmed.startsWith("- ") ? trimmed.lines().findFirst().orElse("") : trimmed;
        }
        return "";
    }

    /** 마크다운 기호를 걷어 낸 한 줄. */
    private static String plain(String markdown) {
        String text = LINK.matcher(markdown).replaceAll("$1");
        text = text.replaceFirst("^(-|>)\\s+", "");
        text = text.replace("**", "").replace("`", "");
        text = text.replaceAll("\\s+", " ").strip();
        // 마무리 절은 통째로 큰따옴표에 싸여 있다. 속성 값 안에서는 뜻이 없는 기호다.
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1).strip();
        }
        return text;
    }

    /**
     * 태그와 속성을 깨는 다섯 글자만 바꾼다.
     *
     * <p>{@code HtmlUtils.htmlEscape}를 쓰지 않는 이유: 그쪽은 가운뎃점·줄표까지 {@code &middot;}
     * 같은 이름 엔티티로 바꾼다. 브라우저는 되돌려 읽지만, 미리보기를 만드는 쪽이 전부 그러리라는
     * 보장이 없고 제목에 늘 가운뎃점이 들어간다("제목 · 심화편").
     */
    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String fit(String text) {
        if (text.length() <= DESCRIPTION_MAX) {
            return text;
        }
        int end = -1;
        Matcher m = SENTENCE_END.matcher(text);
        while (m.find() && m.end() <= DESCRIPTION_MAX) {
            end = m.end();
        }
        return end > 0 ? text.substring(0, end) : text.substring(0, DESCRIPTION_MAX - 1).strip() + "…";
    }
}
