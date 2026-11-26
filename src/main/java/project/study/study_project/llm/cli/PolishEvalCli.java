package project.study.study_project.llm.cli;

import project.study.study_project.llm.client.ClaudeDocumentFactChecker;
import project.study.study_project.llm.client.ClaudeDocumentGenerator;
import project.study.study_project.llm.client.ClaudeDocumentPolisher;
import project.study.study_project.llm.client.DocumentFactChecker;
import project.study.study_project.llm.client.DocumentPolisher;
import project.study.study_project.llm.client.GeneratedDocumentItem;
import project.study.study_project.llm.dto.GeneratedDocumentFile;
import project.study.study_project.llm.support.DocumentDraftValidator;
import project.study.study_project.llm.support.JsonMappers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 윤문을 켜기 전에 윤문 전후를 비교한다(설계 2026-11-18 "설정과 평가"). 실제 API를 부르므로
 * {@code evalPolish} 태스크로만 돈다. 표본 4편 × 2편이면 윤문 8회, 사실 검수 16회다.
 *
 * <pre>
 *   --samples=eval-samples/documents   표본 폴더
 *   --skip-facts=true                  사실 검수를 건너뛴다(윤문 8회만)
 *   --model=claude-opus-5-5            생략하면 생성 모델
 *   --label=before                     보고서 파일 이름 꼬리표
 *   --out=eval                         보고서 폴더
 * </pre>
 */
public final class PolishEvalCli {

    private static final String DEFAULT_SAMPLES = "eval-samples/documents";
    private static final String DEFAULT_OUT_DIR = "eval";

    /** 사실 칸이 {@code null}이면 사실 검수를 건너뛴 행이다. */
    record Row(String file, String edition, boolean applied, String reason, int warningsBefore, int warningsAfter,
               Integer factsBefore, Integer factsAfter) {
    }

    private PolishEvalCli() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = BatchOptions.parseArgs(args);
        String model = BatchOptions.resolveModel(opts);
        boolean skipFacts = "true".equals(opts.get("skip-facts"));
        // 바로 호출한다. 사람이 결과를 기다리고, 배치 반값보다 오늘 결과가 중요하다
        DocumentPolisher polisher = new ClaudeDocumentPolisher(model);
        DocumentFactChecker checker = skipFacts ? null : new ClaudeDocumentFactChecker(model);

        List<Path> files;
        try (Stream<Path> s = Files.list(Path.of(opts.getOrDefault("samples", DEFAULT_SAMPLES)))) {
            files = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        System.out.printf("윤문 평가: 모델 %s, 표본 %d개 파일, 사실 검수 %s%n",
                model, files.size(), skipFacts ? "건너뜀" : "함");

        List<Row> rows = new ArrayList<>();
        for (Path f : files) {
            // 옛 표본에는 지금 형식에 없는 칸이 있을 수 있어 느슨하게 읽는다
            GeneratedDocumentFile parsed = JsonMappers.LENIENT.readValue(f.toFile(), GeneratedDocumentFile.class);
            for (GeneratedDocumentItem doc : Stream.of(parsed.document(), parsed.advancedDocument())
                    .filter(Objects::nonNull).toList()) {
                rows.add(evaluate(f.getFileName().toString(), doc, polisher, checker));
            }
        }

        String report = render(rows);
        System.out.println(report);
        System.out.println("보고서 저장: " + writeReport(report, opts));
    }

    static Row evaluate(String file, GeneratedDocumentItem doc, DocumentPolisher polisher, DocumentFactChecker checker) {
        var edition = ClaudeDocumentGenerator.editionOf(doc.contentMd());
        PolishOutcome outcome = PolishStep.decide(polisher, doc, null);
        String after = outcome.chosen().contentMd();
        int warningsBefore = DocumentDraftValidator.validate(doc.title(), doc.slug(), doc.contentMd()).size();
        int warningsAfter = DocumentDraftValidator.validate(doc.title(), doc.slug(), after).size();
        Integer factsBefore = null;
        Integer factsAfter = null;
        if (checker != null) {
            factsBefore = checker.check(doc.title(), doc.contentMd(), edition).size();
            // 원본을 유지했으면 같은 본문을 두 번 검수할 이유가 없다
            factsAfter = outcome.applied() ? checker.check(doc.title(), after, edition).size() : factsBefore;
        }
        // 채택했는데 원고로 둔 곳이 있으면 그 사유를 같은 칸에 싣는다
        String reason = outcome.applied()
                ? (outcome.kept().isEmpty() ? null : PolishStep.keptSummary(outcome))
                : outcome.reason();
        return new Row(file, edition.getDisplayName(), outcome.applied(), reason,
                warningsBefore, warningsAfter, factsBefore, factsAfter);
    }

    static String render(List<Row> rows) {
        StringBuilder sb = new StringBuilder("# 윤문 평가\n\n")
                .append("| 파일 | 편 | 결과 | 형식 경고 전 | 형식 경고 후 | 사실 지적 전 | 사실 지적 후 |\n")
                .append("|---|---|---|---|---|---|---|\n");
        for (Row r : rows) {
            sb.append("| %s | %s | %s | %d | %d | %s | %s |\n".formatted(r.file(), r.edition(),
                    !r.applied() ? "원본 유지 — " + r.reason()
                            : r.reason() == null ? "채택" : "채택 — 원고로 둔 곳: " + r.reason(),
                    r.warningsBefore(), r.warningsAfter(),
                    dash(r.factsBefore()), dash(r.factsAfter())));
        }
        long applied = rows.stream().filter(Row::applied).count();
        int wb = rows.stream().mapToInt(Row::warningsBefore).sum();
        int wa = rows.stream().mapToInt(Row::warningsAfter).sum();
        List<Row> withFacts = rows.stream().filter(r -> r.factsBefore() != null).toList();
        sb.append("\n- 채택 %d/%d%n".formatted(applied, rows.size()));
        sb.append("- 형식 경고 %d → %d%n".formatted(wb, wa));
        sb.append(withFacts.isEmpty() ? "- 사실 지적 건너뜀\n"
                : "- 사실 지적 %d → %d%n".formatted(
                        withFacts.stream().mapToInt(Row::factsBefore).sum(),
                        withFacts.stream().mapToInt(Row::factsAfter).sum()));
        return sb.toString();
    }

    private static String dash(Integer n) {
        return n == null ? "-" : n.toString();
    }

    private static Path writeReport(String report, Map<String, String> opts) throws Exception {
        Path dir = Path.of(opts.getOrDefault("out", DEFAULT_OUT_DIR));
        Files.createDirectories(dir);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String label = opts.containsKey("label") ? "-" + opts.get("label") : "";
        Path file = dir.resolve("polish-" + stamp + label + ".md");
        Files.writeString(file, report);
        return file;
    }
}
