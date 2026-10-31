package project.study.study_project.llm.support;

import project.study.study_project.llm.client.GeneratedProblemItem;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 그림 자료(mermaid 흐름도·시퀀스)의 문법 경고.
 *
 * <p>진짜 문법 검사는 브라우저의 mermaid만 할 수 있다. 배치는 자바에서 돌아서 여기서는 모델이 자주
 * 틀리는 자리만 본다. 놓친 오류는 화면이 원문으로 남기고, 검수함이 "그림 문법 오류"로 알린다.
 */
public final class DiagramExhibitRule {

    /** 아직 열지 않은 mermaid 그림 종류. 화면이 그림으로 그리지 않아 글자로 보인다. */
    private static final Pattern OTHER_DIAGRAM = Pattern.compile(
            "^(?:stateDiagram|classDiagram|erDiagram|gantt|pie|journey|mindmap|timeline|gitGraph|quadrantChart)\\b.*");

    /**
     * 흐름도 글상자 [..] 안의 따옴표 없는 괄호. mermaid는 이것을 다른 모양의 상자로 읽어 그림 전체가 깨진다.
     * [( 로 여는 것은 원통(DB) 모양이라 뺀다.
     */
    private static final Pattern UNQUOTED_PAREN_IN_BOX = Pattern.compile("\\[(?!\\()[^\\]\"]*[()][^\\]\"]*]");

    private static final Pattern SEQUENCE_MESSAGE = Pattern.compile(
            "^[^:\\s][^:]*?\\s*(?:-->>|->>|-->|->|--x|-x|--\\)|-\\))\\s*[+-]?[^:]+:.*$");
    private static final Pattern SEQUENCE_STATEMENT = Pattern.compile(
            "^(?:(?:participant|actor)\\s+\\S.*|autonumber|(?:activate|deactivate)\\s+\\S+"
                    + "|Note\\s+(?:left of|right of|over)\\s+[^:]+:.*"
                    + "|(?:loop|alt|else|opt|par|and|critical|break|rect)(?:\\s.*)?|end)$");

    private DiagramExhibitRule() {
    }

    /** 첫 줄이 열지 않은 그림 종류인가. 그 자료는 그림이 아니라 글자로 저장되고 보인다. */
    public static boolean isUnsupportedDiagram(String exhibit) {
        String first = exhibit.lines().findFirst().orElse("").strip();
        return OTHER_DIAGRAM.matcher(first).matches();
    }

    /** 흐름도·시퀀스 자료의 경고. 그림 자료가 아니면 빈 목록. */
    public static List<String> warningsOf(GeneratedProblemItem item) {
        if (!item.hasDiagramExhibit()) {
            return List.of();
        }
        List<String> lines = item.normalizedExhibit().lines().toList();
        boolean sequence = lines.get(0).strip().startsWith("sequenceDiagram");
        List<String> warnings = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("%%")) {
                continue;
            }
            String problem = sequence ? sequenceProblemOf(line) : flowchartProblemOf(line);
            if (problem != null) {
                warnings.add("그림 %d번째 줄: %s (\"%s\")".formatted(i + 1, problem, line));
            }
        }
        return warnings;
    }

    private static String sequenceProblemOf(String line) {
        if (SEQUENCE_STATEMENT.matcher(line).matches()) {
            return null;
        }
        if (!SEQUENCE_MESSAGE.matcher(line).matches()) {
            return "시퀀스 문법으로 읽히지 않는다 — 메시지는 \"보내는쪽->>받는쪽: 내용\" 꼴이다";
        }
        String text = line.substring(line.indexOf(':') + 1);
        if (text.contains(";") || text.contains("#")) {
            return "메시지에 ; 나 # 가 있다 — mermaid가 문장 끝이나 특수 문자로 읽어 그림이 깨진다";
        }
        return null;
    }

    private static String flowchartProblemOf(String line) {
        if (UNQUOTED_PAREN_IN_BOX.matcher(line).find()) {
            return "글상자 안 괄호를 따옴표 없이 썼다 — A[\"연결 (TCP)\"]처럼 감싸야 그림이 깨지지 않는다";
        }
        if (count(line, '[') != count(line, ']') || count(line, '{') != count(line, '}')
                || count(line, '|') % 2 != 0) {
            return "괄호나 화살표 글자(|..|)의 짝이 맞지 않는다";
        }
        return null;
    }

    private static long count(String s, char c) {
        return s.chars().filter(ch -> ch == c).count();
    }
}
