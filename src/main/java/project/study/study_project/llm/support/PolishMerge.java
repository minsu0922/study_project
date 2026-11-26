package project.study.study_project.llm.support;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 윤문본을 본문 덩어리마다 받거나 원고로 두려고 문서를 나누고 다시 잇는다.
 *
 * <p>경계는 윤문이 바꾸면 안 되는 줄이다: 소제목, 코드 블록, 인용 상자(「글의 주제」·결론 상자).
 * 이 줄들이 원고와 같으면 그 사이 본문 덩어리끼리 짝이 정해진다. 윤문이 문단을 나누거나
 * 합쳐도 경계 줄은 움직이지 않아서, 문단 개수로 짝을 맞추는 방법보다 어긋나지 않는다.
 */
public final class PolishMerge {

    private static final Pattern HEADING = Pattern.compile("^#{1,6} .*");

    private PolishMerge() {
    }

    /**
     * 나눈 문서. {@code prose}는 늘 {@code anchors}보다 하나 많다 — 첫 경계 앞과 마지막 경계 뒤까지 센다.
     * 칸은 줄 목록이다. 경계 줄이 붙어 있으면 그 사이 칸은 빈 목록이라, 이어 붙일 때 빈 줄이 생기지 않는다.
     */
    public record Parts(List<List<String>> anchors, List<List<String>> prose) {
    }

    public static Parts split(String md) {
        String[] lines = (md == null ? "" : md).split("\n", -1);
        List<List<String>> anchors = new ArrayList<>();
        List<List<String>> prose = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            int end;
            if (isFence(line)) {
                end = i + 1;
                while (end < lines.length && !isFence(lines[end])) {
                    end++;
                }
                // 닫는 줄이 없으면 끝까지를 코드로 본다
                end = Math.min(end, lines.length - 1);
            } else if (line.startsWith(">")) {
                end = i;
                while (end + 1 < lines.length && lines[end + 1].startsWith(">")) {
                    end++;
                }
            } else if (HEADING.matcher(line).matches()) {
                end = i;
            } else {
                current.add(line);
                i++;
                continue;
            }
            prose.add(current);
            current = new ArrayList<>();
            anchors.add(List.copyOf(Arrays.asList(lines).subList(i, end + 1)));
            i = end + 1;
        }
        prose.add(current);
        return new Parts(anchors, prose);
    }

    /** 두 문서의 경계 줄이 같은 순서로 같은지. 줄 끝 공백은 보지 않는다 */
    public static boolean sameAnchors(Parts a, Parts b) {
        if (a.anchors().size() != b.anchors().size()) {
            return false;
        }
        for (int i = 0; i < a.anchors().size(); i++) {
            if (!stripEnds(a.anchors().get(i)).equals(stripEnds(b.anchors().get(i)))) {
                return false;
            }
        }
        return true;
    }

    /** i번째 본문 덩어리 바로 위에 있는 가장 가까운 소제목 줄. 없으면 {@code null} */
    public static String headingAbove(Parts parts, int i) {
        for (int k = Math.min(i, parts.anchors().size()) - 1; k >= 0; k--) {
            String first = parts.anchors().get(k).get(0);
            if (HEADING.matcher(first).matches()) {
                return first.strip();
            }
        }
        return null;
    }

    public static String text(List<String> lines) {
        return String.join("\n", lines);
    }

    /**
     * i번째 본문 덩어리를 원고와 대조한다. 비었으면 통과.
     * 위 소제목을 앞에 붙여 대조한다 — 「용어 한눈에」 표는 소제목을 보고 찾기 때문이다.
     */
    public static List<String> proseDifferences(Parts original, Parts polished, int i) {
        String heading = headingAbove(original, i);
        String prefix = heading == null ? "" : heading + "\n";
        return PolishPreservation.contentDifferences(
                prefix + text(original.prose().get(i)), prefix + text(polished.prose().get(i)));
    }

    /** {@code use[i]}가 참인 덩어리는 윤문본, 거짓인 덩어리는 원고로 잇는다. 경계 줄은 원고 것을 쓴다 */
    public static String assemble(Parts original, Parts polished, boolean[] use) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < original.prose().size(); i++) {
            lines.addAll(use[i] ? polished.prose().get(i) : original.prose().get(i));
            if (i < original.anchors().size()) {
                lines.addAll(original.anchors().get(i));
            }
        }
        return String.join("\n", lines);
    }

    private static boolean isFence(String line) {
        return line.stripLeading().startsWith("```");
    }

    private static List<String> stripEnds(List<String> lines) {
        return lines.stream().map(String::stripTrailing).toList();
    }
}
