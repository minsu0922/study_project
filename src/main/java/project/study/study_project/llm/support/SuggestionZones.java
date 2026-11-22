package project.study.study_project.llm.support;

import project.study.study_project.llm.client.ReaderFinding;

/**
 * 독자 점검 제안으로 고치면 안 되는 자리 — 윤문 프롬프트의 「건드리지 않는 것」과 같다.
 * 제목·코드·그림·인용 상자·표·용어 정의 줄 앞부분은 형식이나 사람이 쓴 글이라, 제안 수락으로 바꾸면
 * 검수자가 모르는 사이 형식이 깨진다.
 */
public final class SuggestionZones {

    private static final String DEFINITION_DASH = " — ";

    private SuggestionZones() {
    }

    /** {@code [start, end)}가 금지 자리에 걸치거나 강조·코드 표시를 반만 걸치면 true. */
    public static boolean forbidden(String contentMd, int start, int end) {
        return forbidden(contentMd, start, end, false);
    }

    /**
     * 제안 종류까지 보고 판정한다. 「글의 주제」 같은 일반 인용 줄은 검수자가 비교를 보고 누르므로 한 줄 바꾸기만 받는다.
     * 지우거나 여러 줄로 바꾸면 빈 {@code >} 줄이나 상자 밖 글이 생기고, 결론 상자({@code > **})는 강조가 풀려 계속 막는다.
     */
    public static boolean forbidden(String contentMd, int start, int end, ReaderFinding.Op op, String text) {
        boolean oneLineReplace = op == ReaderFinding.Op.REPLACE && text != null && !text.isBlank()
                && text.strip().indexOf('\n') < 0;
        return forbidden(contentMd, start, end, oneLineReplace);
    }

    private static boolean forbidden(String contentMd, int start, int end, boolean quoteEditable) {
        if (halfMarkup(contentMd.substring(start, end))) {
            return true;
        }
        boolean inFence = false;
        int lineStart = 0;
        while (lineStart <= contentMd.length()) {
            int nl = contentMd.indexOf('\n', lineStart);
            int lineEnd = nl < 0 ? contentMd.length() : nl;
            String line = contentMd.substring(lineStart, lineEnd);
            String trimmed = line.stripLeading();
            boolean fenceLine = trimmed.startsWith("```");
            boolean overlaps = start < lineEnd + 1 && end > lineStart;
            boolean editableQuote = quoteEditable && !inFence
                    && insidePlainQuote(line, lineStart, lineEnd, start, end);
            if (overlaps && !editableQuote && (inFence || fenceLine || lockedLine(trimmed)
                    || overlapsDefinitionHead(line, lineStart, start))) {
                return true;
            }
            if (fenceLine) {
                inFence = !inFence;
            }
            if (nl < 0) {
                break;
            }
            lineStart = nl + 1;
        }
        return false;
    }

    /**
     * 자리 안의 {@code **}나 백틱 수가 홀수면 강조 구간을 반만 걸친 것이다. 그대로 바꾸면 짝이 깨져
     * 같은 문단 뒤쪽까지 굵어지거나 코드로 보인다. 검증기에는 짝 검사가 없어 여기서 막는다.
     */
    private static boolean halfMarkup(String span) {
        int bold = (span.length() - span.replace("**", "").length()) / 2;
        int ticks = span.length() - span.replace("`", "").length();
        return bold % 2 == 1 || ticks % 2 == 1;
    }

    /** 자리가 {@code > } 표시 뒤, 한 줄 안에 있고 그 줄이 결론 상자가 아니면 true. */
    private static boolean insidePlainQuote(String line, int lineStart, int lineEnd, int start, int end) {
        String trimmed = line.stripLeading();
        if (!trimmed.startsWith("> ") || trimmed.startsWith("> **")) {
            return false;
        }
        int textStart = lineStart + (line.length() - trimmed.length()) + 2;
        return start >= textStart && end <= lineEnd;
    }

    private static boolean lockedLine(String trimmed) {
        return trimmed.startsWith("#") || trimmed.startsWith("![") || trimmed.startsWith(">") || trimmed.startsWith("|");
    }

    /** "- **용어(영문)** — 설명"에서 줄표까지는 용어 이름이다. 줄표 뒤 설명만 고칠 수 있다. */
    private static boolean overlapsDefinitionHead(String line, int lineStart, int start) {
        if (!line.stripLeading().startsWith("- **")) {
            return false;
        }
        int dash = line.indexOf(DEFINITION_DASH);
        if (dash < 0) {
            return false;
        }
        return start < lineStart + dash + DEFINITION_DASH.length();
    }
}
