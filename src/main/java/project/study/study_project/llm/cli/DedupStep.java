package project.study.study_project.llm.cli;

import project.study.study_project.llm.client.ClaudeDocumentGenerator;
import project.study.study_project.llm.client.DedupProposal;
import project.study.study_project.llm.client.DocumentDeduplicator;
import project.study.study_project.llm.client.DocumentEdition;
import project.study.study_project.llm.client.GeneratedDocumentItem;
import project.study.study_project.llm.dto.DedupRemoval;
import project.study.study_project.llm.support.DedupGuard;
import project.study.study_project.llm.support.DocumentDraftValidator;
import project.study.study_project.llm.support.TopicBrief;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 중복 제거본을 받아들일지 정한다(설계 2026-11-22-document-dedup-design.md 6절).
 * 호출 실패·과한 삭제·새 검증 경고 중 하나라도 있으면 그 편은 원본을 쓴다. job은 멈추지 않는다.
 */
final class DedupStep {

    private DedupStep() {
    }

    static BothEditions.Pair<DedupOutcome> dedupBoth(DocumentDeduplicator deduplicator, GeneratedDocumentItem beginner,
                                                     GeneratedDocumentItem advanced, TopicBrief brief) {
        String beginnerBox = brief == null ? null : brief.beginnerTopicBox();
        String advancedBox = brief == null ? null : brief.advancedTopicBox();
        return BothEditions.run(
                () -> decide(deduplicator, beginner, beginnerBox),
                advanced == null ? null : () -> decide(deduplicator, advanced, advancedBox));
    }

    static DedupOutcome decide(DocumentDeduplicator deduplicator, GeneratedDocumentItem original, String expectedTopicBox) {
        String body = original.contentMd();
        DocumentEdition edition = ClaudeDocumentGenerator.editionOf(body);
        List<DedupProposal> proposals;
        try {
            proposals = deduplicator.find(original.title(), body, edition);
        } catch (RuntimeException e) {
            return keep(original, "중복 검사 호출 실패: " + e.getMessage(), List.of());
        }

        DedupGuard.Decision decision = DedupGuard.check(body, edition, proposals);
        if (decision.accepted().isEmpty()) {
            return keep(original, "지울 문장 없음", decision.rejected());
        }
        if (decision.removedChars() > body.length() * DedupGuard.MAX_REMOVED_RATIO) {
            return keep(original, "지울 분량이 %,d자로 상한 %.0f%%를 넘음"
                    .formatted(decision.removedChars(), DedupGuard.MAX_REMOVED_RATIO * 100), decision.rejected());
        }

        String deduped = DedupGuard.apply(body, decision.accepted());
        List<String> added = PolishStep.newWarnings(
                DocumentDraftValidator.validate(original.title(), original.slug(), body, expectedTopicBox),
                DocumentDraftValidator.validate(original.title(), original.slug(), deduped, expectedTopicBox));
        if (!added.isEmpty()) {
            return keep(original, "새 형식 경고: " + added.get(0), decision.rejected());
        }

        List<DedupRemoval> removals = decision.accepted().stream()
                .map(a -> new DedupRemoval(a.removed(), a.kept(), a.section(), a.reason()))
                .toList();
        return new DedupOutcome(new GeneratedDocumentItem(original.title(), original.slug(), deduped, original.tags()),
                true, null, removals, decision.removedChars(), decision.rejected());
    }

    static String summaryLine(String edition, DedupOutcome outcome) {
        String rejected = rejectedSummary(outcome.rejected());
        return outcome.applied()
                ? "🧹 %s 중복 %d건 제거(%,d자)%s".formatted(edition, outcome.removals().size(), outcome.removedChars(), rejected)
                : "↩️ %s 중복 제거 안 함 — %s%s".formatted(edition, outcome.reason(), rejected);
    }

    /** 코드가 버린 제안의 문장. 검사가 너무 엄격한지는 사유 개수만으로 판단할 수 없어 로그에 남긴다. */
    static List<String> rejectedDetails(String edition, DedupOutcome outcome) {
        return outcome.rejected().stream()
                .map(r -> "   버린 제안(%s · %s): %s".formatted(edition, r.why(),
                        r.target() == null ? "" : r.target().replaceAll("\\s+", " ").strip()))
                .toList();
    }

    private static String rejectedSummary(List<DedupGuard.Rejected> rejected) {
        if (rejected.isEmpty()) {
            return "";
        }
        Map<String, Long> byReason = rejected.stream().collect(Collectors.groupingBy(
                r -> r.why().startsWith("남길 문장에 없는 사실") ? "남길 문장에 없는 사실" : r.why(),
                LinkedHashMap::new, Collectors.counting()));
        return " · 버린 제안 %d건(%s)".formatted(rejected.size(), byReason.entrySet().stream()
                .map(e -> e.getKey() + " " + e.getValue())
                .collect(Collectors.joining(", ")));
    }

    private static DedupOutcome keep(GeneratedDocumentItem original, String reason, List<DedupGuard.Rejected> rejected) {
        return new DedupOutcome(original, false, reason, List.of(), 0, rejected);
    }
}
