package project.study.study_project.llm.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.llm.client.ClaudeProblemGenerator;
import project.study.study_project.llm.client.GeneratedProblemItem;
import project.study.study_project.llm.client.SourceDocument;
import project.study.study_project.llm.dto.GeneratedDocumentFile;
import project.study.study_project.llm.support.ProblemItemRule;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 프롬프트 평가 하네스 — <b>프롬프트를 고쳤을 때 실제로 나아졌는지</b>를 몇 분 만에 재는 도구.
 *
 * <h2>왜 만들었나 — 피드백 루프가 하루였다</h2>
 *
 * <p>지금까지 프롬프트를 고치는 절차는 이랬다.
 * <pre>
 *   프롬프트 수정 → 커밋 → 하루 대기 → 문제 5개 확인 → 추측 → 다시 수정
 * </pre>
 * <b>하루에 한 번, 표본 5개로 튜닝</b>하고 있었다는 뜻이다. 그것도 매일 분야가 바뀌므로
 * 어제와 오늘의 차이가 프롬프트 때문인지 주제 때문인지 갈라낼 수가 없다. 이런 조건에서는
 * "고쳤더니 나아졌다"가 <b>느낌</b> 이상이 되지 못한다.
 *
 * <p>이 도구는 조건을 고정하고 한 번에 세 난이도를 뽑아 자동으로 채점한다. 근거 문서를
 * 고정하므로 주제 변수가 사라지고, 결과가 파일로 남으므로 수정 전후를 나란히 놓고 볼 수 있다.
 *
 * <h2>무엇을 재고 무엇을 안 재는가</h2>
 *
 * <p>이 저장소가 세운 규칙 그대로다 — <b>셀 수 있는 것만 기계가 채점하고, 판단이 필요한 것은
 * 사람이 보게 표본을 뽑아 준다.</b> 오답이 "조건이 달랐다면 옳았을 대응"인지는 세어 볼 방법이
 * 없으므로, 그 판정을 흉내 내는 대신 난이도마다 문항 하나를 보기까지 통째로 찍어 준다.
 * 기계가 못 재는 것을 재는 척하면, 숫자가 좋아졌는데 물건은 나빠지는 상태를 못 알아챈다.
 *
 * <p>채점 기준은 {@link ProblemItemRule}을 그대로 쓴다. 여기서 따로 조건을 적으면
 * 배치·흡수와 자가 갈라져, 하네스는 통과라는데 실제 배치는 경고를 뱉는 상태가 된다
 * (이 저장소가 이미 두 번 겪은 사고 방식 — {@code ProblemItemRule} 클래스 주석).
 *
 * <h2>요금과 안전장치</h2>
 *
 * <p>실제 API를 부른다. 기본값(난이도당 3문제, 총 9문제)이면 하루치 배치의 약 두 배다.
 * 배치의 중단 스위치({@code batch-enabled})는 <b>보지 않는다</b> — 배치를 꺼 둔 채로
 * 프롬프트를 고치는 상황이 바로 이 도구가 필요한 때이기 때문이다. 대신 사람이 직접
 * 실행할 때만 도는 별도 태스크로 두어, 예약 실행에 딸려 들어갈 일이 없게 했다.
 *
 * <p>중복 회피 목록과 거절 사례는 <b>일부러 넣지 않는다.</b> 그 둘은 날마다 자라므로
 * 넣으면 같은 프롬프트로도 다음 주에 다른 결과가 나온다 — 비교의 근거가 무너진다.
 *
 * <p>사용법(Gradle 태스크 {@code evalPrompt}가 감싼다):
 * <pre>
 *   --count=3                난이도당 생성 개수(기본 3)
 *   --document=경로          근거 문서 JSON. 생략하면 generated/documents의 최신 파일
 *   --domain=DATABASE        분야. 생략하면 근거 문서에 기록된 분야
 *   --label=before-fix       보고서 파일 이름에 붙일 꼬리표(수정 전/후를 구분할 때)
 *   --out=eval               보고서를 쌓을 디렉터리
 * </pre>
 */
public final class PromptEvalCli {

    /** 보고서가 쌓이는 기본 디렉터리. 생성물이라 저장소에 넣지 않는다(.gitignore). */
    private static final String DEFAULT_OUT_DIR = "eval";

    /** 근거 문서가 쌓이는 곳 — {@code DraftGeneratorCli}가 쓰는 위치와 같아야 한다. */
    private static final Path DOCUMENT_DIR = Path.of("generated", "documents");

    /**
     * 난이도당 기본 생성 개수.
     *
     * <p>3인 이유는 요금과 표본 사이의 타협이다. 셋이면 총 9문제로 하루치 배치의 약 두 배인데,
     * "지문이 전부 길어졌다" 같은 <b>쏠림</b>은 셋만 봐도 드러난다. 반대로 1~2로 줄이면
     * 한 문항의 우연이 전체 평균을 흔들어 비교가 무의미해진다.
     */
    private static final int DEFAULT_COUNT = 3;

    /**
     * 서술문 하나 — 지문에 <b>배경 서술</b>이 붙었는지 재는 잣대.
     *
     * <p>초급 규칙의 핵심은 "상황 없이 한두 문장"인데, 그건 그대로는 셀 수가 없다.
     * 한국어에서 배경 서술은 거의 예외 없이 {@code ~다.}로 끝나므로 그것을 센다.
     * 질문만 있는 지문("…의 이름은?")은 0이고, 배경이 붙으면 1 이상이 된다.
     *
     * <p>실측으로 맞춰 본 잣대다 — 2026-08-16 초급 5문제 중 <b>45자짜리 하나만</b> 0이 나오고
     * 나머지 넷은 1 이상이었는데, 눈으로 읽은 판정과 정확히 같았다.
     *
     * <p><b>닫는 괄호를 사이에 허용하는 것이 핵심이다.</b> 08-16 1번이
     * "…삽입하고 <b>커밋했다).</b>" 형태였는데, {@code 다.}만 찾으면 이걸 놓친다.
     * 하필 가장 전형적인 배경 서술이 괄호로 부연을 다는 형태라, 이 한 글자를 빠뜨리면
     * 잣대가 정반대 결론을 낸다.
     */
    private static final Pattern DECLARATIVE_SENTENCE = Pattern.compile("다[)\\]]?[.]([\\s)]|$)");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PromptEvalCli() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = DraftGeneratorCli.parseArgs(args);
        Map<String, Object> generation = DraftGeneratorCli.readGenerationConfig();
        String model = (String) generation.getOrDefault("model", "claude-opus-5");
        int count = opts.containsKey("count") ? Integer.parseInt(opts.get("count")) : DEFAULT_COUNT;

        Path documentFile = resolveDocumentFile(opts.get("document"));
        LoadedDocument loaded = readDocument(documentFile);
        Domain domain = opts.containsKey("domain")
                ? Domain.valueOf(opts.get("domain")) : loaded.domain();

        System.out.printf("프롬프트 평가 시작: 모델 %s, 난이도당 %d문제, 분야 %s%n", model, count, domain);
        System.out.printf("근거 문서: %s (\"%s\", %d자)%n",
                documentFile, loaded.document().title(), loaded.document().contentMd().length());

        ClaudeProblemGenerator generator = new ClaudeProblemGenerator(model);
        List<DifficultyReport> reports = new ArrayList<>();
        for (Difficulty difficulty : Difficulty.values()) {
            System.out.printf("  %s 생성 중...%n", difficulty);
            // 회피 목록·거절 사례를 비운다 — 날마다 자라는 입력을 넣으면 비교가 무너진다(클래스 주석)
            List<GeneratedProblemItem> problems = generator.generate(
                    domain, difficulty, ProblemType.MULTIPLE_CHOICE, count,
                    List.of(), List.of(), loaded.document());
            reports.add(score(difficulty, count, problems));
        }

        String report = render(reports, model, domain, documentFile, loaded);
        System.out.println();
        System.out.println(report);

        Path outFile = writeReport(report, opts);
        System.out.println("보고서 저장: " + outFile);
        System.out.println("이전 실행과 나란히 놓고 보려면 eval/ 아래 두 파일을 비교하세요.");
    }

    /* ── 채점 ────────────────────────────────────────────────── */

    /**
     * 한 난이도의 결과를 센다. <b>순수 함수</b>라 API 없이 테스트할 수 있다 —
     * 이 도구에서 정작 틀리기 쉬운 곳은 모델 호출이 아니라 여기다.
     */
    static DifficultyReport score(Difficulty difficulty, int requested,
                                  List<GeneratedProblemItem> problems) {
        List<Integer> questionLengths = new ArrayList<>();
        List<Integer> explanationLengths = new ArrayList<>();
        Map<String, Integer> warningCounts = new LinkedHashMap<>();
        int usable = 0;
        int withBackground = 0;

        for (GeneratedProblemItem item : problems) {
            if (!ProblemItemRule.isUsable(item, ProblemType.MULTIPLE_CHOICE)) {
                continue; // 껍데기는 길이 평균을 오염시킨다 — 세지 않고 개수 차이로만 드러낸다
            }
            usable++;
            String question = item.question().trim();
            questionLengths.add(question.length());
            if (item.explanation() != null && !item.explanation().isBlank()) {
                explanationLengths.add(item.explanation().trim().length());
            }
            if (countDeclarativeSentences(question) > 0) {
                withBackground++;
            }
            for (String warning : ProblemItemRule.qualityWarningsOf(item, difficulty)) {
                // "해설이 짧음 (382자, 기준 400자)"에서 숫자를 떼어 종류로 묶는다.
                // 숫자까지 키로 쓰면 표가 한 줄짜리 항목으로 가득 차 한눈에 안 들어온다.
                String kind = warning.replaceAll("\\s*\\(.*", "");
                warningCounts.merge(kind, 1, Integer::sum);
            }
        }

        return new DifficultyReport(difficulty, requested, problems.size(), usable,
                Stat.of(questionLengths), Stat.of(explanationLengths),
                withBackground, warningCounts, problems);
    }

    /** 지문에 섞인 서술문의 개수 — {@link #DECLARATIVE_SENTENCE} 참고. */
    static int countDeclarativeSentences(String question) {
        if (question == null) {
            return 0;
        }
        var m = DECLARATIVE_SENTENCE.matcher(question);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    /** 한 난이도의 채점 결과. */
    record DifficultyReport(Difficulty difficulty, int requested, int received, int usable,
                            Stat questionLength, Stat explanationLength,
                            int withBackground, Map<String, Integer> warnings,
                            List<GeneratedProblemItem> problems) {
    }

    /**
     * 최소·평균·최대. 평균만 보면 "전부 조금씩 길다"와 "하나가 아주 길다"를 구분할 수 없는데,
     * 그 둘은 고쳐야 할 곳이 다르다(프롬프트의 기준이냐, 한 문항의 사고냐).
     */
    record Stat(int min, double avg, int max, int size) {
        static Stat of(List<Integer> values) {
            if (values.isEmpty()) {
                return new Stat(0, 0, 0, 0);
            }
            return new Stat(
                    values.stream().mapToInt(Integer::intValue).min().orElseThrow(),
                    values.stream().mapToInt(Integer::intValue).average().orElseThrow(),
                    values.stream().mapToInt(Integer::intValue).max().orElseThrow(),
                    values.size());
        }

        String render() {
            return size == 0 ? "—" : "%d / %.0f / %d".formatted(min, avg, max);
        }
    }

    /* ── 보고서 ──────────────────────────────────────────────── */

    /**
     * 마크다운 보고서. 표로 만드는 이유는 <b>두 실행을 나란히 놓고 보기 위해서</b>다 —
     * 줄글이면 무엇이 달라졌는지 눈으로 좇을 수가 없다.
     */
    static String render(List<DifficultyReport> reports, String model, Domain domain,
                         Path documentFile, LoadedDocument loaded) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 프롬프트 평가 보고서\n\n");
        sb.append("- 시각: ").append(LocalDateTime.now()).append('\n');
        sb.append("- 모델: ").append(model).append('\n');
        sb.append("- 분야: ").append(domain).append('\n');
        sb.append("- 근거 문서: ").append(documentFile).append(" (\"")
                .append(loaded.document().title()).append("\")\n");

        // 근거 문서에 그 난이도가 캘 절이 없으면 숫자를 곧이곧대로 읽으면 안 된다.
        // 모델이 다른 절에서 캔 결과라 프롬프트가 아니라 문서를 재고 있는 셈이 된다.
        List<String> missing = missingSections(loaded.document().contentMd());
        if (!missing.isEmpty()) {
            sb.append("- ⚠️ 이 문서에 없는 절: ").append(String.join(", ", missing))
                    .append(" — 해당 난이도 숫자는 프롬프트가 아니라 문서의 상태를 반영합니다\n");
        }
        sb.append('\n');

        sb.append("## 한눈에 보기\n\n");
        sb.append("| 난이도 | 요청→응답→유효 | 지문 길이(최소/평균/최대) | 해설 길이 | 배경 서술이 붙은 문항 |\n");
        sb.append("|---|---|---|---|---|\n");
        for (DifficultyReport r : reports) {
            sb.append("| %s | %d→%d→%d | %s | %s | %d / %d |\n".formatted(
                    r.difficulty(), r.requested(), r.received(), r.usable(),
                    r.questionLength().render(), r.explanationLength().render(),
                    r.withBackground(), r.usable()));
        }

        sb.append("\n초급의 '배경 서술이 붙은 문항'은 **0이 목표**입니다");
        sb.append(" — 상황을 붙이는 순간 중급으로 넘어갑니다.\n");

        sb.append("\n## 품질 경고\n\n");
        boolean any = reports.stream().anyMatch(r -> !r.warnings().isEmpty());
        if (!any) {
            sb.append("없음.\n");
        } else {
            for (DifficultyReport r : reports) {
                if (r.warnings().isEmpty()) {
                    continue;
                }
                sb.append("- **").append(r.difficulty()).append("**: ");
                sb.append(r.warnings().entrySet().stream()
                        .map(e -> "%s %d건".formatted(e.getKey(), e.getValue()))
                        .reduce((a, b) -> a + ", " + b).orElse(""));
                sb.append('\n');
            }
        }

        // 사람이 읽어야 하는 부분. 오답이 "조건이 달랐다면 옳았을 대응"인지는 셀 수가 없다.
        sb.append("\n## 눈으로 볼 표본 — 오답 설계\n\n");
        sb.append("숫자로는 중급과 고급이 구분되지 않습니다. 갈리는 곳은 오답입니다:\n");
        sb.append("중급 오답에는 **명백히 틀린 것**이 있어야 하고, 고급 오답은 **넷 다 그럴듯**해야 합니다.\n\n");
        for (DifficultyReport r : reports) {
            sb.append("### ").append(r.difficulty()).append('\n');
            r.problems().stream()
                    .filter(p -> ProblemItemRule.isUsable(p, ProblemType.MULTIPLE_CHOICE))
                    .findFirst()
                    .ifPresentOrElse(p -> {
                        sb.append('\n').append(p.question()).append("\n\n");
                        p.choices().forEach(c -> sb.append(c.correct() ? "- **(정답)** " : "- ")
                                .append(c.text()).append('\n'));
                    }, () -> sb.append("\n(쓸 수 있는 문항이 없습니다)\n"));
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 세 난이도가 캘 절 중 이 문서에 <b>없는</b> 것. 숫자를 잘못 읽지 않게 보고서 머리에 적는다. */
    static List<String> missingSections(String contentMd) {
        return ClaudeProblemGenerator.SOURCE_SECTIONS.values().stream()
                .flatMap(List::stream)
                .distinct()
                .filter(section -> !contentMd.contains(section))
                .toList();
    }

    /* ── 입출력 ──────────────────────────────────────────────── */

    /** 근거 문서 파일 — 지정이 없으면 가장 최근 것. 어느 것을 썼는지는 보고서에 남는다. */
    private static Path resolveDocumentFile(String specified) throws Exception {
        if (specified != null) {
            return Path.of(specified);
        }
        if (!Files.isDirectory(DOCUMENT_DIR)) {
            throw new IllegalStateException(
                    "근거 문서 디렉터리가 없습니다: " + DOCUMENT_DIR + " — --document= 로 직접 지정하세요.");
        }
        try (Stream<Path> files = Files.list(DOCUMENT_DIR)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .max(Comparator.comparing(p -> p.getFileName().toString()))
                    .orElseThrow(() -> new IllegalStateException(
                            "근거 문서가 하나도 없습니다: " + DOCUMENT_DIR));
        }
    }

    private static LoadedDocument readDocument(Path file) throws Exception {
        GeneratedDocumentFile parsed = MAPPER.readValue(file.toFile(), GeneratedDocumentFile.class);
        var doc = parsed.document();
        if (doc == null || doc.contentMd() == null || doc.contentMd().isBlank()) {
            throw new IllegalStateException("근거 문서 본문이 비어 있습니다: " + file);
        }
        return new LoadedDocument(parsed.domain() == null ? Domain.DATABASE : parsed.domain(),
                new SourceDocument(doc.slug(), doc.title(), doc.contentMd()));
    }

    /** 읽어 온 근거 문서와 그 분야. */
    record LoadedDocument(Domain domain, SourceDocument document) {
    }

    /**
     * 보고서를 파일로 남긴다. 파일 이름에 시각과 꼬리표를 넣는 이유는 <b>덮어쓰지 않기 위해서</b>다 —
     * 이 도구의 값어치는 수정 <b>전후</b>를 나란히 놓는 데 있는데, 같은 이름에 쓰면 앞의 것이 사라진다.
     */
    private static Path writeReport(String report, Map<String, String> opts) throws Exception {
        Path dir = Path.of(opts.getOrDefault("out", DEFAULT_OUT_DIR));
        Files.createDirectories(dir);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String label = opts.containsKey("label") ? "-" + opts.get("label") : "";
        Path file = dir.resolve(stamp + label + ".md");
        Files.writeString(file, report);
        return file;
    }
}
