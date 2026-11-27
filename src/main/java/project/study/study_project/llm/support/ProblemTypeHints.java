package project.study.study_project.llm.support;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 문서 본문에서 객관식 말고 낼 수 있는 문제 유형과 그 재료 절을 뽑는다.
 *
 * <p>문서 생성 모델에게 표시를 맡기지 않고 글 형식으로 판단한다. 번호 목록·「용어 한눈에」 표·오해 절은
 * 생성 프롬프트가 정한 모양이라 코드로 확실히 찾고, 이미 나간 문서에도 그대로 쓸 수 있다.
 * "단계가 정말 순서를 따지는가" 같은 뜻 판단은 하지 않는다. 그것은 문제를 만드는 쪽의 몫이다.
 */
public final class ProblemTypeHints {

    /** 품질 기준이 "넷이 좋다"라 넷부터 센다(AdminProblemService.PAIR_MIN·ORDER_MIN은 저장 하한일 뿐이다). */
    static final int MIN_STEPS = 4;
    static final int MIN_PAIRS = 4;

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^(\\d+)\\.\\s+\\S.*$");
    // 오해 항목은 두 모양으로 쓴다: 줄 그은 소제목(### ~~"…"~~)과 굵은 따옴표 줄(**"…"**)
    private static final Pattern MYTH_ITEM = Pattern.compile("^(#{3,6}\\s+~~.+~~|\\*\\*[\"“].+)\\s*$");

    /**
     * @param type     ProblemType 이름(ORDERING·MATCHING·OX)
     * @param label    화면 표기
     * @param count    재료 개수. 순서 배열은 목록 수, 짝짓기는 표의 행 수, OX는 오해 항목 수
     * @param sections 재료가 든 절 이름(나온 순서, 중복 없음)
     */
    public record Hint(String type, String label, int count, List<String> sections) {
    }

    private ProblemTypeHints() {
    }

    public static List<Hint> of(String contentMd) {
        if (contentMd == null || contentMd.isBlank()) {
            return List.of();
        }
        List<String> lines = withoutCodeBlocks(contentMd.lines().toList());
        List<Hint> hints = new ArrayList<>();
        addIfAny(hints, ordering(lines));
        addIfAny(hints, matching(lines));
        addIfAny(hints, ox(lines));
        return List.copyOf(hints);
    }

    private static void addIfAny(List<Hint> hints, Hint hint) {
        if (hint.count() > 0) {
            hints.add(hint);
        }
    }

    /** 1부터 이어지는 번호 목록 가운데 MIN_STEPS줄 이상인 것. 절 이름은 목록 바로 위 소제목이다 */
    private static Hint ordering(List<String> lines) {
        int lists = 0;
        Set<String> sections = new LinkedHashSet<>();
        String heading = "";
        int run = 0;
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;   // 항목 사이에 빈 줄을 둔 목록도 한 목록이다
            }
            Matcher n = NUMBERED.matcher(line);
            if (n.matches() && Integer.parseInt(n.group(1)) == run + 1) {
                run++;
                continue;
            }
            // 목록이 끝났다. 소제목을 바꾸기 전에 센다 — 순서가 뒤집히면 다음 절 이름으로 적힌다
            if (run >= MIN_STEPS) {
                lists++;
                sections.add(heading);
            }
            run = n.matches() && "1".equals(n.group(1)) ? 1 : 0;
            Matcher h = HEADING.matcher(line);
            if (h.matches()) {
                heading = clean(h.group(2));
            }
        }
        if (run >= MIN_STEPS) {
            lists++;
            sections.add(heading);
        }
        return new Hint("ORDERING", "순서 배열", lists, List.copyOf(sections));
    }

    /** 「용어 한눈에」 절의 표 행 수. 머리 행과 구분 행은 세지 않는다 */
    private static Hint matching(List<String> lines) {
        int rows = 0;
        Set<String> sections = new LinkedHashSet<>();
        for (Section s : sectionsTitled(lines, "용어 한눈에")) {
            int tableLines = 0;
            int bodyRows = 0;
            for (String raw : s.body()) {
                String line = raw.strip();
                if (!line.startsWith("|")) {
                    continue;
                }
                tableLines++;
                if (tableLines > 1 && !line.matches("^\\|[\\s:|-]+\\|?$")) {
                    bodyRows++;
                }
            }
            if (bodyRows >= MIN_PAIRS) {
                rows += bodyRows;
                sections.add(s.title());
            }
        }
        return new Hint("MATCHING", "짝짓기", rows, List.copyOf(sections));
    }

    /** 제목에 "오해"가 든 절의 오해 항목 수 */
    private static Hint ox(List<String> lines) {
        int items = 0;
        Set<String> sections = new LinkedHashSet<>();
        for (Section s : sectionsTitled(lines, "오해")) {
            int found = (int) s.body().stream().map(String::strip).filter(l -> MYTH_ITEM.matcher(l).matches()).count();
            if (found > 0) {
                items += found;
                sections.add(s.title());
            }
        }
        return new Hint("OX", "OX", items, List.copyOf(sections));
    }

    private record Section(String title, List<String> body) {
    }

    /** 제목에 keyword가 든 소제목부터, 같거나 더 높은 수준의 다음 소제목 전까지 */
    private static List<Section> sectionsTitled(List<String> lines, String keyword) {
        List<Section> found = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher h = HEADING.matcher(lines.get(i).strip());
            if (!h.matches() || !h.group(2).contains(keyword)) {
                continue;
            }
            int level = h.group(1).length();
            List<String> body = new ArrayList<>();
            for (int j = i + 1; j < lines.size(); j++) {
                Matcher next = HEADING.matcher(lines.get(j).strip());
                if (next.matches() && next.group(1).length() <= level) {
                    break;
                }
                body.add(lines.get(j));
            }
            found.add(new Section(clean(h.group(2)), body));
        }
        return found;
    }

    /** 코드 블록 안의 "1. "이나 "|"를 재료로 세지 않게 빈 줄로 바꾼다(줄 수는 그대로 둔다) */
    private static List<String> withoutCodeBlocks(List<String> lines) {
        List<String> out = new ArrayList<>(lines.size());
        boolean inFence = false;
        for (String line : lines) {
            if (line.strip().startsWith("```")) {
                inFence = !inFence;
                out.add("");
                continue;
            }
            out.add(inFence ? "" : line);
        }
        return out;
    }

    private static String clean(String title) {
        return title.replace("**", "").replace("~~", "").strip();
    }
}
