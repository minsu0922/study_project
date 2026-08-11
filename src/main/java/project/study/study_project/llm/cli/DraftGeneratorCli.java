package project.study.study_project.llm.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.Yaml;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.llm.client.ClaudeProblemGenerator;
import project.study.study_project.llm.client.GeneratedProblemItem;
import project.study.study_project.llm.dto.GeneratedBatchFile;
import project.study.study_project.llm.support.GenerationSchedule;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 일일 생성 배치의 실행 진입점 — <b>GitHub Actions 러너에서 돈다</b>(docs/14, ADR-0006 개정).
 *
 * <p><b>왜 Spring을 띄우지 않는가.</b> 이 프로그램이 하는 일은 "Claude를 불러 결과를 파일로
 * 떨구기"뿐이라 DB도 웹서버도 필요 없다. Spring 컨텍스트를 띄우면 {@code ddl-auto: validate}가
 * MySQL 연결을 요구해서 러너에 DB 컨테이너를 붙여야 하고, 그러면 매일 도는 작업이 몇 배 느리고
 * 복잡해진다. {@code ClaudeProblemGeneratorE2ETest}가 이미 같은 방식(생성기만 직접 생성)으로
 * 매주 실제 API를 호출해 왔으므로, 이 경로는 새로 검증할 것도 없이 이미 증명돼 있다.
 *
 * <p><b>왜 여기서 검증하지 않는가.</b> 규약 검증(객관식 정답 정확히 1개 등)은 흡수 단계에서
 * {@code LlmProblemService}가 한다. 여기서도 걸러 버리면 같은 규칙이 두 곳에 생겨 언젠가
 * 어긋난다. 이 프로그램은 <b>모델이 준 것을 있는 그대로</b> 파일에 남긴다 — 나중에
 * "왜 이 문제가 버려졌지?"를 원본과 대조해 볼 수 있다는 부수 이점도 있다(프롬프트 개선 재료).
 *
 * <p><b>실패하면 반드시 0이 아닌 종료 코드로 죽는다.</b> 그래야 Actions job이 실패로 표시되고
 * GitHub이 저장소 소유자에게 메일을 보낸다. 기존 {@code LlmGenerationScheduler}는 예외를 삼키고
 * 로그만 남겨서 "조용히 죽는" 것이 문제였는데(그래서 주간 감시 워크플로를 따로 뒀다),
 * 이제는 배치 자신이 매일 울리는 화재경보기가 된다.
 *
 * <p>사용법(Gradle 태스크 {@code generateDrafts}가 감싼다):
 * <pre>
 *   --date=2026-08-12        기준 날짜(생략 시 오늘, 한국 시간). 파일명이자 순환 순번의 근거
 *   --domain=NETWORK         분야 강제 지정(생략 시 날짜 순환으로 결정)
 *   --difficulty=BEGINNER    난이도 강제 지정(생략 시 날짜 순환으로 결정)
 *   --count=5                생성 개수(생략 시 application.yml의 batch-count)
 *   --out=generated          출력 디렉터리
 * </pre>
 */
public final class DraftGeneratorCli {

    /** 결과 파일이 쌓이는 기본 디렉터리 — 저장소 루트 기준 상대 경로. */
    private static final String DEFAULT_OUT_DIR = "generated";

    /** 중복 회피 목록에 넣을 지문 수 상한 — 프롬프트 입력 토큰과의 균형점(서비스의 값과 동일). */
    private static final int AVOID_LIST_SIZE = 50;

    /** 정식 문제 지문을 내보내 둔 파일. 클라우드에는 DB가 없어 이 스냅샷으로 대신한다. */
    private static final String EXISTING_QUESTIONS_FILE = "_existing-questions.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DraftGeneratorCli() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parseArgs(args);

        // ── 1. 설정 읽기 ──────────────────────────────────────────
        // application.yml을 직접 읽는 이유: 모델 ID·후보 도메인을 워크플로에 따로 적어 두면
        // 설정을 바꿨을 때 두 곳이 어긋난다. 설정의 단일 출처는 언제나 application.yml이다.
        Map<String, Object> generation = readGenerationConfig();
        String model = (String) generation.getOrDefault("model", "claude-opus-5");
        int defaultCount = (int) generation.getOrDefault("batch-count", 5);
        List<Domain> batchDomains = parseDomains((String) generation.get("batch-domains"));

        // ── 2. 오늘 무엇을 만들지 결정 ────────────────────────────
        // 날짜는 한국 기준. 워크플로는 UTC로 도니까 여기서 변환하지 않으면 하루 어긋난다.
        LocalDate date = opts.containsKey("date")
                ? LocalDate.parse(opts.get("date"))
                : LocalDate.now(ZoneId.of("Asia/Seoul"));

        GenerationSchedule.Cell cell = GenerationSchedule.cellFor(date, batchDomains);
        // 수동 실행(workflow_dispatch)에서 특정 칸을 지정한 경우만 순환을 덮어쓴다
        Domain domain = opts.containsKey("domain") ? Domain.valueOf(opts.get("domain")) : cell.domain();
        Difficulty difficulty = opts.containsKey("difficulty")
                ? Difficulty.valueOf(opts.get("difficulty")) : cell.difficulty();
        // 유형은 객관식 고정 — 보기·해설이 함께 생성돼 검수 가치가 가장 높다(OX·단답형은 관리자 버튼으로)
        ProblemType type = ProblemType.MULTIPLE_CHOICE;
        int count = opts.containsKey("count") ? Integer.parseInt(opts.get("count")) : defaultCount;

        Path outDir = Path.of(opts.getOrDefault("out", DEFAULT_OUT_DIR));
        Path outFile = outDir.resolve(date + ".json");

        // ── 3. 멱등성 — 같은 날짜 파일이 이미 있으면 아무것도 하지 않는다 ──
        // 워크플로를 수동으로 두 번 눌러도 API 요금이 두 번 나가지 않게 하는 안전장치.
        if (Files.exists(outFile)) {
            System.out.println("이미 생성됨, 건너뜀: " + outFile);
            return;
        }

        // ── 4. 중복 회피 목록 ─────────────────────────────────────
        List<String> avoid = buildAvoidList(outDir, domain);
        System.out.printf("생성 시작: %s × %s, %d문제 (모델 %s, 중복 회피 %d건)%n",
                domain, difficulty, count, model, avoid.size());

        // ── 5. 실제 호출 ──────────────────────────────────────────
        List<GeneratedProblemItem> problems =
                new ClaudeProblemGenerator(model).generate(domain, difficulty, type, count, avoid);

        // 빈 응답은 성공이 아니다 — 조용히 빈 파일을 커밋하면 "돌긴 돌았는데 왜 문제가 없지"가 된다.
        // 예외를 던져 job을 실패시키고 메일을 받는 쪽이 낫다.
        if (problems == null || problems.isEmpty()) {
            throw new IllegalStateException("모델이 문제를 하나도 반환하지 않았습니다 — 프롬프트/모델 설정을 확인하세요.");
        }

        // ── 6. 파일로 저장 ────────────────────────────────────────
        GeneratedBatchFile batch = new GeneratedBatchFile(
                "GitHub Actions가 자동 생성한 문제 초안입니다. 로컬 앱이 기동할 때 검수 대기함으로 흡수합니다(docs/14). 손으로 고쳐도 되지만, 흡수 시 규약 검증을 다시 거칩니다.",
                date.toString(), Instant.now().toString(),
                domain, difficulty, type, model, problems);

        Files.createDirectories(outDir);
        Files.writeString(outFile, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(batch));
        System.out.printf("저장 완료: %s (%d문제)%n", outFile, problems.size());
    }

    /* ── 중복 회피 목록 ───────────────────────────────────────── */

    /**
     * 같은 분야의 기존 지문을 모은다 — 정식 문제 스냅샷 + 이전에 생성된 배치 파일들.
     *
     * <p>DB를 볼 수 있던 시절에는 problem 테이블과 PENDING 초안을 직접 조회했다. 클라우드에는
     * DB가 없으므로 <b>저장소에 있는 것</b>으로 대신한다. 정확도가 약간 떨어지는 대목은
     * "승인/거절 결과"를 모른다는 점인데, 어차피 거절된 문제도 다시 만들면 안 되는 것이므로
     * 생성된 전부를 회피 목록에 넣는 편이 오히려 안전하다.
     *
     * <p>최신 것부터 {@link #AVOID_LIST_SIZE}개까지만 넣는다 — 파일이 쌓일수록 프롬프트가
     * 무한정 길어지면 입력 토큰 비용이 계속 오르기 때문. 파일명이 날짜라 이름 역순 정렬이
     * 곧 최신순이다.
     */
    private static List<String> buildAvoidList(Path outDir, Domain domain) throws Exception {
        List<String> avoid = new ArrayList<>();

        // (1) 이전에 생성된 배치 파일 — 최신 날짜부터
        if (Files.isDirectory(outDir)) {
            List<Path> batchFiles;
            try (var stream = Files.list(outDir)) {
                batchFiles = stream
                        .filter(p -> p.getFileName().toString().endsWith(".json"))
                        .filter(p -> !p.getFileName().toString().startsWith("_")) // 스냅샷 등 특수 파일 제외
                        .sorted(java.util.Comparator.reverseOrder())
                        .toList();
            }
            for (Path file : batchFiles) {
                if (avoid.size() >= AVOID_LIST_SIZE) {
                    break;
                }
                GeneratedBatchFile batch = MAPPER.readValue(file.toFile(), GeneratedBatchFile.class);
                if (batch.domain() != domain || batch.problems() == null) {
                    continue;
                }
                batch.problems().stream().map(GeneratedProblemItem::question).forEach(avoid::add);
            }
        }

        // (2) 정식 문제 스냅샷 — 시드 문제처럼 파일로만 알 수 있는 것들
        Path existing = outDir.resolve(EXISTING_QUESTIONS_FILE);
        if (Files.exists(existing) && avoid.size() < AVOID_LIST_SIZE) {
            ExistingQuestions snapshot = MAPPER.readValue(existing.toFile(), ExistingQuestions.class);
            if (snapshot.questions() != null) {
                snapshot.questions().stream()
                        .filter(q -> q.domain() == domain)
                        .map(ExistingQuestion::question)
                        .limit(AVOID_LIST_SIZE - avoid.size())
                        .forEach(avoid::add);
            }
        }
        return avoid;
    }

    /** {@code generated/_existing-questions.json}의 형태 — 이 CLI만 읽으므로 여기 둔다. */
    private record ExistingQuestions(String note, String exportedAt, List<ExistingQuestion> questions) {
    }

    private record ExistingQuestion(Domain domain, String question) {
    }

    /* ── 설정·인자 파싱 ───────────────────────────────────────── */

    /**
     * 클래스패스의 application.yml에서 {@code llm.generation} 블록을 읽는다.
     * Spring 없이 설정을 읽어야 해서 snakeyaml을 직접 쓴다(E2E 테스트와 같은 방식).
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> readGenerationConfig() throws Exception {
        try (InputStream in = DraftGeneratorCli.class.getResourceAsStream("/application.yml")) {
            if (in == null) {
                throw new IllegalStateException("클래스패스에서 application.yml을 찾을 수 없습니다.");
            }
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> llm = (Map<String, Object>) root.get("llm");
            return (Map<String, Object>) llm.get("generation");
        }
    }

    /** "NETWORK,OS,..." → enum 목록. 비어 있으면 빈 목록(스케줄이 전체 도메인으로 보정한다). */
    private static List<Domain> parseDomains(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(Domain::valueOf).toList();
    }

    /** {@code --key=value} 형태만 받는다. 빈 값(--domain=)은 "지정 안 함"으로 본다 — 워크플로 입력이 비면 그렇게 온다. */
    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> opts = new java.util.HashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--") || !arg.contains("=")) {
                continue;
            }
            String[] kv = arg.substring(2).split("=", 2);
            if (!kv[1].isBlank()) {
                opts.put(kv[0], kv[1].trim());
            }
        }
        return opts;
    }
}
