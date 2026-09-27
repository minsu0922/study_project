package project.study.study_project.llm.cli;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import project.study.study_project.llm.client.ClaudeDocumentFactChecker;
import project.study.study_project.llm.client.DocumentFactChecker;
import project.study.study_project.llm.client.FactCheckFinding;
import project.study.study_project.llm.client.GeneratedDocumentItem;
import project.study.study_project.llm.dto.GeneratedDocumentFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 문서 사실 검수 적발률 측정 — docs/22 §4.
 *
 * <p>승인된 문서에 오류를 심고 검수기를 돌려, 심은 오류를 몇 개 찾는지(적발률)와
 * 멀쩡한 곳을 몇 번 지적하는지(헛경보)를 센다. 실제 API를 부르므로 {@code evalReview}
 * 태스크로만 돈다.
 *
 * <pre>
 *   --samples=eval-samples/fact-check.json   표본 정의
 *   --model=claude-opus-5                    검수 모델. 생략하면 생성 모델
 *   --only=redis-persistence                 표본 하나만
 *   --label=before                           보고서 파일 이름 꼬리표
 *   --out=eval                               보고서 디렉터리
 * </pre>
 */
public final class ReviewEvalCli {

    private static final String DEFAULT_SAMPLES = "eval-samples/fact-check.json";
    private static final String DEFAULT_OUT_DIR = "eval";

    /** docs/22 §4의 초기 문턱. 실측 뒤 조정한다. */
    static final double MIN_DETECTION_RATE = 0.8;
    static final double MAX_FALSE_ALARMS_PER_DOC = 2.0;

    /**
     * 인용이 목표 문장과 이만큼 이어서 겹치면 같은 곳을 가리킨 것으로 본다.
     * 모델이 심은 문장의 앞뒤를 조금 더 붙여 인용하는 경우를 받기 위해서다.
     */
    static final int MIN_OVERLAP = 20;

    /** 이보다 짧은 인용은 부분 일치로 치지 않는다. "1024번" 한 단어가 아무 데나 맞는 것을 막는다. */
    static final int MIN_QUOTE_LENGTH = 8;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ReviewEvalCli() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = DraftGeneratorCli.parseArgs(args);
        String model = opts.containsKey("model")
                ? opts.get("model")
                : (String) DraftGeneratorCli.readGenerationConfig().getOrDefault("model", "claude-opus-5");
        SampleFile sampleFile = MAPPER.readValue(
                Path.of(opts.getOrDefault("samples", DEFAULT_SAMPLES)).toFile(), SampleFile.class);

        List<Sample> samples = sampleFile.samples().stream()
                .filter(s -> !opts.containsKey("only") || s.id().equals(opts.get("only")))
                .toList();
        if (samples.isEmpty()) {
            throw new IllegalArgumentException("돌릴 표본이 없습니다: --only=" + opts.get("only"));
        }

        // 요금을 쓰기 전에 표본을 전부 준비해 본다. 문서가 바뀌어 find가 안 맞으면 여기서 멈춘다.
        List<Prepared> prepared = new ArrayList<>();
        for (Sample s : samples) {
            GeneratedDocumentItem doc = readDocument(Path.of(s.document()), s.edition());
            prepared.add(new Prepared(s, doc.title(), plant(doc.contentMd(), s.plantedOrEmpty())));
        }

        System.out.printf("사실 검수 적발률 측정: 모델 %s, 표본 %d편%n", model, prepared.size());
        DocumentFactChecker checker = new ClaudeDocumentFactChecker(model);
        List<SampleScore> scores = new ArrayList<>();
        for (Prepared p : prepared) {
            System.out.printf("  %s 검수 중...%n", p.sample().id());
            List<FactCheckFinding> findings = checker.check(p.title(), p.contentMd());
            scores.add(score(p.sample(), findings));
        }

        String report = render(scores, model);
        System.out.println();
        System.out.println(report);
        Path reportFile = writeReport(report, opts);
        System.out.println("보고서 저장: " + reportFile);
        System.out.println("검수 원본 저장: " + writeRaw(scores, reportFile));
    }

    /* ── 오류 심기 ───────────────────────────────────────────── */

    /** find가 문서에 정확히 한 번 있어야 한다. 두 번 이상이면 어느 쪽을 바꿨는지 채점이 흐려진다. */
    static String plant(String contentMd, List<Planted> planted) {
        String result = contentMd;
        for (Planted p : planted) {
            int count = countOccurrences(result, p.find());
            if (count != 1) {
                throw new IllegalStateException("심을 문장이 문서에 %d번 있습니다(1번이어야 함): %s — %s"
                        .formatted(count, p.id(), p.find()));
            }
            result = result.replace(p.find(), p.replace());
        }
        return result;
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) {
            count++;
        }
        return count;
    }

    /* ── 채점 ────────────────────────────────────────────────── */

    /** 표본 하나를 채점한다. 지적 하나는 목표 하나에만 짝지어진다. */
    static SampleScore score(Sample sample, List<FactCheckFinding> findings) {
        List<Target> targets = targetsOf(sample);
        Map<String, FactCheckFinding> caught = new LinkedHashMap<>();
        List<FactCheckFinding> falseAlarms = new ArrayList<>();

        for (FactCheckFinding f : findings) {
            Target hit = targets.stream()
                    .filter(t -> !caught.containsKey(t.id()))
                    .filter(t -> t.keys().stream().anyMatch(k -> matches(f.quote(), k)))
                    .findFirst()
                    .orElse(null);
            if (hit != null) {
                caught.put(hit.id(), f);
            } else if (targets.stream().noneMatch(t -> t.keys().stream().anyMatch(k -> matches(f.quote(), k)))) {
                falseAlarms.add(f);
            }
            // 이미 잡은 목표를 한 번 더 가리킨 지적은 헛경보도 적발도 아니다 — 같은 오류를 두 문장으로 짚은 것이다
        }

        List<Target> missed = targets.stream().filter(t -> !caught.containsKey(t.id())).toList();
        return new SampleScore(sample.id(), targets, caught, missed, falseAlarms, findings);
    }

    static List<Target> targetsOf(Sample sample) {
        List<Target> targets = new ArrayList<>();
        for (Planted p : sample.plantedOrEmpty()) {
            List<String> keys = new ArrayList<>();
            keys.add(p.replace());
            if (p.alsoAccept() != null) {
                keys.addAll(p.alsoAccept());
            }
            targets.add(new Target(p.id(), p.kind(), true, keys));
        }
        for (Known k : sample.knownOrEmpty()) {
            targets.add(new Target(k.id(), k.kind(), false, k.quotes()));
        }
        return targets;
    }

    /** 인용과 목표 문장이 같은 곳을 가리키는가 — 한쪽이 다른 쪽을 품거나, 충분히 길게 겹친다. */
    static boolean matches(String quote, String key) {
        String q = ClaudeDocumentFactChecker.normalize(quote);
        String k = ClaudeDocumentFactChecker.normalize(key);
        if (q.isEmpty() || k.isEmpty()) {
            return false;
        }
        if (q.contains(k)) {
            return true;
        }
        if (q.length() >= MIN_QUOTE_LENGTH && k.contains(q)) {
            return true;
        }
        return longestCommonSubstring(q, k) >= Math.min(MIN_OVERLAP, k.length());
    }

    static int longestCommonSubstring(String a, String b) {
        int best = 0;
        int[] prev = new int[b.length() + 1];
        for (int i = 1; i <= a.length(); i++) {
            int[] cur = new int[b.length() + 1];
            for (int j = 1; j <= b.length(); j++) {
                if (a.charAt(i - 1) == b.charAt(j - 1)) {
                    cur[j] = prev[j - 1] + 1;
                    best = Math.max(best, cur[j]);
                }
            }
            prev = cur;
        }
        return best;
    }

    /* ── 보고서 ──────────────────────────────────────────────── */

    static String render(List<SampleScore> scores, String model) {
        int targets = scores.stream().mapToInt(s -> s.targets().size()).sum();
        int caught = scores.stream().mapToInt(s -> s.caught().size()).sum();
        int planted = scores.stream().mapToInt(s -> (int) s.targets().stream().filter(Target::planted).count()).sum();
        int plantedCaught = scores.stream()
                .mapToInt(s -> (int) s.targets().stream().filter(t -> t.planted() && s.caught().containsKey(t.id())).count())
                .sum();
        int falseAlarms = scores.stream().mapToInt(s -> s.falseAlarms().size()).sum();
        double rate = targets == 0 ? 0 : (double) caught / targets;
        double perDoc = scores.isEmpty() ? 0 : (double) falseAlarms / scores.size();
        boolean pass = rate >= MIN_DETECTION_RATE && perDoc <= MAX_FALSE_ALARMS_PER_DOC;

        StringBuilder sb = new StringBuilder();
        sb.append("# 사실 검수 적발률 — ").append(model).append("\n\n");
        sb.append("| 지표 | 값 | 문턱 |\n|---|---|---|\n");
        sb.append("| 적발률 (전체) | %d/%d = %.0f%% | %.0f%% 이상 |\n".formatted(caught, targets, rate * 100, MIN_DETECTION_RATE * 100));
        sb.append("| 적발률 (심은 오류만) | %d/%d |  |\n".formatted(plantedCaught, planted));
        sb.append("| 헛경보 | %d건, 문서당 %.1f | %.0f 이하 |\n".formatted(falseAlarms, perDoc, MAX_FALSE_ALARMS_PER_DOC));
        sb.append("| 판정 | ").append(pass ? "통과" : "미달").append(" |  |\n\n");
        sb.append("헛경보에는 원문에 원래 있던 오류가 섞여 있을 수 있다. 목록을 읽고 진짜 오류면 표본의 known에 올린다.\n");

        for (SampleScore s : scores) {
            sb.append("\n## ").append(s.sampleId()).append("\n\n");
            sb.append("| 목표 | 종류 | 출처 | 결과 | 확신도 |\n|---|---|---|---|---|\n");
            for (Target t : s.targets()) {
                FactCheckFinding f = s.caught().get(t.id());
                sb.append("| %s | %s | %s | %s | %s |\n".formatted(
                        t.id(), t.kind(), t.planted() ? "심음" : "원래",
                        f == null ? "놓침" : "적발", f == null ? "" : f.confidence()));
            }
            if (!s.falseAlarms().isEmpty()) {
                sb.append("\n헛경보 후보:\n\n");
                for (FactCheckFinding f : s.falseAlarms()) {
                    sb.append("- [%s/%s] \"%s\" — %s\n".formatted(f.kind(), f.confidence(), f.quote(), f.reason()));
                }
            }
        }
        return sb.toString();
    }

    /* ── 입출력 ──────────────────────────────────────────────── */

    private static GeneratedDocumentItem readDocument(Path file, String edition) throws Exception {
        GeneratedDocumentFile parsed = MAPPER.readValue(file.toFile(), GeneratedDocumentFile.class);
        GeneratedDocumentItem doc = "ADVANCED".equals(edition) ? parsed.advancedDocument() : parsed.document();
        if (doc == null || doc.contentMd() == null || doc.contentMd().isBlank()) {
            throw new IllegalStateException("문서 본문이 없습니다: " + file + " (" + edition + ")");
        }
        return doc;
    }

    private static Path writeReport(String report, Map<String, String> opts) throws Exception {
        Path dir = Path.of(opts.getOrDefault("out", DEFAULT_OUT_DIR));
        Files.createDirectories(dir);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String label = opts.containsKey("label") ? "-" + opts.get("label") : "";
        Path file = dir.resolve("review-" + stamp + label + ".md");
        Files.writeString(file, report);
        return file;
    }

    /** 요금을 내고 받은 지적 전부를 남긴다. 채점 규칙을 고쳤을 때 다시 부르지 않고 재채점할 수 있다. */
    private static Path writeRaw(List<SampleScore> scores, Path reportFile) throws Exception {
        Map<String, List<FactCheckFinding>> bySample = new LinkedHashMap<>();
        for (SampleScore s : scores) {
            bySample.put(s.sampleId(), s.findings());
        }
        Path file = reportFile.resolveSibling(reportFile.getFileName().toString().replaceFirst("\\.md$", ".json"));
        Files.writeString(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(bySample));
        return file;
    }

    /* ── 표본 형태 ───────────────────────────────────────────── */

    record SampleFile(String note, List<Sample> samples) {
    }

    record Sample(String id, String document, String edition, List<Planted> planted, List<Known> known) {
        List<Planted> plantedOrEmpty() {
            return planted == null ? List.of() : planted;
        }

        List<Known> knownOrEmpty() {
            return known == null ? List.of() : known;
        }
    }

    record Planted(String id, FactCheckFinding.Kind kind, String find, String replace, List<String> alsoAccept) {
    }

    record Known(String id, FactCheckFinding.Kind kind, String note, List<String> quotes) {
    }

    record Target(String id, FactCheckFinding.Kind kind, boolean planted, List<String> keys) {
    }

    record Prepared(Sample sample, String title, String contentMd) {
    }

    record SampleScore(String sampleId, List<Target> targets, Map<String, FactCheckFinding> caught,
                       List<Target> missed, List<FactCheckFinding> falseAlarms,
                       List<FactCheckFinding> findings) {
    }
}
