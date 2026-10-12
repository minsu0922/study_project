package project.study.study_project.llm.support;

import project.study.study_project.llm.client.ClaudeDocumentFactChecker;
import project.study.study_project.llm.client.FactCheckFinding;
import project.study.study_project.llm.client.GeneratedProblemItem;
import project.study.study_project.llm.client.ProblemReview.Finding;
import project.study.study_project.llm.client.ProblemReview.FindingType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 문서 사실 검수가 지적한 문단을 근거로 삼은 문제를 찾는다 — docs/22 §6.
 *
 * <p>AI를 부르지 않는다. 문서 지적의 인용과 문제의 근거 인용은 둘 다 문서에서 글자 그대로
 * 옮긴 문장이라, 같은 문단에 있는지만 보면 된다.
 */
public final class FlaggedSourceRule {

    /** 공백이 이 비율을 넘는 "바로잡으면"은 깨진 응답으로 본다(2026-10-09 입문편: 탭과 낱글자만 왔다). */
    static final double GARBLED_WHITESPACE = 0.4;

    private FlaggedSourceRule() {
    }

    /**
     * @param problems  검수에 넘긴 문제 목록. 지적의 번호는 이 목록 안의 위치다
     * @param contentMd 문제를 만들 때 모델에게 준 본문
     * @param facts     문서 파일에 실린 사실 검수 지적. 없으면 {@code null}
     */
    public static List<Finding> link(List<GeneratedProblemItem> problems, String contentMd,
                                     List<FactCheckFinding> facts) {
        if (facts == null || facts.isEmpty() || contentMd == null) {
            return List.of();
        }
        List<String> paragraphs = Arrays.stream(contentMd.split("\\n\\s*\\n"))
                .map(ClaudeDocumentFactChecker::normalize)
                .filter(p -> !p.isEmpty())
                .toList();

        List<Finding> findings = new ArrayList<>();
        for (int i = 0; i < problems.size(); i++) {
            String quote = ClaudeDocumentFactChecker.normalize(problems.get(i).sourceQuote());
            if (quote.isEmpty()) {
                continue;
            }
            for (String paragraph : paragraphs) {
                if (!paragraph.contains(quote)) {
                    continue;
                }
                for (FactCheckFinding fact : facts) {
                    // 지적 문장이 본문에 아직 있을 때만 잇는다. 지적을 보고 문서를 고쳤다면 헛경보가 된다
                    String flagged = ClaudeDocumentFactChecker.normalize(fact.quote());
                    if (makesProblemWrong(fact) && !flagged.isEmpty() && paragraph.contains(flagged)) {
                        findings.add(new Finding(i, FindingType.SOURCE_FLAGGED,
                                "근거로 삼은 문단을 문서 사실 검수가 지적했습니다", detailOf(fact)));
                    }
                }
            }
        }
        return findings;
    }

    /** 정의 없는 용어는 읽기 어렵게 만들 뿐 문제의 정답을 틀리게 하지 않는다. */
    private static boolean makesProblemWrong(FactCheckFinding fact) {
        return fact.kind() == FactCheckFinding.Kind.FACT_ERROR
                || fact.kind() == FactCheckFinding.Kind.INTERNAL_MISMATCH;
    }

    private static String detailOf(FactCheckFinding fact) {
        String detail = "지적된 문장: 「%s」\n이유: %s".formatted(fact.quote().strip(),
                fact.reason() == null ? "" : fact.reason().strip());
        return isGarbled(fact.correction()) ? detail : detail + "\n바로잡으면: " + fact.correction().strip();
    }

    static boolean isGarbled(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        long whitespace = text.chars().filter(Character::isWhitespace).count();
        return whitespace > text.length() * GARBLED_WHITESPACE;
    }
}
