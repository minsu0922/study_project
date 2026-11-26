package project.study.study_project.llm.cli;

import project.study.study_project.llm.client.ClaudeDocumentGenerator;
import project.study.study_project.llm.client.DocumentPolisher;
import project.study.study_project.llm.client.GeneratedDocumentItem;
import project.study.study_project.llm.support.DocumentDraftValidator;
import project.study.study_project.llm.support.DraftCheck;
import project.study.study_project.llm.support.PolishMerge;
import project.study.study_project.llm.support.PolishPreservation;
import project.study.study_project.llm.support.TopicBrief;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 윤문본을 받아들일지 정한다(설계 2026-11-18 "윤문본 채택 기준"·"실패 처리").
 * 보존 대조에 걸리거나 새 형식 경고를 낸 본문 덩어리는 원고로 두고 나머지는 받는다. job은 멈추지 않는다.
 */
final class PolishStep {

    private PolishStep() {
    }

    static BothEditions.Pair<PolishOutcome> polishBoth(DocumentPolisher polisher, GeneratedDocumentItem beginner,
                                                       GeneratedDocumentItem advanced, TopicBrief brief) {
        String beginnerBox = brief == null ? null : brief.beginnerTopicBox();
        String advancedBox = brief == null ? null : brief.advancedTopicBox();
        return BothEditions.run(
                () -> decide(polisher, beginner, beginnerBox),
                advanced == null ? null : () -> decide(polisher, advanced, advancedBox));
    }

    /**
     * 본문 덩어리마다 윤문본을 받거나 원고로 둔다(2026-11-26). 전에는 한 곳만 걸려도 편 전체를 버렸다.
     * 편 전체를 원본으로 두는 것은 호출 실패, 첫 줄이나 경계 줄(소제목·코드 블록·인용 상자)이 달라진 때뿐이다.
     *
     * @param expectedTopicBox 사람이 쓴 그 편 「글의 주제」. 있으면 상자가 바뀌었는지도 형식 검사가 본다
     */
    static PolishOutcome decide(DocumentPolisher polisher, GeneratedDocumentItem original, String expectedTopicBox) {
        String before = original.contentMd();
        String polished;
        try {
            polished = polisher.polish(original.title(), before, ClaudeDocumentGenerator.editionOf(before));
        } catch (RuntimeException e) {
            return keep(original, "윤문 호출 실패: " + e.getMessage());
        }

        PolishMerge.Parts o = PolishMerge.split(before);
        PolishMerge.Parts p = PolishMerge.split(polished);
        if (!PolishPreservation.sameFirstLine(before, polished) || !PolishMerge.sameAnchors(o, p)) {
            List<String> differences = PolishPreservation.differences(before, polished);
            return keep(original, "보존 대조 실패: " + (differences.isEmpty()
                    ? "소제목·코드 블록·인용 상자 줄의 자리가 원고와 다릅니다" : differences.get(0)));
        }

        int n = o.prose().size();
        boolean[] use = new boolean[n];
        List<String> kept = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (o.prose().get(i).equals(p.prose().get(i))) {
                continue;
            }
            List<String> differences = PolishMerge.proseDifferences(o, p, i);
            if (differences.isEmpty()) {
                use[i] = true;
            } else {
                kept.add("보존 대조 실패: " + differences.get(0) + where(o, i));
            }
        }

        List<DraftCheck> baseline = DocumentDraftValidator.validate(original.title(), original.slug(), before, expectedTopicBox);
        String merged = PolishMerge.assemble(o, p, use);
        if (!warningsAdded(original, baseline, merged, expectedTopicBox).isEmpty()) {
            // 어느 덩어리가 경고를 냈는지 하나씩 넣어 보고 그 덩어리만 원고로 둔다
            for (int i = 0; i < n; i++) {
                if (!use[i]) {
                    continue;
                }
                boolean[] only = new boolean[n];
                only[i] = true;
                List<String> added = warningsAdded(original, baseline, PolishMerge.assemble(o, p, only), expectedTopicBox);
                if (!added.isEmpty()) {
                    use[i] = false;
                    kept.add("새 형식 경고: " + added.get(0) + where(o, i));
                }
            }
            merged = PolishMerge.assemble(o, p, use);
            // 덩어리 하나씩은 괜찮은데 합치면 생기는 경고(개수 상한 등)는 가려낼 수 없어 편 전체를 원본으로 둔다
            List<String> still = warningsAdded(original, baseline, merged, expectedTopicBox);
            if (!still.isEmpty()) {
                return keep(original, "새 형식 경고: " + still.get(0));
            }
        }

        boolean anyUsed = false;
        for (boolean u : use) {
            anyUsed |= u;
        }
        if (!anyUsed && !kept.isEmpty()) {
            return keep(original, kept.get(0));
        }
        return new PolishOutcome(new GeneratedDocumentItem(original.title(), original.slug(), merged, original.tags()),
                true, null, List.copyOf(kept));
    }

    private static List<String> warningsAdded(GeneratedDocumentItem original, List<DraftCheck> baseline,
                                              String content, String expectedTopicBox) {
        return newWarnings(baseline,
                DocumentDraftValidator.validate(original.title(), original.slug(), content, expectedTopicBox));
    }

    /** 원고로 둔 덩어리의 자리. 검수할 때 그 절을 찾아가 읽을 수 있게 소제목을 적는다 */
    private static String where(PolishMerge.Parts parts, int i) {
        String heading = PolishMerge.headingAbove(parts, i);
        return heading == null ? " (글 첫머리)" : " (「%s」 아래)".formatted(heading);
    }

    /**
     * 윤문본에만 있는 경고 문장. 원본에도 경고가 흔해 "통과"는 기준이 될 수 없어 새로 생긴 것만 본다.
     * 숫자는 지우고 비교한다. 개수만 다른 같은 경고를 새 경고로 치면 거의 모든 윤문이 원본으로 돌아간다.
     */
    static List<String> newWarnings(List<DraftCheck> before, List<DraftCheck> after) {
        Set<String> seen = before.stream().map(c -> withoutNumbers(c.message())).collect(Collectors.toSet());
        return after.stream()
                .map(DraftCheck::message)
                .filter(m -> !seen.contains(withoutNumbers(m)))
                .toList();
    }

    private static String withoutNumbers(String message) {
        return message == null ? "" : message.replaceAll("\\d+", "#");
    }

    static String summaryLine(String edition, PolishOutcome outcome) {
        if (!outcome.applied()) {
            return "↩️ %s 원본 유지 — %s".formatted(edition, outcome.reason());
        }
        if (outcome.kept().isEmpty()) {
            return "✨ %s 윤문 적용".formatted(edition);
        }
        return "✨ %s 윤문 적용 — 원고로 둔 곳 %d곳: %s".formatted(edition, outcome.kept().size(), keptSummary(outcome));
    }

    /** 원고로 둔 곳을 한 줄로. 요약 화면이 길어지지 않게 세 곳까지만 적는다 */
    static String keptSummary(PolishOutcome outcome) {
        List<String> kept = outcome.kept();
        String shown = String.join("; ", kept.subList(0, Math.min(3, kept.size())));
        return kept.size() > 3 ? shown + " 외 %d곳".formatted(kept.size() - 3) : shown;
    }

    private static PolishOutcome keep(GeneratedDocumentItem original, String reason) {
        return new PolishOutcome(original, false, reason);
    }
}
