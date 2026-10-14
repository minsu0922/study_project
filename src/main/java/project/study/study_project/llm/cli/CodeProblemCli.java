package project.study.study_project.llm.cli;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.DomainCode;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.llm.client.ClaudeCalls;
import project.study.study_project.llm.client.ClaudeCodeProblemGenerator;
import project.study.study_project.llm.client.CodeProblem;
import project.study.study_project.llm.client.GeneratedProblemItem;
import project.study.study_project.llm.client.ProblemReview;
import project.study.study_project.llm.dto.GeneratedBatchFile;
import project.study.study_project.llm.support.CodeFactChecker;
import project.study.study_project.llm.support.CodeProblemRule;
import project.study.study_project.llm.support.CodeRunner;
import project.study.study_project.llm.support.CodeVerifier;
import project.study.study_project.llm.support.TopicQueue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * 자바 코드 문제 배치 — 주 1회, 코드 주제 하나로 문제를 내고 실행해서 확인한 것만 저장한다.
 *
 * <p>두 단계로 나눠 부른다. 워크플로가 스텝을 따로 둔다.
 * <pre>
 *   --phase=generate   주제를 고르고 Claude에게 문제를 받아 후보 파일에 적는다 (API 키 필요)
 *   --phase=verify     후보의 코드를 실제로 돌려, 통과한 문제만 generated/ 에 쓴다 (API 키 없음)
 * </pre>
 * <b>나눈 이유</b>: verify는 모델이 쓴 코드를 실행한다. 그 스텝의 환경에 API 키가 아예 없어야 한다.
 * 한 프로세스에서 다 하면 키를 쥔 채로 남의 코드를 돌리게 된다.
 *
 * <p>개념 문제 배치({@link DraftGeneratorCli})와 주기·주제·근거가 모두 달라 진입점을 따로 뒀다.
 * 파일 모양은 같아서 들여오기와 검수함은 그대로 쓴다.
 */
public final class CodeProblemCli {

    static final String CANDIDATES_FILE = "code-candidates.json";
    static final String DEFAULT_WORK_DIR = "build/code-problems";
    /** 같은 주제로 전에 낸 코드를 프롬프트에 싣는 상한. 주제가 여러 번 돌면 끝없이 늘어난다. */
    static final int MAX_AVOID_CODES = 15;

    static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private CodeProblemCli() {
    }

    /**
     * 생성 단계가 검증 단계에 넘기는 것. 커밋하지 않는 임시 파일이다.
     *
     * @param topicIndex 주제 파일 안의 위치. 통과한 문제가 있을 때 사용 표시를 찍는다. 주제를 손으로 줬으면 -1
     */
    record Candidates(String date, String topic, int topicIndex, DomainCode domain, String model,
                      List<CodeProblem.Item> items) {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = BatchOptions.parseArgs(args);
        Path outDir = BatchOptions.resolveOutDir(opts);
        Path workDir = Path.of(opts.getOrDefault("work", DEFAULT_WORK_DIR));
        LocalDate date = BatchOptions.resolveDate(opts);

        String phase = opts.getOrDefault("phase", "");
        switch (phase) {
            case "generate" -> generate(opts, outDir, workDir, date);
            case "verify" -> {
                // 사실 대조는 검증이 이미 돌린 코드(오답을 넣은 코드)를 다시 돌린다. 결과를 나눠 쓴다
                Map<String, CodeRunner.Result> runs = new HashMap<>();
                CodeRunner runner = new CodeRunner();
                Function<String, CodeRunner.Result> run = code -> runs.computeIfAbsent(code, runner::run);
                verify(outDir, workDir, new CodeVerifier(run), new CodeFactChecker(run));
            }
            default -> throw new IllegalArgumentException("--phase=generate 또는 --phase=verify 를 주세요: " + phase);
        }
    }

    /* ── 1. 생성 ── */

    private static void generate(Map<String, String> opts, Path outDir, Path workDir, LocalDate date) throws Exception {
        Files.createDirectories(workDir);
        Files.deleteIfExists(workDir.resolve(CANDIDATES_FILE));   // 지난 실행의 후보가 검증 단계로 새지 않게

        if (alreadyGenerated(outDir, date)) {
            BatchReports.announce("⏭️ **%s 코드 문제**는 이미 있어 건너뜁니다.".formatted(date));
            return;
        }
        TopicQueue queue = TopicQueue.read(outDir, TopicQueue.CODE_FILE_NAME);
        String givenTopic = givenTopic(opts, System::getenv);
        TopicQueue.Picked picked = givenTopic != null ? null : queue.next();
        if (picked == null && givenTopic == null) {
            BatchReports.announce(("⏭️ **%s 코드 문제**: 코드 문제 주제가 없어 건너뜁니다. "
                    + "관리 화면의 '코드 문제 주제' 탭에서 주제를 넣고 `%s`을 커밋하세요.")
                    .formatted(date, TopicQueue.CODE_FILE_NAME));
            return;
        }
        String topic = picked == null ? givenTopic : picked.topic();
        DomainCode domain = picked == null ? DomainCode.of(opts.getOrDefault("domain", "LANGUAGE_RUNTIME")) : picked.domain();
        String model = BatchOptions.isSet(opts.get("model")) ? opts.get("model")
                : (String) BatchOptions.readGenerationConfig().getOrDefault("model", "claude-opus-5");
        ClaudeCalls.useBatch(!"false".equalsIgnoreCase(opts.getOrDefault(BatchOptions.BATCH_API_OPT, "true")));

        List<String> avoid = previousCodes(outDir, topic);
        System.out.printf("코드 문제 생성 시작: 주제 \"%s\" (%s), 모델 %s, 전에 낸 코드 %d개%n", topic, domain, model, avoid.size());
        List<CodeProblem.Item> items = new ClaudeCodeProblemGenerator(model).generate(topic, plan(), avoid);
        if (items == null || items.isEmpty()) {
            throw new IllegalStateException("모델이 코드 문제를 하나도 반환하지 않았습니다.");
        }
        Files.writeString(workDir.resolve(CANDIDATES_FILE), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(
                new Candidates(date.toString(), topic, picked == null ? -1 : picked.index(), domain, model, items)));
        BatchReports.announce("📝 **%s 코드 문제 후보 %d개** — 주제 \"%s\". 다음 단계에서 실행해 확인합니다."
                .formatted(date, items.size(), topic));
        BatchReports.reportCostSummary(date, ClaudeCalls.ledger());
    }

    /**
     * 손으로 준 주제. 주제에는 공백이 들어가는데 인자는 공백으로 쪼개져 들어오므로,
     * 워크플로는 주제를 환경변수에 담고 그 이름을 {@code --topic-env}로 알려 준다.
     * 공백 없는 주제는 {@code --topic}으로 바로 줘도 된다.
     */
    static String givenTopic(Map<String, String> opts, Function<String, String> env) {
        String topic = opts.get("topic");
        if (!BatchOptions.isSet(topic) && BatchOptions.isSet(opts.get("topic-env"))) {
            topic = env.apply(opts.get("topic-env"));
        }
        return BatchOptions.isSet(topic) ? topic.trim() : null;
    }

    /** 한 주제에서 난이도를 섞어 낸다. 주 1회라 난이도마다 날을 나누면 한 바퀴에 3주가 걸린다. */
    static Map<Difficulty, Integer> plan() {
        Map<Difficulty, Integer> plan = new LinkedHashMap<>();
        plan.put(Difficulty.BEGINNER, 2);
        plan.put(Difficulty.INTERMEDIATE, 2);
        plan.put(Difficulty.ADVANCED, 1);
        return plan;
    }

    static boolean alreadyGenerated(Path outDir, LocalDate date) throws Exception {
        if (!Files.isDirectory(outDir)) {
            return false;
        }
        try (Stream<Path> files = Files.list(outDir)) {
            return files.anyMatch(f -> f.getFileName().toString().startsWith(date + "-code-"));
        }
    }

    /** 같은 주제로 전에 낸 코드. 주제가 되풀이되므로 같은 함정을 다시 쓰지 않게 프롬프트에 싣는다. */
    static List<String> previousCodes(Path outDir, String topic) throws Exception {
        List<String> codes = new ArrayList<>();
        if (!Files.isDirectory(outDir)) {
            return codes;
        }
        try (Stream<Path> files = Files.list(outDir)) {
            // 파일 이름이 날짜로 시작하므로 이름순이 곧 시간순이다. 최근 것부터 담는다
            for (Path file : files.filter(f -> f.getFileName().toString().contains("-code-"))
                    .sorted(java.util.Comparator.reverseOrder()).toList()) {
                try {
                    GeneratedBatchFile batch = MAPPER.readValue(file.toFile(), GeneratedBatchFile.class);
                    if (batch.codeParts() != null && topic.equals(batch.codeTopic())) {
                        batch.codeParts().forEach(part -> codes.add(part.code()));
                    }
                } catch (Exception e) {
                    System.out.println("전에 낸 코드를 읽지 못해 건너뜁니다: " + file.getFileName() + " (" + e.getMessage() + ")");
                }
                if (codes.size() >= MAX_AVOID_CODES) {
                    break;
                }
            }
        }
        return codes.size() > MAX_AVOID_CODES ? codes.subList(0, MAX_AVOID_CODES) : codes;
    }

    /* ── 2. 실행 검증 ── */

    /** @param verifier 테스트가 가짜를 넣을 수 있게 받는다. 실제로는 코드를 컴파일하고 실행한다 */
    static void verify(Path outDir, Path workDir, CodeVerifier verifier, CodeFactChecker checker) throws Exception {
        Path candidatesFile = workDir.resolve(CANDIDATES_FILE);
        if (!Files.exists(candidatesFile)) {
            System.out.println("검증할 코드 문제 후보가 없습니다(생성 단계가 건너뛰었습니다).");
            return;
        }
        Candidates candidates = MAPPER.readValue(candidatesFile.toFile(), Candidates.class);
        LocalDate date = LocalDate.parse(candidates.date());

        Map<Difficulty, List<CodeProblem.Item>> passed = new EnumMap<>(Difficulty.class);
        List<String> dropped = new ArrayList<>();
        for (int i = 0; i < candidates.items().size(); i++) {
            CodeProblem.Item item = candidates.items().get(i);
            String defect = defectOf(item, verifier::defectOf);
            if (defect == null) {
                passed.computeIfAbsent(item.difficulty(), d -> new ArrayList<>()).add(item);
            } else {
                dropped.add("%d번 [%s] 「%s」 — %s".formatted(i + 1,
                        item == null || item.kind() == null ? "?" : item.kind().label(),
                        item == null || item.title() == null ? "" : item.title(), defect));
            }
        }

        Files.createDirectories(outDir);
        int saved = 0;
        List<String> flagged = new ArrayList<>();
        for (Map.Entry<Difficulty, List<CodeProblem.Item>> entry : passed.entrySet()) {
            List<ProblemReview.Finding> findings = factFindings(entry.getValue(), checker);
            for (ProblemReview.Finding f : findings) {
                flagged.add("「%s」 — %s".formatted(entry.getValue().get(f.problemIndex()).title(), f.type().label()));
            }
            Path file = outDir.resolve(fileNameOf(date, entry.getKey()));
            Files.writeString(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(
                    toBatchFile(candidates, date, entry.getKey(), entry.getValue()).withReviewFindings(findings)));
            saved += entry.getValue().size();
        }
        // 하나도 못 건진 주제에는 사용 표시를 찍지 않는다. 다음 주에 같은 주제를 다시 시도한다
        if (saved > 0 && candidates.topicIndex() >= 0) {
            TopicQueue queue = TopicQueue.read(outDir, TopicQueue.CODE_FILE_NAME);
            if (!queue.markUsed(outDir, new TopicQueue.Picked(candidates.topicIndex(), candidates.domain(), candidates.topic()), date)) {
                BatchReports.announce("⚠️ `%s`에 사용 기록을 못 남겼습니다 — \"%s\" 주제가 다음에 또 걸릴 수 있습니다."
                        .formatted(TopicQueue.CODE_FILE_NAME, candidates.topic()));
            }
        }
        BatchReports.announce(renderSummary(date, candidates, saved, dropped, flagged));
        Files.deleteIfExists(candidatesFile);
    }

    /**
     * 통과한 문제의 글을 실행 결과와 맞춰 본다. 지적은 파일에 실려 검수함에 달린다.
     * 대조가 실패해도 문제는 저장한다. 이미 정답을 확인한 문제다.
     */
    static List<ProblemReview.Finding> factFindings(List<CodeProblem.Item> items, CodeFactChecker checker) {
        List<ProblemReview.Finding> findings = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            try {
                findings.addAll(checker.findingsOf(i, items.get(i)));
            } catch (RuntimeException e) {
                System.out.printf("사실 대조를 건너뜁니다: 「%s」 (%s)%n", items.get(i).title(), e.getMessage());
            }
        }
        return findings;
    }

    /** 모양과 금지 소재를 먼저 보고, 통과한 것만 실행한다. 금지 소재가 든 코드는 한 번도 돌리지 않는다. */
    static String defectOf(CodeProblem.Item item, Function<CodeProblem.Item, String> runCheck) {
        String shape = CodeProblemRule.defectOf(item);
        if (shape != null) {
            return shape;
        }
        try {
            return runCheck.apply(item);
        } catch (RuntimeException e) {
            return "실행 검증 중 오류: " + e.getMessage();
        }
    }

    static String fileNameOf(LocalDate date, Difficulty difficulty) {
        String suffix = switch (difficulty) {
            case BEGINNER -> "beg";
            case INTERMEDIATE -> "int";
            case ADVANCED -> "adv";
        };
        return "%s-code-%s.json".formatted(date, suffix);
    }

    static GeneratedBatchFile toBatchFile(Candidates candidates, LocalDate date, Difficulty difficulty,
                                          List<CodeProblem.Item> items) {
        List<GeneratedProblemItem> problems = items.stream().map(CodeProblem::toProblemItem).toList();
        List<CodeProblem.Part> parts = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            parts.add(CodeProblem.Part.of(i, items.get(i)));
        }
        return new GeneratedBatchFile(
                "GitHub Actions가 자동 생성한 자바 코드 문제 초안입니다. 코드는 배치가 실제로 컴파일하고 실행해 "
                        + "정답과 대조했습니다. 로컬 앱이 기동할 때 검수 대기함으로 들여옵니다.",
                date.toString(), Instant.now().toString(), candidates.domain(), difficulty,
                ProblemType.MULTIPLE_CHOICE, candidates.model(), null, problems)
                .withCode(candidates.topic(), parts);
    }

    static String renderSummary(LocalDate date, Candidates candidates, int saved, List<String> dropped,
                                List<String> flagged) {
        StringBuilder sb = new StringBuilder(saved > 0 ? "✅" : "⚠️")
                .append(" **%s 코드 문제 %d건 저장** — 주제 \"%s\", 후보 %d개 중 실행 검증 통과 %d개"
                        .formatted(date, saved, candidates.topic(), candidates.items().size(), saved));
        if (!flagged.isEmpty()) {
            sb.append("\n\n🔎 실행 결과와 글이 어긋난 곳 ").append(flagged.size()).append("건 (검수함에 지적으로 답니다)\n")
                    .append(BatchReports.bullets(flagged));
        }
        if (!dropped.isEmpty()) {
            sb.append("\n\n버린 문제 ").append(dropped.size()).append("개 (검수함에 들이지 않습니다)\n")
                    .append(BatchReports.bullets(dropped));
        }
        if (saved == 0) {
            sb.append("\n\n통과한 문제가 없어 주제에 사용 표시를 찍지 않았습니다. 다음 실행에 같은 주제를 다시 시도합니다.");
        }
        return sb.toString();
    }
}
