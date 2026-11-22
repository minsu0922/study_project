package project.study.study_project.llm.cli;

import project.study.study_project.global.common.Difficulty;
import project.study.study_project.llm.client.ClaudeCalls;
import project.study.study_project.llm.client.ClaudeDocumentDeduplicator;
import project.study.study_project.llm.client.ClaudeDocumentGenerator;
import project.study.study_project.llm.client.DocumentDeduplicator;
import project.study.study_project.llm.client.GeneratedDocumentItem;
import project.study.study_project.llm.dto.DedupRemoval;
import project.study.study_project.llm.dto.GeneratedDocumentFile;
import project.study.study_project.llm.support.DedupGuard;
import project.study.study_project.llm.support.DifficultyMaterialRule;
import project.study.study_project.llm.support.DocumentDraftValidator;
import project.study.study_project.llm.support.JsonMappers;
import project.study.study_project.llm.support.TypeMaterialRule;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 중복 제거를 켜기 전에 원본과 제거본을 비교한다(설계 2026-11-22 9절). 실제 API를 부르므로
 * {@code evalDedup} 태스크로만 돈다. 기본은 최근 3개 파일, 편마다 1회라 최대 6회다.
 *
 * <pre>
 *   --samples=generated/documents   표본 폴더
 *   --limit=3                       폴더에서 가장 최근 파일 몇 개를 볼지
 *   --batch-api=true                Batch API로 반값에 돌린다(답이 늦다)
 *   --model=claude-opus-5-5         생략하면 생성 모델
 *   --label=first                   보고서 파일 이름 꼬리표
 *   --out=eval                      보고서 폴더
 * </pre>
 */
public final class DedupEvalCli {

    private static final String DEFAULT_SAMPLES = "generated/documents";
    private static final String DEFAULT_OUT_DIR = "eval";
    private static final int DEFAULT_LIMIT = 3;

    record Row(String file, String edition, boolean applied, String reason, int charsBefore, int charsAfter,
               int warningsBefore, int warningsAfter, int tableRowsBefore, int tableRowsAfter, boolean materialKept,
               List<DedupRemoval> removals, List<DedupGuard.Rejected> rejected) {
    }

    private DedupEvalCli() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = BatchOptions.parseArgs(args);
        String model = BatchOptions.resolveModel(opts);
        ClaudeCalls.useBatch("true".equalsIgnoreCase(opts.get(BatchOptions.BATCH_API_OPT)));
        DocumentDeduplicator deduplicator = new ClaudeDocumentDeduplicator(model);

        List<Path> all;
        try (Stream<Path> s = Files.list(Path.of(opts.getOrDefault("samples", DEFAULT_SAMPLES)))) {
            all = s.filter(p -> p.toString().endsWith(".json"))
                    .filter(p -> !p.getFileName().toString().startsWith("_"))
                    .toList();
        }
        List<Path> files = latest(all, Integer.parseInt(opts.getOrDefault("limit", String.valueOf(DEFAULT_LIMIT))));
        System.out.printf("중복 제거 평가: 모델 %s, 표본 %d개 파일, %s%n", model, files.size(),
                ClaudeCalls.batchMode() ? "Batch API" : "바로 호출");

        List<Row> rows = new ArrayList<>();
        for (Path f : files) {
            // 옛 표본에는 지금 형식에 없는 칸이 있을 수 있어 느슨하게 읽는다
            GeneratedDocumentFile parsed = JsonMappers.LENIENT.readValue(f.toFile(), GeneratedDocumentFile.class);
            String name = f.getFileName().toString();
            GeneratedDocumentItem advanced = parsed.advancedDocument();
            BothEditions.Pair<Row> pair = BothEditions.run(
                    () -> evaluate(name, parsed.document(), deduplicator),
                    advanced == null ? null : () -> evaluate(name, advanced, deduplicator));
            rows.add(pair.beginner());
            if (pair.advanced() != null) {
                rows.add(pair.advanced());
            }
        }

        String report = render(rows);
        System.out.println(report);
        System.out.println("보고서 저장: " + writeReport(report, opts));
        BatchReports.reportCostSummary(LocalDate.now(), ClaudeCalls.ledger());
    }

    /** 이름순 마지막 {@code limit}개. 파일 이름이 날짜라 곧 최근 파일이다. */
    static List<Path> latest(List<Path> files, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("--limit은 1 이상이어야 합니다: " + limit);
        }
        List<Path> sorted = files.stream().sorted().toList();
        return sorted.subList(Math.max(0, sorted.size() - limit), sorted.size());
    }

    static Row evaluate(String file, GeneratedDocumentItem doc, DocumentDeduplicator deduplicator) {
        String before = doc.contentMd();
        DedupOutcome outcome = DedupStep.decide(deduplicator, doc, null);
        String after = outcome.chosen().contentMd();
        return new Row(file, ClaudeDocumentGenerator.editionOf(before).getDisplayName(), outcome.applied(),
                outcome.reason(), before.length(), after.length(),
                DocumentDraftValidator.validate(doc.title(), doc.slug(), before).size(),
                DocumentDraftValidator.validate(doc.title(), doc.slug(), after).size(),
                TypeMaterialRule.comparableRowsOf(before), TypeMaterialRule.comparableRowsOf(after),
                sameMaterial(before, after), outcome.removals(), outcome.rejected());
    }

    /** 난이도마다 캘 절이 있는지가 원본과 같은지. 재료가 사라지면 그 난이도가 조용히 폴백으로 간다. */
    static boolean sameMaterial(String before, String after) {
        return Arrays.stream(Difficulty.values()).allMatch(d ->
                DifficultyMaterialRule.hasMaterialFor(before, d) == DifficultyMaterialRule.hasMaterialFor(after, d));
    }

    static String render(List<Row> rows) {
        StringBuilder sb = new StringBuilder("# 중복 제거 평가\n\n")
                .append("| 파일 | 편 | 결과 | 분량 | 줄어든 비율 | 형식 경고 | 짝짓기 표 행 | 난이도 재료 | 버린 제안 |\n")
                .append("|---|---|---|---|---|---|---|---|---|\n");
        for (Row r : rows) {
            sb.append("| %s | %s | %s | %,d → %,d | %.1f%% | %d → %d | %d → %d | %s | %d |\n".formatted(
                    r.file(), r.edition(),
                    r.applied() ? "채택 %d건".formatted(r.removals().size()) : "원본 유지 — " + cell(r.reason()),
                    r.charsBefore(), r.charsAfter(), reduced(r), r.warningsBefore(), r.warningsAfter(),
                    r.tableRowsBefore(), r.tableRowsAfter(), r.materialKept() ? "같음" : "**달라짐**", r.rejected().size()));
        }
        long applied = rows.stream().filter(Row::applied).count();
        int removed = rows.stream().mapToInt(r -> r.removals().size()).sum();
        int charsBefore = rows.stream().mapToInt(Row::charsBefore).sum();
        int charsAfter = rows.stream().mapToInt(Row::charsAfter).sum();
        sb.append("\n- 채택 %d/%d편, 지운 문장 %d건%n".formatted(applied, rows.size(), removed));
        sb.append("- 분량 %,d → %,d자%n".formatted(charsBefore, charsAfter));
        sb.append("- 재료가 달라진 편 %d개%n".formatted(rows.stream().filter(r -> !r.materialKept()).count()));

        sb.append("\n## 지운 문장\n\n사람이 읽고 잘못 지운 문장이 없는지 확인한다.\n");
        for (Row r : rows) {
            for (DedupRemoval x : r.removals()) {
                sb.append("\n### %s · %s · %s\n\n- 지움: %s\n- 남김: %s\n- 이유: %s\n".formatted(
                        r.file(), r.edition(), x.section(), oneLine(x.removed()), oneLine(x.kept()), oneLine(x.reason())));
            }
        }
        if (removed == 0) {
            sb.append("\n지운 문장이 없습니다.\n");
        }

        Map<String, Long> reasons = rows.stream().flatMap(r -> r.rejected().stream()).collect(Collectors.groupingBy(
                x -> x.why().startsWith("남길 문장에 없는 사실") ? "남길 문장에 없는 사실" : x.why(),
                LinkedHashMap::new, Collectors.counting()));
        sb.append("\n## 코드가 버린 제안\n\n");
        if (reasons.isEmpty()) {
            sb.append("버린 제안이 없습니다.\n");
        } else {
            reasons.forEach((why, n) -> sb.append("- %s: %d건\n".formatted(why, n)));
        }
        return sb.toString();
    }

    private static double reduced(Row r) {
        return r.charsBefore() == 0 ? 0 : (r.charsBefore() - r.charsAfter()) * 100.0 / r.charsBefore();
    }

    private static String cell(String text) {
        return oneLine(text).replace("|", "\\|");
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
    }

    private static Path writeReport(String report, Map<String, String> opts) throws Exception {
        Path dir = Path.of(opts.getOrDefault("out", DEFAULT_OUT_DIR));
        Files.createDirectories(dir);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String label = opts.containsKey("label") ? "-" + opts.get("label") : "";
        Path file = dir.resolve("dedup-" + stamp + label + ".md");
        Files.writeString(file, report);
        return file;
    }
}
