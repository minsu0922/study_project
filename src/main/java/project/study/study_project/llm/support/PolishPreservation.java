package project.study.study_project.llm.support;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 윤문 전후에 내용이 그대로인지 코드로 대조한다(설계 docs/superpowers/specs/2026-11-18-document-polish-design.md).
 *
 * <p>윤문은 문장만 고치는 단계라, 여기 항목이 하나라도 달라지면 배치는 그 편의 원본을 쓴다.
 * 사실 검수는 "틀린 말"만 잡고 "빠진 말"은 못 잡아서 이 대조가 따로 있다.
 */
public final class PolishPreservation {

    /**
     * 빠지면 참이지만 과장된 문장이 되는 정도 부사(사용자 확정 2026-11-18). "경우"는 넣지 않는다 —
     * 글쓰기 규칙이 "~하는 경우"를 "~하면"으로 바꾸게 해서, 넣으면 윤문이 자주 원본으로 돌아간다.
     */
    public static final List<String> CONDITION_WORDS = List.of(
            "기본적으로", "대부분", "대개", "보통", "주로", "흔히", "일반적으로", "거의",
            "가끔", "종종", "드물게", "최대", "최소", "적어도", "늦어도");

    private static final Pattern FENCE = Pattern.compile("(?ms)^```.*?^```[ \\t]*$");
    private static final Pattern HEADING = Pattern.compile("(?m)^#{1,6} .*$");
    private static final Pattern FIGURE = Pattern.compile("!\\[[^\\]]*\\]\\([^)]*\\)");
    private static final Pattern LINK = Pattern.compile("(?<!!)\\[[^\\]]*\\]\\([^)]*\\)");
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,:]\\d+)*");

    private PolishPreservation() {
    }

    /** @return 다른 항목을 사람이 읽을 문장으로. 비었으면 통과 */
    public static List<String> differences(String original, String polished) {
        List<String> out = new ArrayList<>();
        String before = original == null ? "" : original;
        String after = polished == null ? "" : polished;

        // 소제목 목록만으로는 앞에 붙은 안내문을 못 잡는다
        if (!firstLine(before).equals(firstLine(after))) {
            out.add("첫 줄이 다릅니다: " + firstLine(after));
        }
        compareList(out, "코드 블록", all(FENCE, before), all(FENCE, after));
        String proseBefore = FENCE.matcher(before).replaceAll("");
        String proseAfter = FENCE.matcher(after).replaceAll("");
        compareList(out, "소제목", all(HEADING, proseBefore), all(HEADING, proseAfter));
        compareList(out, "그림 줄", all(FIGURE, proseBefore), all(FIGURE, proseAfter));
        compareList(out, "링크", all(LINK, proseBefore), all(LINK, proseAfter));

        // 같은 수치를 되풀이한 문장을 합치면 횟수만 준다. 뜻은 그대로라 종류만 본다(사용자 확정)
        Set<String> numbersBefore = new TreeSet<>(all(NUMBER, before));
        Set<String> numbersAfter = new TreeSet<>(all(NUMBER, after));
        if (!numbersBefore.equals(numbersAfter)) {
            out.add("수치가 다릅니다: " + symmetricDifference(numbersBefore, numbersAfter));
        }

        compareList(out, "용어 한눈에 표의 용어", glossaryTerms(proseBefore), glossaryTerms(proseAfter));

        for (String word : CONDITION_WORDS) {
            int b = count(proseBefore, word);
            int a = count(proseAfter, word);
            if (a != b) {
                out.add("조건어 \"%s\" 횟수가 다릅니다: %d → %d".formatted(word, b, a));
            }
        }
        return out;
    }

    private static String firstLine(String s) {
        String stripped = s.stripLeading();
        int nl = stripped.indexOf('\n');
        return (nl < 0 ? stripped : stripped.substring(0, nl)).strip();
    }

    private static void compareList(List<String> out, String name, List<String> before, List<String> after) {
        if (before.equals(after)) {
            return;
        }
        List<String> missing = new ArrayList<>(before);
        missing.removeAll(after);
        List<String> added = new ArrayList<>(after);
        added.removeAll(before);
        out.add("%s이(가) 다릅니다: 빠짐 %s, 생김 %s".formatted(name, missing, added));
    }

    private static List<String> all(Pattern p, String s) {
        List<String> found = new ArrayList<>();
        Matcher m = p.matcher(s);
        while (m.find()) {
            found.add(m.group().strip());
        }
        return found;
    }

    private static Set<String> symmetricDifference(Set<String> a, Set<String> b) {
        Set<String> diff = new LinkedHashSet<>(a);
        diff.addAll(b);
        Set<String> both = new TreeSet<>(a);
        both.retainAll(b);
        diff.removeAll(both);
        return diff;
    }

    /** {@code ## 용어 한눈에} 절 표의 첫 칸. 머리줄과 구분줄은 뺀다. */
    private static List<String> glossaryTerms(String prose) {
        List<String> terms = new ArrayList<>();
        boolean inSection = false;
        boolean headerSeen = false;
        for (String line : prose.split("\n")) {
            if (line.startsWith("## ")) {
                inSection = line.strip().equals("## 용어 한눈에");
                headerSeen = false;
                continue;
            }
            if (!inSection || !line.strip().startsWith("|")) {
                continue;
            }
            if (!headerSeen) {
                headerSeen = true;
                continue;
            }
            String[] cells = line.strip().split("\\|");
            if (cells.length < 2 || cells[1].strip().matches("-+")) {
                continue;
            }
            terms.add(cells[1].strip());
        }
        return terms;
    }

    private static int count(String s, String word) {
        int n = 0;
        for (int i = s.indexOf(word); i >= 0; i = s.indexOf(word, i + word.length())) {
            n++;
        }
        return n;
    }
}
