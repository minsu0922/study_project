package project.study.study_project.llm.support;

import project.study.study_project.llm.client.GeneratedProblemItem;
import project.study.study_project.llm.client.GeneratedProblemItem.GeneratedChoice;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 해설·오답 설명의 꼴을 코드로 센다. 기준 번호는 docs/superpowers/specs/2026-11-16-explanation-review-design.md의 기준표다.
 *
 * <p>생성 직후 경고(1차 검사)와, AI가 고친 해설을 넣기 전 재검사에 같은 규칙을 쓴다.
 * 두 곳이 다른 기준을 쓰면 넣은 글이 다음 날 경고에 걸린다.
 */
public final class ExplanationFormRule {

    /**
     * 해설 맨 끝의 다시 읽을 절 안내. 첫 따옴표 안이 절 이름이다. "'바탕이 되는 개념' 3단계 표를",
     * "'X' 절의 'Y' 항목을"처럼 뒤에 붙는 말은 받는다. 승인된 538문제 중 이런 꼴이 75개다(2026-11-17)
     */
    private static final Pattern POINTER = Pattern.compile("\\((입문편|심화편|문서|이 글)의 '([^']+)'[^()]*다시 [^()]+\\)\\.?$");
    private static final Pattern HEADING = Pattern.compile("(?m)^#{2,4}\\s+(.+)$");
    private static final int MIN_STEPS = 3;

    public record FormIssue(String criterion, String message) {
    }

    private ExplanationFormRule() {
    }

    public static boolean passes(GeneratedProblemItem item, String sourceSlug, String sourceMd) {
        return issuesOf(item, sourceSlug, sourceMd).isEmpty();
    }

    /**
     * @param sourceSlug 근거 문서 slug. {@code -advanced}로 끝나면 심화편이다. {@code null}이면 편을 견주지 않는다
     * @param sourceMd   근거 문서 본문(입문편 발췌가 붙을 수 있다). {@code null}이면 안내 줄의 꼴만 본다
     */
    public static List<FormIssue> issuesOf(GeneratedProblemItem item, String sourceSlug, String sourceMd) {
        List<FormIssue> issues = new ArrayList<>();
        String explanation = item.explanation() == null ? "" : item.explanation().strip();
        if (!explanation.isEmpty()) {
            explanationIssues(explanation, sourceSlug, sourceMd, issues);
        }
        String shortRationales = shortRationalePositions(item);
        if (!shortRationales.isEmpty()) {
            issues.add(new FormIssue("C2", "오답 설명이 비었거나 짧음 (%s번째 보기, 기준 %d자)"
                    .formatted(shortRationales, ProblemItemRule.RATIONALE_MIN)));
        }
        return issues;
    }

    private static void explanationIssues(String explanation, String sourceSlug, String sourceMd,
                                          List<FormIssue> issues) {
        List<String> lines = new ArrayList<>(explanation.lines().map(String::strip).filter(l -> !l.isEmpty()).toList());
        Matcher pointer = POINTER.matcher(lines.get(lines.size() - 1));
        boolean hasPointer = pointer.find();
        if (hasPointer && pointer.start() > 0) {
            // 안내를 맺음 문장 뒤에 이어 쓴 해설이 있다. 안내 앞부분을 한 줄로 떼어 센다
            lines.add(lines.size() - 1, lines.get(lines.size() - 1).substring(0, pointer.start()).strip());
        }

        String a4 = hasPointer ? pointerProblem(pointer.group(1), pointer.group(2), sourceSlug, sourceMd)
                : "마지막 줄에 다시 읽을 절 안내가 없음 (\"(심화편의 '절 이름' 절을 다시 읽어 보라)\" 꼴)";
        if (a4 != null) {
            issues.add(new FormIssue("A4", a4));
        }
        if (!lines.get(0).startsWith("규칙:")) {
            issues.add(new FormIssue("B1", "첫 줄이 \"규칙:\"으로 시작하지 않음"));
        }

        int end = hasPointer ? lines.size() - 1 : lines.size();
        // 맺음은 마지막 "그래서" 줄이다. 걸음 중간에도 "그래서"를 쓴다(승인 문제 10개)
        int so = -1;
        for (int i = end - 1; i >= 1; i--) {
            if (lines.get(i).startsWith("그래서")) {
                so = i;
                break;
            }
        }
        int steps = (so < 0 ? end : so) - 1;
        if (steps < MIN_STEPS) {
            issues.add(new FormIssue("B3", "규칙과 맺음 사이 걸음이 모자람 (%d줄, 기준 %d줄 이상)".formatted(steps, MIN_STEPS)));
        }
        if (so < 0) {
            issues.add(new FormIssue("B5", "\"그래서\"로 시작하는 맺음 줄이 없음"));
        } else if (so != end - 1) {
            issues.add(new FormIssue("B5", "\"그래서\" 줄 뒤에 안내 말고 다른 줄이 있음"));
        }

        int length = explanation.length();
        if (length < ProblemItemRule.EXPLANATION_MIN || length > ProblemItemRule.EXPLANATION_MAX) {
            issues.add(new FormIssue("C4", "해설 길이가 기준 밖 (%d자, 기준 %d~%d자)"
                    .formatted(length, ProblemItemRule.EXPLANATION_MIN, ProblemItemRule.EXPLANATION_MAX)));
        }
    }

    /** @return 안내가 맞으면 {@code null}, 아니면 걸린 이유 */
    private static String pointerProblem(String edition, String section, String sourceSlug, String sourceMd) {
        if (sourceMd == null) {
            return null;
        }
        int cut = sourceMd.indexOf(DocumentEditionRule.BEGINNER_EXCERPT_HEADER);
        String main = cut < 0 ? sourceMd : sourceMd.substring(0, cut);
        String excerpt = cut < 0 ? "" : sourceMd.substring(cut + DocumentEditionRule.BEGINNER_EXCERPT_HEADER.length());

        String body;
        // 편이 둘로 나뉘기 전 문서는 "문서의"·"이 글의"로 가리킨다. 편을 견주지 않고 본문 전체에서 찾는다
        boolean anyEdition = edition.equals("문서") || edition.equals("이 글");
        if (sourceSlug == null || anyEdition || edition.equals(editionOf(sourceSlug))) {
            body = sourceSlug == null || anyEdition ? sourceMd : main;
        } else if (edition.equals("입문편") && !excerpt.isBlank()) {
            body = excerpt;
        } else {
            return "근거 문서는 %s인데 %s 절을 안내함".formatted(editionOf(sourceSlug), edition);
        }
        return hasHeading(body, section) ? null : "'%s' 절이 %s에 없음".formatted(section, edition);
    }

    private static String editionOf(String slug) {
        return slug.endsWith("-advanced") ? "심화편" : "입문편";
    }

    private static boolean hasHeading(String body, String section) {
        String want = cleanHeading(section);
        Matcher m = HEADING.matcher(body);
        while (m.find()) {
            String heading = cleanHeading(m.group(1));
            // "바탕이 되는 개념 — 브라우저는 …"처럼 부제가 붙은 절은 앞부분 이름으로 가리킨다
            if (heading.equals(want) || heading.startsWith(want + " —") || heading.startsWith(want + ":")) {
                return true;
            }
        }
        return false;
    }

    private static String cleanHeading(String s) {
        return s.replace("~~", "").strip();
    }

    private static String shortRationalePositions(GeneratedProblemItem item) {
        List<GeneratedChoice> choices = item.choices() == null ? List.of() : item.choices();
        List<String> positions = new ArrayList<>();
        for (int i = 0; i < choices.size(); i++) {
            GeneratedChoice c = choices.get(i);
            String r = c.rationale() == null ? "" : c.rationale().strip();
            if (!c.correct() && r.length() < ProblemItemRule.RATIONALE_MIN) {
                positions.add(String.valueOf(i + 1));
            }
        }
        return String.join(", ", positions);
    }
}
