package project.study.study_project.llm.support;

import java.util.ArrayList;
import java.util.List;

/**
 * 사람이 쓴 「글의 주제」(V48)를 문서 맨 앞 상자로 끼우고, 문서에서 다시 꺼낸다.
 *
 * <p>모델에게 "그대로 옮겨라"고만 시키면 조사·띄어쓰기를 조금씩 고친다. 그래서 상자는 코드가
 * 끼운다. 모델이 쓴 상자는 버리고, 없으면 H1 바로 아래에 넣는다.
 */
public final class TopicBoxes {

    static final String HEAD = "> **글의 주제**";

    private TopicBoxes() {
    }

    /** 문단 글 → 마크다운 상자. 문단 사이에는 {@code >} 한 줄을 둔다. */
    public static String render(String box) {
        StringBuilder sb = new StringBuilder(HEAD).append('\n');
        for (String para : paragraphs(box)) {
            sb.append(">\n");
            for (String line : para.split("\n")) {
                sb.append("> ").append(line.strip()).append('\n');
            }
        }
        return sb.toString().stripTrailing();
    }

    /**
     * 본문의 「글의 주제」 상자를 {@code box}로 바꾼다. 상자가 없으면 H1 바로 아래에 넣는다.
     * {@code box}가 비었으면 본문을 그대로 돌려준다.
     */
    public static String apply(String contentMd, String box) {
        if (box == null || box.isBlank() || contentMd == null) {
            return contentMd;
        }
        String rendered = render(box);
        List<String> lines = new ArrayList<>(List.of(contentMd.split("\n", -1)));
        int start = indexOfHead(lines);
        if (start >= 0) {
            int end = start + 1;
            while (end < lines.size() && lines.get(end).startsWith(">")) {
                end++;
            }
            lines.subList(start, end).clear();
            lines.add(start, rendered);
            return String.join("\n", lines);
        }
        int h1 = 0;
        while (h1 < lines.size() && !lines.get(h1).startsWith("# ")) {
            h1++;
        }
        if (h1 == lines.size()) {
            return rendered + "\n\n" + contentMd;
        }
        lines.add(h1 + 1, "");
        lines.add(h1 + 2, rendered);
        return String.join("\n", lines);
    }

    /** 본문의 「글의 주제」 상자 글 — 문단 사이는 빈 줄 하나다. 상자가 없으면 {@code null}. */
    public static String extract(String contentMd) {
        if (contentMd == null) {
            return null;
        }
        List<String> lines = List.of(contentMd.split("\n", -1));
        int start = indexOfHead(lines);
        if (start < 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = start + 1; i < lines.size() && lines.get(i).startsWith(">"); i++) {
            String body = lines.get(i).substring(1).strip();
            sb.append(body).append('\n');
        }
        return String.join("\n\n", paragraphs(sb.toString()));
    }

    /** 사람이 쓴 글과 본문의 상자가 같은가 — 줄 안의 앞뒤 공백 차이는 보지 않는다. */
    public static boolean matches(String contentMd, String box) {
        String inDoc = extract(contentMd);
        return inDoc != null && inDoc.equals(String.join("\n\n", paragraphs(box)));
    }

    private static int indexOfHead(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).strip().equals(HEAD)) {
                return i;
            }
        }
        return -1;
    }

    /** 빈 줄로 문단을 가른다. 문단 안 줄은 앞뒤 공백만 걷는다. */
    static List<String> paragraphs(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        for (String raw : text.replace("\r\n", "\n").split("\n", -1)) {
            String line = raw.strip();
            if (line.isEmpty()) {
                if (!cur.isEmpty()) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                continue;
            }
            if (!cur.isEmpty()) {
                cur.append('\n');
            }
            cur.append(line);
        }
        if (!cur.isEmpty()) {
            out.add(cur.toString());
        }
        return out;
    }
}
