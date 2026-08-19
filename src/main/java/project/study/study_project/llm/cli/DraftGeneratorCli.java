package project.study.study_project.llm.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.Yaml;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.llm.client.ClaudeDocumentGenerator;
import project.study.study_project.llm.client.ClaudeProblemGenerator;
import project.study.study_project.llm.client.GeneratedDocumentItem;
import project.study.study_project.llm.client.GeneratedProblemItem;
import project.study.study_project.llm.client.RejectionNote;
import project.study.study_project.llm.client.SourceDocument;
import project.study.study_project.llm.dto.GeneratedBatchFile;
import project.study.study_project.llm.dto.GeneratedDocumentFile;
import project.study.study_project.llm.dto.RejectionNotesFile;
import project.study.study_project.llm.support.DocumentCheck;
import project.study.study_project.llm.support.DocumentDraftValidator;
import project.study.study_project.llm.support.GenerationSchedule;
import project.study.study_project.llm.support.ProblemItemRule;
import project.study.study_project.llm.support.TopicQueue;

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
 *   --topic=슬로우쿼리        문서 주제 강제 지정(문서일에만. 생략 시 주제 대기열 → 모델 자동 선택)
 *                            공백이 든 주제는 환경변수 DRAFT_TOPIC으로 넘긴다
 *   --difficulty=BEGINNER    난이도 강제 지정(생략 시 날짜 순환으로 결정)
 *   --count=5                생성 개수(생략 시 application.yml의 batch-count)
 *   --out=generated          출력 디렉터리
 *   --force=true             batch-enabled=false여도 이번 한 번은 생성(수동 실행 전용)
 * </pre>
 *
 * <p><b>중단 스위치</b>: {@code llm.generation.batch-enabled=false}면 API를 부르지 않고 즉시
 * 정상 종료한다. 급히 멈출 때는 GitHub Actions의 "Disable workflow" 버튼이 더 빠르고,
 * 이 설정은 <b>중단 사실을 저장소에 기록으로 남기고 싶을 때</b> 쓴다 — 버튼은 눈에 안 보이는
 * 곳에 있어서 "왜 요즘 문제가 안 들어오지?"의 답을 찾기 어렵다.
 */
public final class DraftGeneratorCli {

    /** 결과 파일이 쌓이는 기본 디렉터리 — 저장소 루트 기준 상대 경로. */
    private static final String DEFAULT_OUT_DIR = "generated";

    /** 중복 회피 목록에 넣을 지문 수 상한 — 프롬프트 입력 토큰과의 균형점(서비스의 값과 동일). */
    private static final int AVOID_LIST_SIZE = 50;

    /** 정식 문제 지문을 내보내 둔 파일. 클라우드에는 DB가 없어 이 스냅샷으로 대신한다. */
    private static final String EXISTING_QUESTIONS_FILE = "_existing-questions.json";

    /** 검수자의 거절 사례 스냅샷. 로컬 앱(RejectionNotesExporter)이 쓰고 여기서 읽는다. */
    private static final String REJECTION_NOTES_FILE = "_rejection-notes.json";

    /** 개념 문서 결과가 쌓이는 하위 디렉터리. 문제 파일과 형식이 달라 폴더로 분리한다(docs/15). */
    private static final String DOCUMENT_SUBDIR = "documents";

    /** 기존 문서 제목·태그 스냅샷. 문서 주제 중복을 피하고 태그 난립을 막는 데 쓴다. */
    private static final String EXISTING_DOCUMENTS_FILE = "_existing-documents.json";

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

        // ── 2. 중단 스위치 ────────────────────────────────────────
        // 값이 없으면 켜진 것으로 본다 — 설정 키가 사라졌다고 배치가 멈추면
        // "왜 안 돌지?"를 한참 뒤에 알게 된다(조용한 정지가 조용한 실패보다 낫지 않다).
        boolean batchEnabled = !Boolean.FALSE.equals(generation.get("batch-enabled"));
        boolean force = "true".equalsIgnoreCase(opts.getOrDefault("force", "false"));
        if (!shouldGenerate(batchEnabled, force)) {
            System.out.println("배치가 꺼져 있어 생성하지 않습니다 "
                    + "(llm.generation.batch-enabled=false). 수동 실행 시 force=true로 한 번만 무시할 수 있습니다.");
            return; // 종료 코드 0 — "의도된 중단"은 실패가 아니므로 알림 메일이 오면 안 된다
        }

        // ── 3. 오늘 무엇을 만들지 결정 ────────────────────────────
        // 날짜는 한국 기준. 워크플로는 UTC로 도니까 여기서 변환하지 않으면 하루 어긋난다.
        LocalDate date = resolveDate(opts);
        GenerationSchedule.Plan plan = GenerationSchedule.planFor(date, batchDomains);

        // 스냅샷이 낡았는지 여기서 한 번 본다 — 생성 성공/실패와 무관하게 알려야 하므로 맨 앞에 둔다.
        warnIfSnapshotsAreStale(Path.of(opts.getOrDefault("out", DEFAULT_OUT_DIR)), date);

        // 개념 문서는 대상 선정 방식도, 출력 위치(generated/documents/)도 다르다.
        // 한 흐름 안에서 if로 갈라면 두 관심사가 뒤엉키므로 아예 따로 뗀다.
        BatchAction action = decideAction(
                opts.get("type"), (String) generation.get("batch-type"), plan.documentDay());
        if (action == BatchAction.DOCUMENT) {
            generateDocument(opts, model, batchDomains);
            return;
        }
        if (action == BatchAction.SKIP) {
            System.out.println("오늘은 만들 것이 없습니다 "
                    + "(llm.generation.batch-type=document인데 문서일이 아님). 요금 0으로 종료합니다.");
            return; // 종료 코드 0 — "의도된 쉬는 날"은 실패가 아니다
        }

        // 수동 실행(workflow_dispatch)에서 특정 칸을 지정한 경우만 주기를 덮어쓴다
        Domain domain = opts.containsKey("domain") ? Domain.valueOf(opts.get("domain")) : plan.domain();
        Difficulty difficulty = opts.containsKey("difficulty")
                ? Difficulty.valueOf(opts.get("difficulty")) : plan.difficulty();
        // 유형은 객관식 고정 — 보기·해설이 함께 생성돼 검수 가치가 가장 높다(OX·단답형은 관리자 버튼으로)
        ProblemType type = ProblemType.MULTIPLE_CHOICE;
        int count = opts.containsKey("count") ? Integer.parseInt(opts.get("count")) : defaultCount;

        Path outDir = Path.of(opts.getOrDefault("out", DEFAULT_OUT_DIR));
        Path outFile = outDir.resolve(date + ".json");

        // ── 4. 멱등성 — 같은 날짜 파일이 이미 있으면 아무것도 하지 않는다 ──
        // 워크플로를 수동으로 두 번 눌러도 API 요금이 두 번 나가지 않게 하는 안전장치.
        if (Files.exists(outFile)) {
            System.out.println("이미 생성됨, 건너뜀: " + outFile);
            return;
        }

        // ── 5. 근거 문서 찾기(2단계) ──────────────────────────────
        // 못 찾으면 null — 그때는 예전처럼 모델의 지식으로 만든다(폴백). 문서 생성이 실패했거나
        // 검수에서 거절된 주기에도 그날의 문제는 나와야 하기 때문.
        ResolvedSource resolved = findSourceDocument(outDir, plan, difficulty);
        SourceDocument source = resolved == null ? null : resolved.document();

        if (source == null && !opts.containsKey("difficulty")) {
            // 근거가 없으면 "이번 주기의 난이도"를 쓸 이유도 없다. 주기가 헛도는 동안
            // 같은 분야·난이도만 반복되지 않게 옛 규칙(매일 분야가 바뀌는 24칸 순환)으로 돌아간다.
            GenerationSchedule.Cell fallback = GenerationSchedule.cellFor(date, batchDomains);
            if (!opts.containsKey("domain")) {
                domain = fallback.domain();
            }
            difficulty = fallback.difficulty();
        } else if (resolved != null && !opts.containsKey("domain")) {
            Domain aligned = alignDomainWithDocument(domain, resolved.domain());
            if (aligned != domain) {
                System.out.printf("주기 분야(%s)와 근거 문서 분야(%s)가 달라 문서 쪽으로 맞춥니다%n",
                        domain, aligned);
                domain = aligned;
            }
        }

        // ── 6. 중복 회피 목록 + 거절 사례 되먹이기 ────────────────
        List<String> avoid = buildAvoidList(outDir, domain);
        List<RejectionNote> rejectionNotes = readRejectionNotes(outDir);
        System.out.printf("생성 시작: %s × %s, %d문제 (모델 %s, 근거 문서 %s, 중복 회피 %d건, 거절 사례 %d건)%n",
                domain, difficulty, count, model,
                source == null ? "없음(폴백)" : source.slug(), avoid.size(), rejectionNotes.size());

        // ── 7. 실제 호출 ──────────────────────────────────────────
        List<GeneratedProblemItem> problems = new ClaudeProblemGenerator(model)
                .generate(domain, difficulty, type, count, avoid, rejectionNotes, source);

        // 빈 응답은 성공이 아니다 — 조용히 빈 파일을 커밋하면 "돌긴 돌았는데 왜 문제가 없지"가 된다.
        // 예외를 던져 job을 실패시키고 메일을 받는 쪽이 낫다.
        if (problems == null || problems.isEmpty()) {
            throw new IllegalStateException("모델이 문제를 하나도 반환하지 않았습니다 — 프롬프트/모델 설정을 확인하세요.");
        }

        // ── 7-1. 수확량 점검 ──────────────────────────────────────
        // "목록이 비었나"만 봐서는 부족했다. 2026-08-14 배치는 5개를 요청해 3개를 받았고
        // 그중 하나가 지문·해설이 빈 껍데기여서 실제로 쓴 건 2개인데, job은 초록불로 끝났다.
        // 검수함에 문제가 적게 들어온 것을 사람이 먼저 눈치챈 뒤에야 파일을 열어 보고 알았다.
        reportYield(checkYield(problems, count, type, difficulty), date);

        // ── 8. 파일로 저장 ────────────────────────────────────────
        GeneratedBatchFile batch = new GeneratedBatchFile(
                "GitHub Actions가 자동 생성한 문제 초안입니다. 로컬 앱이 기동할 때 검수 대기함으로 흡수합니다(docs/14). 손으로 고쳐도 되지만, 흡수 시 규약 검증을 다시 거칩니다.",
                date.toString(), Instant.now().toString(),
                domain, difficulty, type, model,
                source == null ? null : source.slug(), problems);

        Files.createDirectories(outDir);
        Files.writeString(outFile, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(batch));
        System.out.printf("저장 완료: %s (%d문제)%n", outFile, problems.size());
    }

    /** 오늘 배치가 할 일. {@link #decideAction}이 결정한다. */
    enum BatchAction {
        /** 개념 문서 한 편을 만든다. */
        DOCUMENT,
        /** 문제를 만든다. */
        PROBLEM,
        /** 아무것도 만들지 않고 정상 종료한다(요금 0). */
        SKIP
    }

    /**
     * 오늘 무엇을 만들지 정한다 — 우선순위는 <b>수동 지정 &gt; 설정 &gt; 날짜 주기</b>다.
     *
     * <p><b>수동이 가장 세다</b>: 사람이 워크플로에서 "오늘 문서 뽑아"라고 눌렀는데 주기나 설정이
     * 막으면 그건 버그처럼 보인다. 수동 실행은 그 한 번만 유효하고 저장소 설정을 건드리지 않으므로
     * 되돌리는 것을 잊어 사고가 나지도 않는다({@code force} 스위치와 같은 판단).
     *
     * <p><b>설정({@code llm.generation.batch-type})의 뜻</b>
     * <ul>
     *   <li>{@code auto}(기본): 4일 주기 그대로 — 0일차 문서, 나머지 문제
     *   <li>{@code problem}: 문서일에도 문제를 만든다. <b>2단계가 사실상 꺼진다</b> —
     *       문서를 안 만드니 근거로 삼을 문서가 안 생기고, 배치는 계속 폴백으로 돈다
     *   <li>{@code document}: 문서일에만 문서를 만들고 <b>나머지 사흘은 아무것도 안 한다</b>.
     *       "매일 문서"가 아닌 이유는 그러면 요금이 네 배가 되기 때문 — 매일 원한다면
     *       주기 길이를 바꿔야 하고, 그건 이 스위치가 다룰 문제가 아니다
     * </ul>
     *
     * <p><b>모르는 값이 오면 auto로 본다.</b> 오타 하나로 배치가 통째로 멈추는 것보다
     * 평소대로 도는 편이 낫다 — 이 프로젝트가 겪은 사고는 "안 도는 걸 몇 주 뒤에 알아차린" 쪽이었다.
     *
     * @param requestedType  수동 실행의 {@code --type}. 비어 있으면 지정 안 한 것(예약 실행이 그렇다)
     * @param configuredType {@code application.yml}의 {@code llm.generation.batch-type}
     * @param documentDay    날짜 주기가 "오늘은 문서일"이라고 했는지
     */
    static BatchAction decideAction(String requestedType, String configuredType, boolean documentDay) {
        // ① 수동 지정 — auto는 "지정 안 함"과 같은 뜻이라 아래로 흘려보낸다(워크플로가 빈 값으로
        //    바꿔 넘기지만, 사람이 직접 CLI를 부를 때를 위해 여기서도 받아 준다)
        if (isSet(requestedType) && !"auto".equalsIgnoreCase(requestedType.trim())) {
            return "document".equalsIgnoreCase(requestedType.trim())
                    ? BatchAction.DOCUMENT : BatchAction.PROBLEM;
        }

        // ② 설정
        if (isSet(configuredType)) {
            String type = configuredType.trim();
            if ("problem".equalsIgnoreCase(type)) {
                return BatchAction.PROBLEM;
            }
            if ("document".equalsIgnoreCase(type)) {
                return documentDay ? BatchAction.DOCUMENT : BatchAction.SKIP;
            }
        }

        // ③ 날짜 주기(auto)
        return documentDay ? BatchAction.DOCUMENT : BatchAction.PROBLEM;
    }

    private static boolean isSet(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * 주기가 계산한 분야와 근거 문서의 분야가 어긋나면 <b>문서 쪽으로 맞춘다</b>(2단계).
     *
     * <p><b>왜 이런 일이 생기나.</b> 문서는 수동 실행으로도 만들 수 있고(주제·날짜를 직접 지정),
     * 주기를 도입하기 전에 만들어진 문서도 있다. 실제로 2026-08-11에 손으로 만든 문서
     * (캐시 전략, SYSTEM_DESIGN)가 OS 주기의 근거로 잡히는 상황이 있었다.
     *
     * <p><b>그냥 두면 무슨 일이 벌어지나.</b> "운영체제 문제를 내라"와 "이 캐시 문서 안에서만
     * 내라"라는 <b>모순된 지시</b>가 한 프롬프트에 함께 실린다. 모델은 오류를 내지 않는다 —
     * 둘 중 하나를 무시하거나 어정쩡하게 섞은 문제를 만들어 낸다. 검수자는 "왜 OS 칸에
     * 캐시 문제가 있지?"를 한참 뒤에야 알아차린다.
     *
     * <p><b>왜 문서가 이기나.</b> 근거 문서가 곧 그 주기의 주제다. 분야는 그 주제를 분류한
     * 이름표일 뿐이라, 이름표를 지키자고 실제 내용과 어긋나게 둘 이유가 없다.
     * (사용자가 {@code --domain}으로 직접 지정한 경우는 호출부에서 이 조정을 건너뛴다.)
     *
     * @param planDomain     주기가 계산한 분야
     * @param documentDomain 근거 문서에 기록된 분야. {@code null}이면 조정하지 않는다
     *                       (옛 형식 파일 방어 — 알 수 없는 값 때문에 멀쩡한 분야를 버리면 안 된다)
     */
    static Domain alignDomainWithDocument(Domain planDomain, Domain documentDomain) {
        return documentDomain != null ? documentDomain : planDomain;
    }

    /* ── 근거 문서 찾기(2단계) ───────────────────────────────── */

    /**
     * 이번 주기의 근거 문서를 읽는다 — 없거나 쓸 수 없으면 {@code null}(폴백).
     *
     * <p><b>클라우드에 DB가 없는데 어떻게 문서를 읽나.</b> 문서는 이미 저장소에 커밋돼 있다
     * ({@code generated/documents/YYYY-MM-DD.json}). Actions는 저장소를 통째로 내려받고 시작하므로
     * <b>파일을 그냥 열면 된다</b>. 날짜만으로 어느 파일인지 정해지니(주기 0일차) 어긋날 일도 없다.
     * 문서 흡수 때 겪은 "클라우드는 DB를 못 본다" 문제가 여기서는 저절로 풀린다.
     *
     * <p><b>거절된 문서는 쓰지 않는다.</b> 검수자가 "이건 아니다"라고 판단한 문서로 사흘간 문제를
     * 만들면 그 사흘이 통째로 낭비다. 거절 여부는 로컬 DB에만 있으므로
     * {@code _existing-documents.json}의 {@code rejectedSlugs}로 실어 보낸다.
     *
     * <p><b>아직 검수 안 한 문서는 그냥 쓴다.</b> 미승인은 부정 신호가 아니라 "아직 안 봤다"일 뿐인데,
     * 승인을 며칠 미뤘다고 그 주기를 날리면 사람의 검수 속도에 배치가 인질로 잡힌다.
     */
    private static ResolvedSource findSourceDocument(Path outDir, GenerationSchedule.Plan plan,
                                                     Difficulty difficulty) {
        if (difficulty == null) {
            return null; // 문서일에는 근거 문서를 찾을 일이 없다(방어)
        }
        Path file = outDir.resolve(DOCUMENT_SUBDIR).resolve(plan.documentDate() + ".json");
        if (!Files.exists(file)) {
            System.out.println("근거 문서 없음, 폴백으로 생성합니다: " + file);
            return null;
        }
        try {
            GeneratedDocumentFile parsed = MAPPER.readValue(file.toFile(), GeneratedDocumentFile.class);
            GeneratedDocumentItem doc = parsed.document();
            if (doc == null || doc.contentMd() == null || doc.contentMd().isBlank()) {
                System.out.println("근거 문서 본문이 비어 폴백으로 생성합니다: " + file);
                return null;
            }
            if (readExistingDocuments(outDir).rejectedSlugs().contains(doc.slug())) {
                System.out.println("근거 문서가 검수에서 거절돼 폴백으로 생성합니다: " + doc.slug());
                return null;
            }
            if (!hasMaterialFor(doc.contentMd(), difficulty)) {
                System.out.printf("근거 문서에 %s 재료가 없어 폴백으로 생성합니다: %s (찾은 절: %s 중 하나도 없음)%n",
                        difficulty, doc.slug(), ClaudeProblemGenerator.SOURCE_SECTIONS.get(difficulty));
                return null;
            }
            return new ResolvedSource(parsed.domain(),
                    new SourceDocument(doc.slug(), doc.title(), doc.contentMd()));
        } catch (Exception e) {
            // 문서를 못 읽는 것이 그날 문제 생성을 막을 이유는 없다 — 근거 없이라도 만든다
            System.out.println("근거 문서를 읽지 못해 폴백으로 생성합니다: " + e.getMessage());
            return null;
        }
    }

    /**
     * 이 문서에 <b>오늘 난이도가 캘 재료가 남아 있는지</b> — 없으면 문서를 쓰지 않는다(폴백).
     *
     * <p><b>왜 필요한가.</b> 지금까지 근거 문서를 거르는 기준은 "파일이 있나 / 본문이 비었나 /
     * 거절됐나" 셋뿐이었다. 셋 다 통과하지만 <b>오늘 쓸 절만 없는</b> 문서가 실제로 나왔다 —
     * 2026-08-15 주기의 문서에 중급이 지목하는 두 절이 둘 다 없었다(절 이름을 문서 생성 뒤에
     * 바꿨기 때문, 커밋 2a5538c). 승인된 문서는 재검증되지 않아 아무도 몰랐다.
     *
     * <p><b>없으면 왜 나쁜가.</b> 프롬프트가 없는 절을 지목하면 모델은 멈추지 않고
     * <b>알아서 다른 절을 캔다</b>. 하필 {@code ## 언제 깨지는가}를 캐면 그날 중급이 고급처럼
     * 나오고, 다음 날 고급은 이미 쓴 재료 앞에서 빈손이 된다(8/14에 겪은 경로).
     * 실패가 아니라 <b>조용한 품질 저하</b>라 며칠 뒤 사람이 눈으로 알아차릴 때까지 간다.
     *
     * <p><b>왜 job을 실패시키지 않고 폴백인가.</b> 문서가 못 쓸 물건인 것은 <b>어제까지의
     * 사고</b>지 오늘 배치의 잘못이 아니다. 여기서 예외를 던지면 그날 문제가 0개가 되는데,
     * 폴백으로 가면 모델 지식으로라도 그날 치는 나온다 — "근거 문서가 없는 날"과 똑같은 상황으로
     * 취급하면 되고, 그 경로는 호출부에 이미 있다(난이도까지 옛 24칸 순환으로 되돌린다).
     * 대신 사유를 또렷이 찍어 로그만 봐도 원인을 알 수 있게 한다.
     *
     * <p><b>왜 "하나라도 있으면 통과"인가(전부가 아니라).</b> 중급 지시는 절 이름 외에
     * "본문 문장 안에서 판단한 대목"까지 재료로 인정한다. 두 절을 모두 요구하면 재료가 멀쩡히
     * 있는 문서까지 폴백으로 버려진다. 여기서 막으려는 것은 "조금 부족한 문서"가 아니라
     * <b>캘 곳이 하나도 없는 문서</b>다 — 문턱을 낮게 두어야 오탐이 안 난다.
     * (오탐으로 폴백이 잦아지면 2단계 구조 자체가 헛돈다.)
     *
     * @param contentMd  문서 본문 마크다운
     * @param difficulty 오늘 만들 난이도. {@code null}이면 검사할 것이 없으므로 통과
     */
    static boolean hasMaterialFor(String contentMd, Difficulty difficulty) {
        if (contentMd == null || difficulty == null) {
            return true; // 판단 근거가 없으면 막지 않는다 — 확신 없이 버리는 쪽이 더 나쁘다
        }
        List<String> sections = ClaudeProblemGenerator.SOURCE_SECTIONS.get(difficulty);
        if (sections == null || sections.isEmpty()) {
            return true; // 지목하는 절이 없는 난이도는 검사 대상이 아니다(방어)
        }
        return sections.stream().anyMatch(contentMd::contains);
    }

    /**
     * 갓 만든 문서를 검증기에 통과시켜 결과를 로그와 요약 화면에 남긴다.
     *
     * <p><b>왜 여기서 또 보는가.</b> {@link DocumentDraftValidator}는 이미 있었지만
     * <b>승인 화면에서만</b> 돌았다. 그런데 문서는 만든 날 바로 승인되지 않는다 — 그 사이
     * 이 문서를 근거로 사흘 치 문제가 만들어진다. 형식이 어긋난 것을 <b>사흘 뒤에</b> 알면
     * 이미 그 주기가 절반 지나간 뒤다. 2026-08-15 문서가 정확히 그 경로로 새어 나갔다.
     *
     * <p><b>왜 job을 실패시키지 않는가.</b> 전부 경고이고, 문서 자체는 쓸 수 있다.
     * 여기서 죽이면 그날 요금을 내고 만든 문서를 버리는 셈인데, 사람이 승인 화면에서
     * 소제목 하나 고치면 되는 일이 대부분이다. 알리는 데까지가 이 자리의 몫이다.
     *
     * <p>차단 항목이 나오면 이야기가 다르다 — 그건 승인 자체가 막힌다는 뜻이라
     * 그 주기가 통째로 헛돌게 되므로 <b>요약 화면에</b> 눈에 띄게 남긴다.
     */
    private static void reportDocumentChecks(GeneratedDocumentItem document, LocalDate date) {
        List<DocumentCheck> checks = DocumentDraftValidator.validate(
                document.title(), document.slug(), document.contentMd());
        if (checks.isEmpty()) {
            System.out.println("문서 검증 통과: 형식 문제 없음");
            return;
        }

        boolean blocking = DocumentDraftValidator.hasBlocking(checks);
        String lines = checks.stream()
                .map(c -> "- %s %s".formatted(c.isBlocking() ? "[차단]" : "[경고]", c.message()))
                .collect(java.util.stream.Collectors.joining("\n"));

        String rendered = "%s **%s 문서 검증: %d건**%s%n%s%n".formatted(
                blocking ? "❌" : "⚠️", date, checks.size(),
                blocking ? " — 차단 항목이 있어 이대로는 승인되지 않습니다" : "",
                lines);

        // 서식은 여기서 끝낸다 — 본문에 '%'가 들어 있으면 다시 포맷할 때 예외로 죽는다
        // (reportYield에서 실제로 겪은 함정).
        System.out.println(rendered);
        appendToStepSummary(rendered);
    }

    /**
     * 찾아낸 근거 문서와 <b>그 문서가 속한 분야</b>.
     *
     * <p>분야를 따로 들고 다니는 이유: 주기가 계산한 분야와 실제 문서의 분야가 어긋날 수 있고,
     * 그때는 문서 쪽으로 맞춰야 한다(위 호출부 주석). {@link SourceDocument}에 분야를 넣지 않은 것은
     * 그 record가 <b>프롬프트에 실릴 내용</b>만 담는 그릇이기 때문이다 — 분야는 프롬프트의
     * 다른 자리(조건 줄)에 이미 들어가므로 문서 블록에 또 넣으면 중복이다.
     */
    private record ResolvedSource(Domain domain, SourceDocument document) {
    }

    /* ── 개념 문서 생성 ───────────────────────────────────────── */

    /**
     * 개념 문서 한 편을 만들어 {@code generated/documents/YYYY-MM-DD.json}으로 저장한다(docs/15).
     *
     * <p><b>문제 생성과 다른 점 세 가지</b>:
     * <ul>
     *   <li><b>난이도가 없다.</b> 문서는 한 편 안에 초급·중급·고급 재료를 모두 담는 것이 목표라
     *       (프롬프트의 [난이도 재료] 절) 날짜 순환의 난이도 축은 쓰지 않는다. 분야만 쓴다.
     *   <li><b>주제를 지정할 수 있다.</b> 우선순위는 <b>수동({@code --topic}) &gt; 주제 대기열
     *       ({@link TopicQueue}) &gt; 모델 자동 선택</b>이다. 대기열은 사람이 미리 적어 둔
     *       세부 주제를 위에서부터 꺼내 쓰는 자리로, 분야만 던져서는 문서가 너무 광범위해지는
     *       문제를 푼다(2026-08-19). 셋 다 없어도 동작한다는 점이 중요하다 — 대기열을 비워 둬도
     *       파이프라인은 예전 그대로 돈다.
     *   <li><b>하위 디렉터리에 저장한다.</b> 문제 파일과 형식이 달라 같은 폴더에 섞이면
     *       기존 흡수 코드가 파싱에 실패한다({@code GeneratedDocumentFile} 주석 참고).
     * </ul>
     */
    private static void generateDocument(Map<String, String> opts, String model,
                                         List<Domain> batchDomains) throws Exception {
        LocalDate date = resolveDate(opts);
        Path outDir = Path.of(opts.getOrDefault("out", DEFAULT_OUT_DIR));
        Path docDir = outDir.resolve(DOCUMENT_SUBDIR);
        Path outFile = docDir.resolve(date + ".json");

        // 문제 생성과 같은 멱등성 보호 — 수동으로 두 번 눌러도 요금이 두 번 나가지 않는다
        if (Files.exists(outFile)) {
            System.out.println("이미 생성됨, 건너뜀: " + outFile);
            return;
        }

        // 분야를 지정하지 않으면 그날의 주기 분야를 쓴다(난이도는 문서에 의미가 없어 버린다)
        Domain domain = documentDomain(date, batchDomains, opts.get("domain"));
        String topic = resolveTopic(opts);

        // 주제 대기열 — 사람이 미리 적어 둔 세부 주제를 위에서부터 꺼내 쓴다(2026-08-19).
        // 수동 지정(--topic)이 있으면 대기열을 건드리지 않는다: 그건 "이번 한 번만 이걸로"라는
        // 뜻이지 줄 서 있는 주제를 소모하겠다는 뜻이 아니다.
        TopicQueue queue = TopicQueue.read(outDir);
        TopicQueue.Picked picked = null;
        if (topic == null) {
            picked = queue.next();
            if (picked != null) {
                topic = picked.topic();
                domain = topicDomain(domain, opts.get("domain"), picked);
            }
        }
        reportTopicQueue(queue, picked, topic);

        ExistingDocuments snapshot = readExistingDocuments(outDir);
        List<String> avoidTitles = snapshot.titles();
        List<String> tags = snapshot.tags();

        System.out.printf("문서 생성 시작: %s / 주제 %s (모델 %s, 기존 문서 %d편, 태그 %d개)%n",
                domain, topic == null ? "자동 선택" : topic, model, avoidTitles.size(), tags.size());

        GeneratedDocumentItem document =
                new ClaudeDocumentGenerator(model).generate(domain, topic, avoidTitles, tags);

        // 빈 응답은 성공이 아니다 — job을 실패시켜 메일을 받는 쪽이 낫다(문제 생성과 같은 판단)
        if (document == null || document.contentMd() == null || document.contentMd().isBlank()) {
            throw new IllegalStateException("모델이 문서 본문을 반환하지 않았습니다 — 프롬프트/모델 설정을 확인하세요.");
        }

        GeneratedDocumentFile file = new GeneratedDocumentFile(
                "GitHub Actions가 자동 생성한 개념 문서 초안입니다. 로컬 앱이 기동할 때 검수 대기함으로 흡수합니다(docs/15). 승인 전까지는 정식 문서가 아닙니다.",
                date.toString(), Instant.now().toString(), domain, model, document);

        Files.createDirectories(docDir);
        Files.writeString(outFile, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(file));
        System.out.printf("저장 완료: %s (\"%s\", 본문 %d자)%n",
                outFile, document.title(), document.contentMd().length());

        // 문제 쪽의 수확량 점검에 해당하는 자리다. 지금까지 문서에는 이런 점검이 없었고,
        // 검증은 <며칠 뒤 승인 화면에서만> 돌았다. 그 사이 이 문서로 사흘 치 문제가 만들어진다.
        reportDocumentChecks(document, date);

        // 사용 표시는 <저장이 끝난 뒤> 찍는다(TopicQueue.markUsed 주석). 여기서 실패해도
        // 문서는 이미 파일에 있으므로 job을 죽이지 않는다 — 대신 다음 주기에 같은 주제가
        // 또 나올 수 있다는 사실을 또렷이 알린다.
        if (picked != null && !queue.markUsed(outDir, picked, date)) {
            System.out.println("⚠️ 주제 대기열에 사용 표시를 못 했습니다 — 다음 문서일에 같은 주제가 또 나올 수 있습니다: "
                    + picked.topic());
            appendToStepSummary("⚠️ `%s`에 사용 표시를 못 했습니다 — \"%s\"가 다음 문서일에 또 나올 수 있습니다.%n"
                    .formatted(TopicQueue.FILE_NAME, picked.topic()));
        }
    }

    /**
     * 대기열에서 꺼낸 주제의 분야를 적용한다 — <b>수동 지정({@code --domain})이 있으면 그것이 이긴다</b>.
     *
     * <p><b>왜 대기열 분야가 주기 분야를 이기나.</b> 대기열에 "@Transactional 전파 속성"을
     * 적어 뒀는데 그날 주기가 운영체제 차례라면, 스프링 문서가 운영체제 칸에 들어간다.
     * 그 어긋남은 문서 한 편으로 끝나지 않는다 — 이어지는 사흘의 문제가 그 문서를 근거로
     * 만들어지므로({@link #alignDomainWithDocument}) 나흘이 통째로 엉킨다.
     * 근거 문서가 주기 분야를 이기는 것과 <b>정확히 같은 이유</b>다: 실제 내용이 이름표를 이긴다.
     *
     * <p><b>왜 수동 지정은 이기지 못하나.</b> 사람이 워크플로에서 분야를 직접 골랐다면 그게
     * 가장 최근의 의사 표시다. 다만 그 조합은 어긋날 수 있으므로 호출부에서 로그로 알린다.
     *
     * @param planned         주기(또는 수동 지정)가 계산해 둔 분야
     * @param requestedDomain 수동 실행의 {@code --domain}. 비어 있으면 대기열 쪽을 쓴다
     * @param picked          대기열에서 꺼낸 항목
     */
    static Domain topicDomain(Domain planned, String requestedDomain, TopicQueue.Picked picked) {
        if (requestedDomain != null && !requestedDomain.isBlank()) {
            if (picked != null && picked.domain() != planned) {
                System.out.printf("수동 지정 분야(%s)와 대기열 주제의 분야(%s)가 다릅니다 — 수동 지정을 따릅니다%n",
                        planned, picked.domain());
            }
            return planned;
        }
        return picked == null ? planned : picked.domain();
    }

    /**
     * 대기열 상태를 로그와 <b>Actions 요약 화면</b>에 남긴다.
     *
     * <p><b>왜 요약 화면까지 쓰나.</b> 이 기능의 실패는 조용하다 — 대기열을 채워 놓고 커밋을
     * 잊었거나 분야 상수명을 잘못 적었으면, 배치는 아무 오류 없이 예전처럼 모델이 고른 주제로
     * 문서를 만든다. 초록불로 끝나므로 <b>대기열이 안 쓰이고 있다는 사실 자체를 모른다</b>.
     * 스냅샷 낡음 경고를 여기에 둔 것과 같은 판단이다(그 함수 주석 참고).
     */
    private static void reportTopicQueue(TopicQueue queue, TopicQueue.Picked picked, String topic) {
        if (picked != null) {
            String message = "📌 주제 대기열에서 꺼냄: **%s** (%s) — 남은 주제 %d개%n"
                    .formatted(picked.topic(), picked.domain(), queue.pendingCount() - 1);
            System.out.print(message);
            appendToStepSummary(message);
        } else if (topic == null) {
            // 수동 지정도 대기열도 없는 평소 경로. 오류가 아니므로 아이콘도 정보(ℹ️)로 둔다 —
            // 매일 경고가 뜨면 사람이 경고 전체를 무시하게 된다.
            String message = ("ℹ️ 주제 대기열이 비어 모델이 주제를 자동으로 고릅니다. "
                    + "`generated/%s`에 주제를 적어 커밋하면 다음 문서일부터 그대로 씁니다.%n")
                    .formatted(TopicQueue.FILE_NAME);
            System.out.print(message);
            appendToStepSummary(message);
        }

        if (!queue.problems().isEmpty()) {
            String message = "⚠️ **주제 대기열에서 건너뛴 항목 %d건**%n%s%n"
                    .formatted(queue.problems().size(), bullets(queue.problems()));
            System.out.print(message);
            appendToStepSummary(message);
        }
    }

    /**
     * 문서를 만들 분야 — 수동 지정이 있으면 그것, 없으면 <b>4일 주기</b>가 정한 분야.
     *
     * <p><b>이 한 줄이 왜 따로 떨어져 나와 있나.</b> 여기서 실제로 버그가 났기 때문이다.
     * 2단계에서 문제 쪽만 {@link GenerationSchedule#planFor}로 옮기고 문서 쪽은 옛
     * {@link GenerationSchedule#cellFor}를 그대로 두었는데, 그 둘이 어긋나는 방식이 하필 최악이었다.
     *
     * <pre>
     *   문서일  = 에포크일 mod 4 == 0        → 4의 배수인 날
     *   cellFor = 분야[에포크일 mod 8]        → 4의 배수를 8로 나눈 나머지는 0 아니면 4
     *   ⇒ 후보 8개 중 <b>0번과 4번만</b> 무한 반복 (NETWORK ↔ SYSTEM_DESIGN)
     * </pre>
     *
     * <p>나머지 여섯 분야는 개념 문서를 <b>영원히 못 받는다.</b> 게다가 문제일에는
     * {@link #alignDomainWithDocument}가 분야를 문서 쪽으로 맞추므로 <b>문제까지 그 두 분야에
     * 갇힌다</b> — 백엔드 8개 분야를 고루 돌자던 설계가 25%만 도는 셈이었다.
     *
     * <p><b>왜 아무도 몰랐나.</b> {@code planFor}에는 테스트가 촘촘했지만 <b>그것을 쓰는 쪽</b>은
     * 아무도 보지 않았다. 규칙이 옳아도 배선이 틀리면 소용이 없다. 게다가 정렬 장치가 매일
     * "분야를 맞췄습니다" 로그를 남기며 결과를 그럴듯하게 만들어 줘서 <b>증상마저 가려졌다</b> —
     * 그 장치는 원래 손으로 만든 옛 문서를 위한 임시 그물이지 매일 발동할 물건이 아니었다.
     *
     * <p>그래서 이 판단을 이름 있는 함수로 꺼내 테스트를 걸었다({@code DraftGeneratorCliTest}).
     * 문제 쪽 배선({@code plan.domain()})과 짝이 맞는지 32일치를 돌려 확인한다.
     *
     * @param date            기준 날짜(한국 기준)
     * @param candidates      후보 분야({@code batch-domains})
     * @param requestedDomain 수동 실행의 {@code --domain}. 비어 있으면 주기에 맡긴다
     */
    static Domain documentDomain(LocalDate date, List<Domain> candidates, String requestedDomain) {
        if (requestedDomain != null && !requestedDomain.isBlank()) {
            return Domain.valueOf(requestedDomain.trim());
        }
        // ⚠️ 반드시 planFor다. cellFor로 바꾸면 위 계산대로 두 분야만 반복된다.
        return GenerationSchedule.planFor(date, candidates).domain();
    }

    /**
     * 기존 문서 제목·태그 스냅샷을 읽는다({@code generated/_existing-documents.json}).
     *
     * <p>중복 회피 목록과 같은 경계 문제다 — 클라우드에는 DB가 없으니 저장소에 커밋된 스냅샷으로
     * 대신한다. 파일이 없어도 진행한다: 첫 실행이거나 아직 내보내지 않았을 수 있고, 이건 정상
     * 상황이지 오류가 아니다(중복이 날 뿐 생성 자체는 된다).
     */
    private static ExistingDocuments readExistingDocuments(Path dir) {
        Path file = dir.resolve(EXISTING_DOCUMENTS_FILE);
        if (!Files.exists(file)) {
            return ExistingDocuments.empty();
        }
        try {
            ExistingDocuments snapshot = MAPPER.readValue(file.toFile(), ExistingDocuments.class);
            return new ExistingDocuments(snapshot.note(), snapshot.exportedAt(),
                    snapshot.titles() == null ? List.of() : snapshot.titles(),
                    snapshot.tags() == null ? List.of() : snapshot.tags(),
                    snapshot.rejectedSlugs() == null ? List.of() : snapshot.rejectedSlugs());
        } catch (Exception e) {
            System.out.println("기존 문서 스냅샷을 읽지 못해 건너뜁니다: " + e.getMessage());
            return ExistingDocuments.empty();
        }
    }

    /**
     * {@code generated/_existing-documents.json}의 형태 — 이 CLI만 읽으므로 여기 둔다.
     *
     * <p>{@code rejectedSlugs}는 2단계에서 추가됐다(docs/15). 검수자가 거절한 문서로 사흘간
     * 문제를 만드는 낭비를 막는 용도다. 옛 파일에는 이 필드가 없으므로 null 방어가 필요하다 —
     * 위 {@code readExistingDocuments}가 전부 빈 목록으로 정규화해 호출부가 신경 쓰지 않게 한다.
     */
    private record ExistingDocuments(String note, String exportedAt, List<String> titles,
                                     List<String> tags, List<String> rejectedSlugs) {

        static ExistingDocuments empty() {
            return new ExistingDocuments(null, null, List.of(), List.of(), List.of());
        }
    }

    /**
     * 문서 주제 — 환경변수 {@code DRAFT_TOPIC}을 우선하고, 없으면 {@code --topic} 인자를 본다.
     *
     * <p><b>왜 환경변수를 먼저 보는가.</b> 주제는 "TCP 혼잡 제어"처럼 공백이 들어간다. Gradle에
     * 인자를 넘기는 통로({@code -PdraftArgs})는 문자열 하나라서 공백으로 쪼개 쓰는데, 그러면
     * 주제가 단어 단위로 찢어진다. 환경변수는 값 하나를 통째로 전달하므로 이 문제가 없고,
     * 사용자 입력을 셸 명령에 끼워 넣지 않아 스크립트 인젝션 위험도 없다.
     * {@code --topic}은 공백 없는 주제를 로컬에서 빠르게 시험할 때를 위해 남겨 둔다.
     */
    private static String resolveTopic(Map<String, String> opts) {
        String fromEnv = System.getenv("DRAFT_TOPIC");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        return opts.get("topic");
    }

    /** 기준 날짜 — 한국 기준. 문제·문서 두 흐름이 같은 규칙을 쓰도록 한 곳에 둔다. */
    private static LocalDate resolveDate(Map<String, String> opts) {
        return opts.containsKey("date")
                ? LocalDate.parse(opts.get("date"))
                : LocalDate.now(ZoneId.of("Asia/Seoul"));
    }

    /* ── 중단 스위치 ─────────────────────────────────────────── */

    /**
     * 생성을 진행할지 판단한다 — {@code batch-enabled}가 꺼져 있어도 {@code force}면 진행.
     *
     * <p><b>왜 force라는 예외 구멍을 두는가.</b> 스위치가 절대적이면, 꺼 둔 상태에서 문제 하나를
     * 급히 만들려 할 때 "설정을 true로 커밋 → 실행 → 다시 false로 커밋"을 해야 한다. 그 과정에서
     * 되돌리기를 잊으면 <b>끈 줄 알았던 배치가 계속 돈다</b> — 스위치를 둔 목적이 무너진다.
     * force는 수동 실행(workflow_dispatch)에서만 켤 수 있고 저장소 설정을 건드리지 않으므로,
     * "한 번만 예외"가 영구 변경으로 새는 일이 없다. 예약 실행은 force를 넘기지 않는다.
     *
     * <p><b>두 줄짜리인데 왜 테스트하는가.</b> 이 판단이 틀리면 증상이 조용하다. {@code &&}를
     * {@code ||}로 잘못 쓰면 "꺼도 계속 도는" 또는 "켜도 안 도는" 상태가 되는데, 후자는 배치가
     * 그냥 매일 조용히 아무것도 안 할 뿐이라 몇 주 뒤에야 알게 된다 — 이 프로젝트가 이미 한 번
     * 겪은 종류의 사고다(docs/14 "왜 옮겼나"). 진리표를 테스트로 못 박아 둔다.
     */
    static boolean shouldGenerate(boolean batchEnabled, boolean force) {
        return batchEnabled || force;
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

    /* ── 거절 사례 되먹이기 ───────────────────────────────────── */

    /**
     * 검수자의 거절 사례 스냅샷을 읽는다({@code generated/_rejection-notes.json}, docs/14).
     *
     * <p>이 파일은 로컬 앱이 내보내고({@code RejectionNotesExporter}) 사용자가 커밋한 것이다.
     * 클라우드에는 DB가 없으니 <b>사람의 검수 판단이 여기까지 오는 유일한 경로</b>다.
     *
     * <p><b>없어도 그냥 진행한다</b>: 거절 이력이 아직 없거나 사용자가 아직 커밋하지 않았을 수 있다.
     * 이건 정상 상황이지 오류가 아니므로, 파일이 없다고 배치를 실패시키면 안 된다
     * (되먹임은 품질 개선 장치이지 생성의 전제 조건이 아니다).
     */
    private static List<RejectionNote> readRejectionNotes(Path dir) {
        Path file = dir.resolve(REJECTION_NOTES_FILE);
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            RejectionNotesFile snapshot = MAPPER.readValue(file.toFile(), RejectionNotesFile.class);
            return snapshot.notes() == null ? List.of() : snapshot.notes();
        } catch (Exception e) {
            // 손으로 고치다 깨졌을 수 있다 — 되먹임을 포기할 뿐 생성 자체는 계속한다
            System.out.println("거절 사례 파일을 읽지 못해 건너뜁니다: " + e.getMessage());
            return List.of();
        }
    }

    /* ── 스냅샷 낡음 경고 ─────────────────────────────────────── */

    /**
     * 스냅샷이 이 일수보다 오래되면 경고한다.
     *
     * <p>왜 14일인가. 스냅샷은 <b>내용이 바뀔 때만</b> 갱신되므로, 며칠 그대로인 것은 정상이다
     * (검수를 안 했으면 거절 사례도 안 늘어난다). 반대로 2주 넘게 그대로면 "바뀔 게 없었다"보다
     * "앱을 켜고도 커밋을 안 했다" 또는 "앱 자체를 안 켰다"일 가능성이 훨씬 높다.
     * 너무 짧게 잡으면 매일 경고가 떠서 <b>사람이 경고를 무시하게 된다</b> — 그게 더 나쁘다.
     */
    static final int SNAPSHOT_STALE_DAYS = 14;

    /**
     * 스냅샷 하나가 낡았는지 판정한다.
     *
     * <p><b>읽을 수 없으면 "낡지 않음"으로 본다.</b> 날짜 형식이 이상하거나 필드가 없는 것은
     * 스냅샷이 오래됐다는 증거가 아니다 — 여기서 낡음으로 처리하면 파일이 깨진 날마다
     * 엉뚱한 경고가 뜨고, 진짜 경고까지 같이 무시당한다. 경고는 <b>확실할 때만</b> 울려야 한다.
     *
     * @param exportedAt 스냅샷의 {@code exportedAt}("2026-08-12" 또는 ISO 시각). null·공백 허용
     * @param today      기준 날짜
     */
    static boolean isStaleSnapshot(String exportedAt, LocalDate today) {
        if (exportedAt == null || exportedAt.isBlank() || exportedAt.length() < 10) {
            return false;
        }
        try {
            LocalDate exported = LocalDate.parse(exportedAt.substring(0, 10));
            return exported.isBefore(today.minusDays(SNAPSHOT_STALE_DAYS));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 커밋되지 않아 낡은 스냅샷이 있으면 로그와 <b>Actions 요약 화면</b>에 경고한다.
     *
     * <p><b>왜 여기(클라우드)에서 알리나.</b> 문제는 "로컬에서 스냅샷을 갱신하고도 커밋하지
     * 않는 것"인데, 앱이 그걸 알려면 앱 안에 git을 심어야 한다 — docs/14에서 <b>일부러 피한
     * 방향</b>이다(권한·인증·충돌이 줄줄이 따라온다). 반면 배치는 저장소에 커밋된 파일을
     * 그대로 읽으므로, <b>파일의 {@code exportedAt}이 곧 마지막으로 커밋된 시점</b>이다.
     * git 없이도 정확히 같은 것을 알 수 있고, 게다가 <b>문제가 실제로 터지는 자리</b>에서 알린다.
     *
     * <p>기동 로그에 한 줄 더 얹는 대안을 버린 이유도 같다 — 애초에 놓치고 있는 로그에
     * 한 줄을 더 보태는 것은 해결이 아니다.
     *
     * <p><b>실패시키지 않는다.</b> 낡은 스냅샷은 중복 문제가 나올 수 있다는 뜻이지 생성이
     * 불가능하다는 뜻이 아니다. 여기서 job을 죽이면 "귀찮아서 껐다"로 끝난다.
     */
    private static void warnIfSnapshotsAreStale(Path outDir, LocalDate today) {
        List<String> stale = new ArrayList<>();
        for (String name : List.of(EXISTING_QUESTIONS_FILE, REJECTION_NOTES_FILE, EXISTING_DOCUMENTS_FILE)) {
            Path file = outDir.resolve(name);
            if (!Files.exists(file)) {
                continue; // 아직 한 번도 안 내보낸 것 — 첫 실행에서는 정상이다
            }
            String exportedAt = readExportedAt(file);
            if (isStaleSnapshot(exportedAt, today)) {
                stale.add("`" + name + "` (" + exportedAt + ")");
            }
        }
        if (stale.isEmpty()) {
            return;
        }

        String message = """
                ⚠️ **스냅샷이 %d일 넘게 그대로입니다** — %s

                %s

                이 파일들은 로컬 앱이 기동할 때 갱신되고, **커밋해야** 다음 배치에 반영됩니다.
                낡은 채로 두면 배치가 옛 목록으로 중복 회피를 하므로 **이미 있는 문제가 또 나올 수 있습니다.**
                → 로컬에서 앱을 한 번 켜고 `git add generated/` 후 커밋하세요.
                """.formatted(SNAPSHOT_STALE_DAYS, today, String.join("\n", stale.stream().map(s -> "- " + s).toList()));

        System.out.println(message);
        appendToStepSummary(message);
    }

    /**
     * 스냅샷 파일에서 {@code exportedAt} 필드만 꺼낸다.
     *
     * <p>파일마다 형태가 다른데({@code questions}/{@code notes}/{@code titles}) 필요한 건 날짜
     * 하나뿐이라, 전용 record로 파싱하는 대신 트리로 읽어 필드 하나만 본다. 이렇게 하면
     * 나중에 스냅샷이 하나 더 늘어도 이 함수는 그대로 쓸 수 있다.
     */
    private static String readExportedAt(Path file) {
        try {
            var node = MAPPER.readTree(file.toFile()).get("exportedAt");
            return node == null ? null : node.asText(null);
        } catch (Exception e) {
            return null; // 못 읽으면 판단하지 않는다(위 isStaleSnapshot과 같은 원칙)
        }
    }

    /* ── 수확량 점검 ──────────────────────────────────────────── */

    /**
     * 저장 직전의 수확 점검 결과.
     *
     * @param requested          요청한 개수
     * @param received           모델이 돌려준 항목 수
     * @param usable             그중 흡수 단계를 통과할 항목 수
     * @param defects            버려질 항목의 사유(사람이 읽는 문장)
     * @param blankExplanations  해설만 빈 항목의 지문 앞부분. 흡수는 통과하므로 {@code usable}에는 포함된다
     */
    /**
     * @param warnings 버리지는 않지만 알려야 할 것 — 해설 분량, 초급 지문 길이, 문서 지칭 등.
     *                 전에는 "해설이 비었다" 하나뿐이라 {@code blankExplanations}였는데,
     *                 프롬프트에 숫자로 적어 둔 규칙들이 지켜지지 않는 것을 실측하고
     *                 ({@code ProblemItemRule}의 품질 경고 절) 같은 통로로 모았다
     */
    record YieldCheck(int requested, int received, int usable,
                      List<String> defects, List<String> warnings) {

        /** 요청한 만큼 쓸 만한 게 왔는지. 모델이 더 많이 주는 경우도 부족은 아니다. */
        boolean isShort() {
            return usable < requested;
        }
    }

    /**
     * 모델이 준 항목을 세어 본다 — <b>걸러내지는 않는다</b>.
     *
     * <p>이 클래스는 모델이 준 것을 있는 그대로 파일에 남긴다(클래스 주석의 "왜 여기서 검증하지
     * 않는가"). 그 원칙은 그대로 두고 <b>세기만</b> 한다. 원본이 남아 있어야 "왜 이 문제가
     * 버려졌지"를 나중에 대조할 수 있고, 그게 프롬프트를 고칠 때의 재료가 된다.
     *
     * <p>판정은 {@link ProblemItemRule}에 맡긴다. 여기서 직접 조건을 적으면 흡수 쪽 규칙과
     * 갈라져 "배치는 5개 다 멀쩡하다는데 검수함에는 2개만 들어온" 상태가 된다.
     */
    static YieldCheck checkYield(List<GeneratedProblemItem> problems, int requested, ProblemType type,
                                 Difficulty difficulty) {
        List<String> defects = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int usable = 0;

        for (int i = 0; i < problems.size(); i++) {
            GeneratedProblemItem item = problems.get(i);
            String defect = ProblemItemRule.defectOf(item, type);
            if (defect != null) {
                defects.add("%d번 — %s: %s".formatted(i + 1, defect, ProblemItemRule.snippet(item)));
                continue;
            }
            usable++;
            // 버릴 것은 아니지만 알려야 할 것. 흡수를 통과하므로 usable을 센 <뒤에> 본다 —
            // 여기서 usable에서 빼면 경고가 말하는 개수와 실제 검수함 개수가 어긋난다.
            for (String warning : ProblemItemRule.qualityWarningsOf(item, difficulty)) {
                warnings.add("%d번 [%s] — %s".formatted(i + 1, warning, ProblemItemRule.snippet(item)));
            }
        }
        return new YieldCheck(requested, problems.size(), usable, defects, warnings);
    }

    /**
     * 수확이 부족하면 로그와 <b>Actions 요약 화면</b>에 알리고, 아예 없으면 job을 실패시킨다.
     *
     * <p><b>왜 요약 화면인가.</b> 지금까지 요약에는 파일 이름만 찍혀서(워크플로의 "요약 남기기"
     * 스텝) "5개 중 2개"를 알 방법이 없었다. 빌드 로그 수백 줄 사이에 묻힌 경고는 없는 것과 같다.
     *
     * <p><b>왜 부족한 정도로는 실패시키지 않나.</b> 2개라도 건지는 편이 낫기 때문이다.
     * 여기서 job을 죽이면 저장·커밋 스텝이 통째로 건너뛰어져 <b>이미 지불한 API 요금이
     * 결과 없이 버려진다</b>(워크플로가 rebase 보험을 둔 것과 같은 이유). 부족한 것은
     * 프롬프트를 손볼 신호이지 그날 치를 버릴 이유가 아니다.
     *
     * <p><b>반대로 0건이면 실패시킨다.</b> 껍데기만 온 파일을 커밋하면 흡수 이력에
     * "0건 저장"으로 남아 <b>다시는 시도되지 않는다</b>(V7 주석의 의도된 동작). 그러면 그날은
     * 조용히 사라진다 — 기존 {@code problems.isEmpty()} 방어가 막으려던 것과 같은 사고이고,
     * 다만 "빈 목록"이 아니라 "빈 껍데기"라는 형태로 그 그물을 빠져나갔을 뿐이다.
     */
    private static void reportYield(YieldCheck yield, LocalDate date) {
        if (yield.usable() == 0) {
            // 요약 화면에도 남긴다 — 실패한 job일수록 원인이 위에 보여야 한다
            appendToStepSummary("""
                    ❌ **%s 생성 실패 — 쓸 수 있는 문제가 하나도 없습니다**

                    모델이 %d개를 돌려줬지만 전부 규약을 어겼습니다.

                    %s
                    """.formatted(date, yield.received(), bullets(yield.defects())));
            throw new IllegalStateException(
                    "모델이 준 %d개가 전부 규약 위반이라 쓸 수 있는 문제가 없습니다: %s"
                            .formatted(yield.received(), String.join(" / ", yield.defects())));
        }

        if (!yield.isShort() && yield.warnings().isEmpty()) {
            System.out.printf("수확 점검 통과: 요청 %d개 → 유효 %d개%n", yield.requested(), yield.usable());
            return;
        }

        StringBuilder message = new StringBuilder();
        if (yield.isShort()) {
            message.append("⚠️ **%s 생성: 요청 %d개 중 %d개만 쓸 수 있습니다**(모델 응답 %d개)%n%n"
                    .formatted(date, yield.requested(), yield.usable(), yield.received()));
            if (!yield.defects().isEmpty()) {
                message.append("버려질 항목:%n%s%n%n".formatted(bullets(yield.defects())));
            }
            // 근거 문서를 다 우려내면 여기로 온다 — 2026-08-14가 그랬다(고급 재료 8개 중 6개를
            // 앞선 이틀이 이미 소진). 사람이 볼 때 원인을 바로 짚을 수 있게 후보를 적어 둔다.
            message.append("""
                    근거 문서에서 낼 수 있는 만큼 다 냈거나, 중복 회피 목록이 너무 빡빡한 상태입니다.
                    같은 문서로 사흘을 나면서 재료가 마르는 것이 알려진 원인입니다
                    → 문서 프롬프트의 고급 재료 요구량 또는 `llm.generation.batch-count`를 확인하세요.
                    """);
        }
        if (!yield.warnings().isEmpty()) {
            // 검수함에는 들어간다는 말을 빼면 안 된다 — 경고를 "버려졌다"로 읽으면
            // 사람이 개수를 세어 보고 혼란스러워한다(경고와 실제 결과가 어긋나 보인다).
            message.append("%n⚠️ **품질 경고 %d건**(검수함에는 들어갑니다)%n%s%n"
                    .formatted(yield.warnings().size(), bullets(yield.warnings())));
        }

        // 여기서 String.format을 한 번 더 돌리면 안 된다 — 지문 앞부분에 '%'가 들어 있으면
        // ("평시 캐시 히트율이 95%인 조회 API…") 그걸 서식 지시자로 읽고 예외로 죽는다.
        // 서식은 위에서 인자로 넘겨 이미 끝냈다.
        String rendered = message.toString();
        System.out.println(rendered);
        appendToStepSummary(rendered);
    }

    /** 사유 목록을 마크다운 불릿으로. 요약 화면과 표준 출력 양쪽에서 읽히는 형태다. */
    private static String bullets(List<String> lines) {
        return String.join(System.lineSeparator(), lines.stream().map(s -> "- " + s).toList());
    }

    /**
     * GitHub Actions 실행 요약 화면에 마크다운을 덧붙인다.
     *
     * <p>{@code GITHUB_STEP_SUMMARY}는 Actions가 각 실행마다 만들어 주는 임시 파일 경로다.
     * 여기에 쓴 내용이 실행 결과 화면 맨 위에 렌더링되므로, <b>로그를 펼치지 않아도 보인다</b> —
     * 경고가 수백 줄 빌드 로그 사이에 묻히면 없는 것과 같다.
     *
     * <p>로컬 실행에는 이 환경변수가 없다. 그때는 조용히 넘어간다(표준 출력에는 이미 찍혔다).
     */
    private static void appendToStepSummary(String markdown) {
        String path = System.getenv("GITHUB_STEP_SUMMARY");
        if (path == null || path.isBlank()) {
            return;
        }
        try {
            Files.writeString(Path.of(path), markdown + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            // 요약 화면에 못 쓰는 것이 생성을 막을 이유는 없다
            System.out.println("실행 요약에 쓰지 못했습니다(무시하고 계속): " + e.getMessage());
        }
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
    static Map<String, Object> readGenerationConfig() throws Exception {
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
    static Map<String, String> parseArgs(String[] args) {
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
