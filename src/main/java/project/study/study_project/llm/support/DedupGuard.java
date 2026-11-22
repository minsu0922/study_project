package project.study.study_project.llm.support;

import project.study.study_project.llm.client.DedupProposal;
import project.study.study_project.llm.client.DocumentEdition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 중복 검사 후보를 지워도 되는지 코드로 판정하고 지운다(설계 2026-11-22-document-dedup-design.md 3~5절).
 * 모델은 찾기만 한다. 수치·조건·문제 재료가 사라지는지는 모델의 판단에 맡기지 않는다.
 */
public final class DedupGuard {

    /** 2026-11-21 문서를 사람이 줄였을 때 6~9%였던 값을 보고 정한 시작값. 평가 뒤 다시 잡는다. */
    public static final double MAX_REMOVED_RATIO = 0.10;

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,:]\\d+)*");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`\\n]+)`");
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern BLOCK_LINE = Pattern.compile("^(\\s|>|\\||!\\[|%%|[-*+]\\s|\\d+\\.\\s).*");

    private static final Set<String> PROTECTED_SECTIONS = Set.of(
            "이 글을 읽기 전에", "정리", "심화편 정리", "참고 자료", "핵심 요약", "한 줄로 정리하면",
            "면접 한 줄 요약", "용어 한눈에", "자주 하는 오해", "면접에서 이렇게 물어본다");
    private static final Set<String> PROTECTED_SUBSECTIONS = Set.of("헷갈리기 쉬운 점", "흔한 오해", "용어 한눈에");

    private static final String ADVANCED_PRACTICE = "실무에서 어디에 나타나는가";

    /** "이·그"로 시작하는 뒷문장은 지운 문장을 받을 수 있다. "이름"처럼 아닌 말까지 막아도 안 지울 뿐이다. */
    private static final List<String> LINKING_STARTS = List.of("이", "그", "반면", "또한", "하지만", "따라서", "즉", "여기서");

    private DedupGuard() {
    }

    public record Accepted(int start, int end, String removed, String kept, String section, String reason) {
    }

    public record Rejected(String target, String why) {
    }

    public record Decision(List<Accepted> accepted, List<Rejected> rejected) {

        public int removedChars() {
            return accepted.stream().mapToInt(a -> a.end() - a.start()).sum();
        }
    }

    private enum Kind { BLANK, HEADING, PROSE, BLOCK }

    private enum Zone { FREE, INTERMEDIATE, ADVANCED }

    private record Line(int start, int end, Kind kind, String h2, String h3, Zone zone, boolean guarded) {
    }

    private record Located(DedupProposal proposal, int[] target, int[] kept) {
    }

    /** 후보마다 검사해 통과한 것과 버린 것을 가른다. 후보끼리 겹치면 앞의 것만 남긴다. */
    public static Decision check(String contentMd, DocumentEdition edition, List<DedupProposal> proposals) {
        List<Line> lines = parse(contentMd, edition);
        List<Rejected> rejected = new ArrayList<>();
        List<Located> located = new ArrayList<>();
        for (DedupProposal p : proposals == null ? List.<DedupProposal>of() : proposals) {
            if (p == null) {
                continue;
            }
            Optional<int[]> target = TextSpans.locateOnce(contentMd, p.target());
            Optional<int[]> kept = TextSpans.locateOnce(contentMd, p.kept());
            if (target.isEmpty() || kept.isEmpty()) {
                rejected.add(new Rejected(p.target(), "본문에서 한 곳으로 찾지 못함"));
                continue;
            }
            located.add(new Located(p, target.get(), kept.get()));
        }
        located.sort(Comparator.comparingInt(l -> l.target()[0]));

        List<Accepted> accepted = new ArrayList<>();
        for (Located l : located) {
            String why = whyNot(contentMd, lines, l, accepted);
            if (why != null) {
                rejected.add(new Rejected(l.proposal().target(), why));
                continue;
            }
            int[] t = l.target();
            accepted.add(new Accepted(t[0], t[1], contentMd.substring(t[0], t[1]),
                    contentMd.substring(l.kept()[0], l.kept()[1]), lineAt(lines, t[0]).h2(), l.proposal().reason()));
        }
        return new Decision(accepted, rejected);
    }

    private static String whyNot(String md, List<Line> lines, Located l, List<Accepted> accepted) {
        int start = l.target()[0], end = l.target()[1];
        int keptStart = l.kept()[0], keptEnd = l.kept()[1];
        if (keptEnd > start) {
            return "남길 문장이 지울 문장보다 앞에 없음";
        }
        for (Accepted a : accepted) {
            if (start < a.end() && a.start() < end) {
                return "다른 후보와 겹침";
            }
            if (keptStart < a.end() && a.start() < keptEnd) {
                return "남길 문장이 이미 지워짐";
            }
        }
        int first = indexAt(lines, start), last = indexAt(lines, end - 1);
        for (int i = first; i <= last; i++) {
            if (lines.get(i).kind() != Kind.PROSE) {
                return "일반 문단이 아님";
            }
            if (lines.get(i).guarded()) {
                return "지우면 안 되는 절";
            }
        }
        String raw = md.substring(start, end);
        if (raw.contains("**")) {
            return "굵은 글씨 용어 문장";
        }

        int paraFirst = first, paraLast = last;
        while (paraFirst > 0 && lines.get(paraFirst - 1).kind() == Kind.PROSE) {
            paraFirst--;
        }
        while (paraLast + 1 < lines.size() && lines.get(paraLast + 1).kind() == Kind.PROSE) {
            paraLast++;
        }
        int paraStart = lines.get(paraFirst).start(), paraEnd = lines.get(paraLast).end();
        boolean opensParagraph = md.substring(paraStart, start).isBlank();
        boolean closesParagraph = md.substring(end, paraEnd).isBlank();
        if (!opensParagraph && !endsSentence(md, start)) {
            return "문장 처음이 아님";
        }
        if (!endsSentence(md, end) || (end < md.length() && !Character.isWhitespace(md.charAt(end)))) {
            return "문장 끝이 아님";
        }
        Kind before = neighbor(lines, paraFirst, -1), after = neighbor(lines, paraLast, 1);
        if (opensParagraph && before == Kind.HEADING) {
            return "소제목 바로 아래 첫 문장";
        }
        if ((opensParagraph && before == Kind.BLOCK) || (closesParagraph && after == Kind.BLOCK)) {
            return "그림·코드·표·목록 바로 앞뒤 문장";
        }
        if (!closesParagraph && startsWithLink(md.substring(end, paraEnd))) {
            return "뒷문장이 지울 문장을 받음";
        }
        String kept = md.substring(keptStart, keptEnd);
        String lost = lostFact(raw, kept);
        if (lost != null) {
            return "남길 문장에 없는 사실: " + lost;
        }
        Zone zone = lineAt(lines, start).zone();
        if (zone != Zone.FREE && lineAt(lines, keptStart).zone() != zone) {
            return "문제 재료 구역 밖의 문장으로 대신함";
        }
        return null;
    }

    /** 지울 문장의 수치·코드·정도 부사·가능 표현 가운데 남길 문장에 없는 것. 다 있으면 {@code null}. */
    private static String lostFact(String target, String kept) {
        Set<String> keptNumbers = allOf(NUMBER, kept, 0);
        for (String n : allOf(NUMBER, target, 0)) {
            if (!keptNumbers.contains(n)) {
                return n;
            }
        }
        for (String code : allOf(INLINE_CODE, target, 1)) {
            if (!kept.contains(code)) {
                return code;
            }
        }
        for (String word : PolishPreservation.CONDITION_WORDS) {
            if (target.contains(word) && !kept.contains(word)) {
                return word;
            }
        }
        if ((target.contains("수 있") || target.contains("수도 있"))
                && !(kept.contains("수 있") || kept.contains("수도 있"))) {
            return "가능 표현";
        }
        return null;
    }

    private static Set<String> allOf(Pattern pattern, String text, int group) {
        Set<String> found = new HashSet<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            found.add(m.group(group));
        }
        return found;
    }

    private static boolean startsWithLink(String rest) {
        String next = rest.strip().replaceFirst("^[*`\"“]+", "");
        return LINKING_STARTS.stream().anyMatch(next::startsWith);
    }

    /** {@code at} 바로 앞이 문장 끝인지. 닫는 따옴표·강조 기호·공백은 건너뛴다. */
    private static boolean endsSentence(String md, int at) {
        int i = at - 1;
        while (i >= 0 && (Character.isWhitespace(md.charAt(i)) || "\"”’')*`".indexOf(md.charAt(i)) >= 0)) {
            i--;
        }
        return i >= 0 && ".?!".indexOf(md.charAt(i)) >= 0;
    }

    private static Kind neighbor(List<Line> lines, int from, int step) {
        for (int i = from + step; i >= 0 && i < lines.size(); i += step) {
            if (lines.get(i).kind() != Kind.BLANK) {
                return lines.get(i).kind();
            }
        }
        return Kind.BLANK;
    }

    private static Line lineAt(List<Line> lines, int offset) {
        return lines.get(indexAt(lines, offset));
    }

    private static int indexAt(List<Line> lines, int offset) {
        int lo = 0, hi = lines.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (lines.get(mid).start() <= offset) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    private static List<Line> parse(String md, DocumentEdition edition) {
        List<Line> lines = new ArrayList<>();
        boolean fence = false;
        String h2 = "", h3 = "";
        int pos = 0;
        while (pos <= md.length()) {
            int nl = md.indexOf('\n', pos);
            int end = nl < 0 ? md.length() : nl;
            String text = md.substring(pos, end);
            if (text.endsWith("\r")) {
                text = text.substring(0, text.length() - 1);
            }
            Kind kind;
            if (text.strip().startsWith("```")) {
                fence = !fence;
                kind = Kind.BLOCK;
            } else if (fence) {
                kind = Kind.BLOCK;
            } else if (text.isBlank()) {
                kind = Kind.BLANK;
            } else {
                Matcher heading = HEADING.matcher(text);
                if (heading.matches()) {
                    kind = Kind.HEADING;
                    int level = heading.group(1).length();
                    String name = heading.group(2).strip();
                    if (level <= 2) {
                        h2 = level == 2 ? name : "";
                        h3 = "";
                    } else if (level == 3) {
                        h3 = name;
                    }
                } else {
                    kind = BLOCK_LINE.matcher(text).matches() ? Kind.BLOCK : Kind.PROSE;
                }
            }
            lines.add(new Line(pos, pos + text.length(), kind, h2, h3, zoneOf(edition, h2, h3), guarded(edition, h2, h3)));
            if (nl < 0) {
                break;
            }
            pos = nl + 1;
        }
        return lines;
    }

    private static boolean guarded(DocumentEdition edition, String h2, String h3) {
        return h2.isEmpty() || PROTECTED_SECTIONS.contains(h2) || PROTECTED_SUBSECTIONS.contains(h3)
                || (edition == DocumentEdition.ADVANCED && h2.equals(ADVANCED_PRACTICE) && !h3.isEmpty());
    }

    private static Zone zoneOf(DocumentEdition edition, String h2, String h3) {
        if (edition == DocumentEdition.ADVANCED) {
            if (h2.equals(ADVANCED_PRACTICE)) {
                return Zone.INTERMEDIATE;
            }
            return h2.equals("어떤 때 통하지 않는가") || h2.equals("면접에서 이렇게 물어본다") ? Zone.ADVANCED : Zone.FREE;
        }
        return h2.equals("실무에서는 이렇게 쓴다") || h3.equals("왜 이렇게 설계됐는가") ? Zone.INTERMEDIATE : Zone.FREE;
    }

    /** 통과한 문장을 지운다. 문단이 통째로 비면 그 빈 줄까지 함께 지운다. */
    public static String apply(String contentMd, List<Accepted> accepted) {
        StringBuilder sb = new StringBuilder(contentMd);
        List<Accepted> backwards = new ArrayList<>(accepted);
        backwards.sort(Comparator.comparingInt(Accepted::start).reversed());
        for (Accepted a : backwards) {
            removeSpan(sb, a.start(), a.end());
        }
        return sb.toString();
    }

    private static void removeSpan(StringBuilder sb, int start, int end) {
        int left = start, right = end;
        while (left > 0 && isInlineSpace(sb.charAt(left - 1))) {
            left--;
        }
        while (right < sb.length() && isInlineSpace(sb.charAt(right))) {
            right++;
        }
        boolean lineStart = left == 0 || sb.charAt(left - 1) == '\n';
        boolean lineEnd = right == sb.length() || sb.charAt(right) == '\n' || sb.charAt(right) == '\r';
        if (lineStart && lineEnd) {
            if (right < sb.length() && sb.charAt(right) == '\r') {
                right++;
            }
            if (right < sb.length()) {
                right++;
            }
            sb.delete(left, right);
            collapseBlankLine(sb, left);
        } else if (lineEnd) {
            sb.delete(left, right);
        } else {
            sb.delete(start, right);
        }
    }

    /** 문단이 사라진 자리에 빈 줄 두 개가 겹치면 하나로 줄인다. */
    private static void collapseBlankLine(StringBuilder sb, int at) {
        boolean blankBefore = at >= 2 && sb.charAt(at - 1) == '\n' && sb.charAt(at - 2) == '\n';
        if (blankBefore && at < sb.length() && sb.charAt(at) == '\n') {
            sb.deleteCharAt(at);
        } else if (blankBefore && at + 1 < sb.length() && sb.charAt(at) == '\r' && sb.charAt(at + 1) == '\n') {
            sb.delete(at, at + 2);
        }
    }

    private static boolean isInlineSpace(char c) {
        return c == ' ' || c == '\t';
    }
}
