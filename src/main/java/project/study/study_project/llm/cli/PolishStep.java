package project.study.study_project.llm.cli;

import project.study.study_project.llm.client.ClaudeDocumentGenerator;
import project.study.study_project.llm.client.DocumentPolisher;
import project.study.study_project.llm.client.GeneratedDocumentItem;
import project.study.study_project.llm.support.DocumentDraftValidator;
import project.study.study_project.llm.support.DraftCheck;
import project.study.study_project.llm.support.PolishPreservation;
import project.study.study_project.llm.support.TopicBrief;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 윤문본을 받아들일지 정한다(설계 2026-11-18 "윤문본 채택 기준"·"실패 처리").
 * 호출 실패, 보존 대조 실패, 새 형식 경고 중 하나라도 있으면 그 편은 원본을 쓴다. job은 멈추지 않는다.
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

    /** @param expectedTopicBox 사람이 쓴 그 편 「글의 주제」. 있으면 상자가 바뀌었는지도 형식 검사가 본다 */
    static PolishOutcome decide(DocumentPolisher polisher, GeneratedDocumentItem original, String expectedTopicBox) {
        String polished;
        try {
            polished = polisher.polish(original.title(), original.contentMd(),
                    ClaudeDocumentGenerator.editionOf(original.contentMd()));
        } catch (RuntimeException e) {
            return keep(original, "윤문 호출 실패: " + e.getMessage());
        }

        List<String> differences = PolishPreservation.differences(original.contentMd(), polished);
        if (!differences.isEmpty()) {
            return keep(original, "보존 대조 실패: " + differences.get(0));
        }

        List<String> added = newWarnings(
                DocumentDraftValidator.validate(original.title(), original.slug(), original.contentMd(), expectedTopicBox),
                DocumentDraftValidator.validate(original.title(), original.slug(), polished, expectedTopicBox));
        if (!added.isEmpty()) {
            return keep(original, "새 형식 경고: " + added.get(0));
        }

        return new PolishOutcome(
                new GeneratedDocumentItem(original.title(), original.slug(), polished, original.tags()), true, null);
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
        return outcome.applied()
                ? "✨ %s 윤문 적용".formatted(edition)
                : "↩️ %s 원본 유지 — %s".formatted(edition, outcome.reason());
    }

    private static PolishOutcome keep(GeneratedDocumentItem original, String reason) {
        return new PolishOutcome(original, false, reason);
    }
}
