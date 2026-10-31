package project.study.study_project.llm.support;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 차트 자료(mermaid xychart-beta)를 읽어 값을 대조한다.
 *
 * <p>차트는 그림과 달리 틀려도 화면이 조용히 그린다. mermaid 11.4.1에서 확인했다:
 * 가로축 3칸에 값 2개를 주면 오류 없이 칸이 빈 차트가 나오고, "1,000"은 값 두 개로 쪼개진다.
 * 학습자는 틀린 값을 보고 문제를 푼다. 그래서 개수·범위를 여기서 직접 잰다.
 */
public final class ChartExhibitRule {

    public static final Pattern HEADER = Pattern.compile("^xychart-beta(?:\\s+horizontal)?\\s*$");

    /**
     * 고급 차트의 점 개수 상한. 화면은 차트를 480px로 그리고 라벨 하나에 60px을 준다.
     * 8개면 옆으로 넘기지 않고 한 번에 보인다. 12개로 그려 보니 폰에서 라벨이 붙었다(2026-10-31).
     */
    public static final int ADVANCED_MAX_POINTS = 8;

    /**
     * 계열(선·막대) 상한. mermaid xychart에는 범례가 없어, 선 둘이면 어느 선이 무엇인지 화면이 말해 주지 않는다.
     * 그래서 둘일 때는 막대 하나와 선 하나여야 "막대는 ○○, 선은 ○○"로 가리킬 수 있다.
     */
    public static final int MAX_SERIES = 2;

    private static final Pattern TITLE = Pattern.compile("^title\\s+\"[^\"]*\"$");
    private static final Pattern X_CATEGORIES = Pattern.compile("^x-axis(?:\\s+\"[^\"]*\")?\\s*\\[(.*)]$");
    private static final Pattern X_RANGE = Pattern.compile("^x-axis(?:\\s+\"[^\"]*\")?\\s+(-?[\\d.]+)\\s*-->\\s*(-?[\\d.]+)$");
    private static final Pattern Y_AXIS = Pattern.compile(
            "^y-axis(?:\\s+\"[^\"]*\")?(?:\\s+(-?[\\d.]+)\\s*-->\\s*(-?[\\d.]+))?$");
    private static final Pattern SERIES = Pattern.compile("^(line|bar)(?:\\s+\"[^\"]*\")?\\s*\\[(.*)]$");
    private static final Pattern QUOTED = Pattern.compile("\"[^\"]*\"");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    /** 읽어 낸 차트. categories는 가로축이 숫자 범위면 빈 목록이다. */
    public record Chart(List<String> categories, List<Series> series, Double yMin, Double yMax,
                        List<String> problems) {
        public int points() {
            return series.stream().mapToInt(s -> s.values().size()).max().orElse(0);
        }
    }

    public record Series(String kind, List<Double> values) {
    }

    private ChartExhibitRule() {
    }

    public static boolean isChart(String exhibit) {
        return exhibit != null && HEADER.matcher(exhibit.lines().findFirst().orElse("").strip()).matches();
    }

    /** 차트를 읽는다. 문법이 어긋난 줄과 값 대조 결과는 problems에 모은다. */
    public static Chart parse(String exhibit) {
        List<String> lines = exhibit.lines().toList();
        List<String> problems = new ArrayList<>();
        List<String> categories = new ArrayList<>();
        List<Series> series = new ArrayList<>();
        Double yMin = null;
        Double yMax = null;
        boolean hasXAxis = false;

        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("%%")) {
                continue;
            }
            String where = "차트 %d번째 줄".formatted(i + 1);
            Matcher m;
            if (line.startsWith("title")) {
                if (!TITLE.matcher(line).matches()) {
                    problems.add(where + ": 제목은 따옴표로 감싼다 — title \"응답 시간\" 꼴이 아니면 차트 전체가 깨진다");
                }
            } else if ((m = X_CATEGORIES.matcher(line)).matches()) {
                hasXAxis = true;
                String inner = m.group(1).strip();
                String rest = QUOTED.matcher(inner).replaceAll("").replace(",", "").strip();
                if (!rest.isEmpty()) {
                    problems.add(where + ": 가로축 라벨은 하나하나 따옴표로 감싼다 — [\"12:00\", \"12:05\"] 꼴이 아니면 차트 전체가 깨진다");
                }
                Matcher q = QUOTED.matcher(inner);
                while (q.find()) {
                    categories.add(q.group());
                }
            } else if (X_RANGE.matcher(line).matches()) {
                hasXAxis = true;
            } else if ((m = Y_AXIS.matcher(line)).matches()) {
                if (m.group(1) != null) {
                    yMin = Double.parseDouble(m.group(1));
                    yMax = Double.parseDouble(m.group(2));
                }
            } else if ((m = SERIES.matcher(line)).matches()) {
                List<Double> values = new ArrayList<>();
                boolean clean = true;
                for (String token : m.group(2).split(",")) {
                    String t = token.strip();
                    if (NUMBER.matcher(t).matches()) {
                        values.add(Double.parseDouble(t));
                    } else {
                        clean = false;
                    }
                }
                if (!clean) {
                    problems.add(where + ": 값은 쉼표로 가른 숫자만 쓴다 — 단위·천 단위 쉼표를 넣으면 값이 쪼개진다");
                }
                series.add(new Series(m.group(1), values));
            } else {
                problems.add(where + ": 차트 문법으로 읽히지 않는다 (\"%s\")".formatted(line));
            }
        }

        if (!hasXAxis) {
            problems.add("차트에 가로축(x-axis)이 없다 — 학습자가 무엇을 따라 바뀌는 값인지 알 수 없다");
        }
        if (series.isEmpty()) {
            problems.add("차트에 값(line·bar)이 없다");
        }
        if (!categories.isEmpty()) {
            for (Series s : series) {
                if (s.values().size() != categories.size()) {
                    problems.add("%s 값이 %d개인데 가로축 칸은 %d개다 — 화면은 오류 없이 틀린 차트를 그린다"
                            .formatted(label(s), s.values().size(), categories.size()));
                }
            }
        }
        if (yMin != null) {
            for (Series s : series) {
                for (double v : s.values()) {
                    if (v < yMin || v > yMax) {
                        problems.add("%s 값 %s가 세로축 범위 %s~%s 밖이다 — 화면에서 잘린다"
                                .formatted(label(s), fmt(v), fmt(yMin), fmt(yMax)));
                        break;
                    }
                }
            }
        }
        if (series.size() > MAX_SERIES) {
            problems.add("계열이 %d개다 (상한 %d개 — 범례가 없어 어느 것이 무엇인지 안 보인다)"
                    .formatted(series.size(), MAX_SERIES));
        } else if (series.size() == 2 && series.get(0).kind().equals(series.get(1).kind())) {
            problems.add("같은 종류 계열이 둘이다 — 범례가 없어 구분이 안 된다. 하나는 막대(bar), 하나는 선(line)으로 쓴다");
        }
        return new Chart(categories, series, yMin, yMax, problems);
    }

    private static String label(Series s) {
        return s.kind().equals("bar") ? "막대" : "선";
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
