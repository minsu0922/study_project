package project.study.study_project.llm.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.DomainCode;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.llm.client.ClaudeCalls;
import project.study.study_project.llm.client.ClaudeDocumentFactChecker;
import project.study.study_project.llm.client.ClaudeDocumentGenerator;
import project.study.study_project.llm.client.ClaudeProblemGenerator;
import project.study.study_project.llm.client.ClaudeProblemReviewer;
import project.study.study_project.llm.client.DocumentFactChecker;
import project.study.study_project.llm.client.GeneratedDocumentItem;
import project.study.study_project.llm.client.GeneratedProblemItem;
import project.study.study_project.llm.client.RejectionNote;
import project.study.study_project.llm.client.SourceDocument;
import project.study.study_project.llm.dto.GeneratedBatchFile;
import project.study.study_project.llm.dto.GeneratedDocumentFile;
import project.study.study_project.llm.support.DomainSettings;
import project.study.study_project.llm.support.GenerationSchedule;
import project.study.study_project.llm.support.TopicQueue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
 * <p><b>그 원칙의 유일한 예외: 지문이 빈 항목.</b> {@link #dropBlankQuestions}가 저장 직전에
 * 배열에서 뺀다. 원본을 남기는 목적이 "나중에 읽어 볼 재료"인데, 물음이 없는 항목에는
 * 읽을 것이 없다 — 해설과 보기만 남은 껍데기로는 무엇을 물으려 했는지 복원할 수 없으므로
 * 프롬프트를 고칠 재료도 되지 못한다. 사유는 {@link #reportYield}가 이미 번호와 함께
 * 요약 화면에 적어 두므로, 무슨 일이 있었는지는 파일이 아니라 로그에 남는다.
 *
 * <p><b>실패하면 반드시 0이 아닌 종료 코드로 죽는다.</b> 그래야 Actions job이 실패로 표시되고
 * GitHub이 저장소 소유자에게 메일을 보낸다. 기존 {@code LlmGenerationScheduler}는 예외를 삼키고
 * 로그만 남겨서 "조용히 죽는" 것이 문제였는데(그래서 주간 감시 워크플로를 따로 뒀다),
 * 이제는 배치 자신이 매일 울리는 화재경보기가 된다.
 *
 * <p>사용법(Gradle 태스크 {@code generateDrafts}가 감싼다):
 * <pre>
 *   --date=2026-08-12        기준 날짜(생략 시 오늘, 한국 시간). 파일명이자 순환 순번의 근거
 *   --document-date=2026-09-08  근거로 삼을 문서 지목(생략 시 --date의 주기가 결정)
 *                            지목하면 폴백을 막는다 — 못 쓰는 문서면 요금 0으로 실패시킨다
 *   --suffix=xss-beg         결과 파일을 {날짜}-{접미사}.json으로 쓴다(손으로 채울 때).
 *                            주기가 만드는 이름과 겹치지 않아 그날의 예약 실행을 죽이지 않는다
 *   --domain=NETWORK         분야 강제 지정(생략 시 날짜 순환으로 결정)
 *   --topic=슬로우쿼리        문서 주제 강제 지정(문서일에만. 생략 시 주제 대기열 → 모델 자동 선택)
 *                            공백이 든 주제는 환경변수 DRAFT_TOPIC으로 넘긴다
 *   --difficulty=BEGINNER    난이도 강제 지정(생략 시 날짜 순환으로 결정)
 *   --problem-type=OX        문제 유형(생략 시 객관식). MULTIPLE_CHOICE·OX·SHORT_ANSWER·MATCHING·ORDERING
 *                            주의: --type과 다른 옵션이다. 그쪽은 "문제냐 문서냐"를 고른다
 *   --count=5                생성 개수 1~10. 생략하면 난이도별 배분
 *                            (batch-count-by-difficulty, 초7·중5·고3) → batch-count 순
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

    /** 기존 문서 제목·태그 스냅샷. 문서 주제 중복을 피하고 태그 난립을 막는 데 쓴다. */
    static final String EXISTING_DOCUMENTS_FILE = "_existing-documents.json";

    static final ObjectMapper MAPPER = new ObjectMapper();

    private DraftGeneratorCli() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = BatchOptions.parseArgs(args);
        ClaudeCalls.useBatch("true".equalsIgnoreCase(opts.getOrDefault(BatchOptions.BATCH_API_OPT, "false")));
        try {
            run(opts);
        } finally {
            // 흐름마다 끝나는 자리가 달라(쉬는 날, 문서일, 문제일) 한 곳에서 찍는다. 실패한 날에도
            // 이미 낸 요금은 남아야 한다
            BatchReports.reportCostSummary(BatchOptions.resolveDate(opts), ClaudeCalls.ledger());
        }
    }

    static void run(Map<String, String> opts) throws Exception {

        // ── 1. 설정 읽기 ──────────────────────────────────────────
        // application.yml을 직접 읽는 이유: 모델 ID·후보 도메인을 워크플로에 따로 적어 두면
        // 설정을 바꿨을 때 두 곳이 어긋난다. 설정의 단일 출처는 언제나 application.yml이다.
        Map<String, Object> generation = BatchOptions.readGenerationConfig();
        String model = (String) generation.getOrDefault("model", "claude-opus-5");
        int defaultCount = (int) generation.getOrDefault("batch-count", 5);
        // 난이도별 배분. 없으면 null로 두고 resolveCount가 위 폴백을 쓰게 한다 —
        // 여기서 기본 문자열을 끼워 넣으면 설정을 지운 사람이 <지운 대로> 못 돌게 된다.
        String countSpec = (String) generation.get("batch-count-by-difficulty");

        // outDir을 여기서 먼저 정한다 — 원래는 218번째 줄(문제 흐름)에서야 계산됐지만,
        // 분야 설정 파일(_domain-settings.json)이 그 디렉터리 안에 있고 아래에서 만들
        // 후보 분야 목록이 그 파일을 봐야 한다. resolveOutDir 한 곳으로 모아 두어 세 자리
        // (여기·문제 흐름·문서 흐름)가 각자 "generated"를 따로 하드코딩하지 않게 한다.
        Path outDir = BatchOptions.resolveOutDir(opts);

        // 분야 설정 파일 → 관리 화면이 켜 둔 분야를 sortOrder 순으로 후보로 삼는다.
        // 폴백 조건은 resolveBatchDomains 주석에 — 파일이 "없을 때만" yml로 간다.
        // DomainSettings.read는 파일이 깨져도 절대 예외를 던지지 않으므로(클래스 Javadoc)
        // 여기서 try-catch가 필요 없다.
        DomainSettings settings = DomainSettings.read(outDir);
        List<DomainCode> batchDomains = BatchOptions.resolveBatchDomains(settings, (String) generation.get("batch-domains"));
        // 주기의 0일차로 삼을 날. 값이 없으면 에포크 = 앵커가 없던 시절과 같은 위상이다.
        LocalDate cycleAnchor = GenerationSchedule.parseAnchor((String) generation.get("cycle-anchor"));

        // ── 2. 중단 스위치 ────────────────────────────────────────
        // 값이 없으면 켜진 것으로 본다 — 설정 키가 사라졌다고 배치가 멈추면
        // "왜 안 돌지?"를 한참 뒤에 알게 된다(조용한 정지가 조용한 실패보다 낫지 않다).
        boolean batchEnabled = !Boolean.FALSE.equals(generation.get("batch-enabled"));
        boolean force = "true".equalsIgnoreCase(opts.getOrDefault("force", "false"));
        if (!BatchOptions.shouldGenerate(batchEnabled, force)) {
            BatchReports.announce("""
                    ⏸️ **아무것도 만들지 않았습니다 — 배치가 꺼져 있습니다**

                    `llm.generation.batch-enabled=false`. 수동 실행에서 force=true로 한 번만 무시할 수 있습니다.""");
            return; // 종료 코드 0 — "의도된 중단"은 실패가 아니므로 알림 메일이 오면 안 된다
        }

        // ── 3. 오늘 무엇을 만들지 결정 ────────────────────────────
        // 날짜는 한국 기준. 워크플로는 UTC로 도니까 여기서 변환하지 않으면 하루 어긋난다.
        LocalDate date = BatchOptions.resolveDate(opts);
        GenerationSchedule.Plan plan = GenerationSchedule.planFor(date, batchDomains, cycleAnchor);

        // 스냅샷이 낡았는지 여기서 한 번 본다 — 생성 성공/실패와 무관하게 알려야 하므로 맨 앞에 둔다.
        //
        // 기준이 date가 아니라 <실제 오늘>인 이유(2026-08-29): 이 판정이 묻는 것은 "사람이 커밋을
        // 잊은 지 얼마나 됐나"이고, 그건 벽시계로만 잴 수 있다. date를 쓰면 손으로 미래 날짜를
        // 넘겨 한 칸 채울 때 <어제 갱신한 파일도 14일 넘었다>고 잘못 경고한다(실제로 겪음).
        // 예약 실행은 date가 곧 오늘이라 동작이 달라지지 않는다.
        BatchHistory.warnIfSnapshotsAreStale(outDir, LocalDate.now(BatchOptions.KST));

        // 개념 문서는 대상 선정 방식도, 출력 위치(generated/documents/)도 다르다.
        // 한 흐름 안에서 if로 갈라면 두 관심사가 뒤엉키므로 아예 따로 뗀다.
        BatchOptions.BatchAction action = BatchOptions.decideAction(
                opts.get("type"), (String) generation.get("batch-type"), plan.documentDay());
        if (action == BatchOptions.BatchAction.DOCUMENT) {
            // settings(DomainSettings)를 카탈로그 그대로 넘긴다 — hints()만 뽑아 넘기면 분야
            // 이름은 여전히 DefaultDomains 고정값을 쓰게 되어, 관리 화면에서 고친 이름이
            // 배치 프롬프트에는 반영되지 않는다(Task 4, 스펙 6절 버그의 배치 쪽 절반).
            generateDocument(opts, model, settings, batchDomains, cycleAnchor);
            return;
        }
        if (action == BatchOptions.BatchAction.SKIP) {
            BatchReports.announce("""
                    💤 **아무것도 만들지 않았습니다 — 오늘은 쉬는 날입니다**

                    `batch-type=document`인데 %s은 문서일이 아닙니다(4일 주기). 요금 0.""".formatted(date));
            return; // 종료 코드 0 — "의도된 쉬는 날"은 실패가 아니다
        }

        // 수동 실행(workflow_dispatch)에서 특정 칸을 지정한 경우만 주기를 덮어쓴다
        // batchDomains는 이미 파일→yml→DefaultDomains 순으로 넓혀 온 "이번 실행의 전체"다(Task 7) —
        // --domain은 그 안에 있어야 한다. 아니면 모르는 분야로 유료 API를 부르기 전에 여기서 끊는다.
        DomainCode domain = opts.containsKey("domain") ? BatchOptions.knownDomain(opts.get("domain"), batchDomains) : plan.domain();
        Difficulty difficulty = opts.containsKey("difficulty")
                ? Difficulty.valueOf(opts.get("difficulty")) : plan.difficulty();
        ProblemType type = BatchOptions.resolveProblemType(opts.get(BatchOptions.PROBLEM_TYPE_OPT));
        // 난이도가 정해진 <뒤에> 부른다 — 개수가 난이도에 딸려 있으므로 순서가 뒤바뀌면
        // 늘 폴백(5)이 나온다. 컴파일로는 안 걸리는 종류의 실수라 테스트가 따로 지킨다.
        int count = BatchOptions.resolveCount(opts, countSpec, difficulty, defaultCount);

        // outDir은 앞서(설정 읽기 단계) 이미 정했다 — 여기서 다시 계산하지 않는다.
        Path outFile = outDir.resolve(BatchOptions.outFileName(date, opts.get(BatchOptions.SUFFIX_OPT)));

        // ── 4. 멱등성 — 같은 날짜 파일이 이미 있으면 아무것도 하지 않는다 ──
        // 워크플로를 수동으로 두 번 눌러도 API 요금이 두 번 나가지 않게 하는 안전장치.
        //
        // 이 자리가 가장 조용히 해로운 곳이다. 두 번 누른 경우에는 옳은 동작이지만,
        // 사람이 그 날짜를 손으로 채워 둔 경우에도 똑같이 침묵했다 — 그래서 예약 실행이
        // 며칠씩 아무것도 안 하는 것을 아무도 몰랐다. 이제는 요약에 남기고, 되풀이하지 않는
        // 방법(--suffix)까지 함께 알려 준다.
        if (Files.exists(outFile)) {
            BatchReports.announce("""
                    ⏭️ **아무것도 만들지 않았습니다 — 결과 파일이 이미 있습니다**

                    `%s`

                    두 번 실행한 경우라면 정상입니다. 손으로 한 칸을 채우려던 것이라면
                    `--suffix`를 주세요 — 날짜 이름을 쓰면 **그 날짜의 예약 실행이 죽습니다**.""".formatted(outFile));
            return;
        }

        // ── 5. 근거 문서 찾기(2단계) ──────────────────────────────
        // 못 찾으면 null — 그때는 예전처럼 모델의 지식으로 만든다(폴백). 문서 생성이 실패했거나
        // 검수에서 거절된 주기에도 그날의 문제는 나와야 하기 때문.
        LocalDate documentDate = BatchOptions.resolveDocumentDate(opts, plan);
        boolean documentPinned = opts.containsKey(BatchOptions.DOCUMENT_DATE_OPT);

        SourceDocumentFinder.ResolvedSource resolved = SourceDocumentFinder.findSourceDocument(outDir, documentDate, difficulty, type);
        SourceDocument source = resolved == null ? null : resolved.document();

        // 지목한 문서를 못 쓰면 <b>폴백하지 않고 실패</b>시킨다. 폴백은 "예약 실행이 그날을 통째로
        // 날리지 않게" 하려고 둔 장치인데, 사람이 문서를 콕 집어 부른 실행에서는 정반대로 해롭다 —
        // 원하는 문서 대신 <b>근거 없는 문제 5개</b>가 조용히 나오고, 그 사실은 파일을 열어 봐야
        // 안다(documentSlug가 null). 요금까지 나간 뒤에 알게 되는 셈이라 호출 전에 끊는다.
        if (documentPinned && resolved == null) {
            throw new IllegalStateException(
                    "지목한 근거 문서를 쓸 수 없습니다: " + outDir.resolve(BatchOptions.DOCUMENT_SUBDIR).resolve(documentDate + ".json")
                    + " (위에 찍힌 사유 참고). 요금 0으로 중단합니다.");
        }

        if (source == null && !opts.containsKey("difficulty")) {
            // 근거가 없으면 "이번 주기의 난이도"를 쓸 이유도 없다. 주기가 헛도는 동안
            // 같은 분야·난이도만 반복되지 않게 옛 규칙(매일 분야가 바뀌는 24칸 순환)으로 돌아간다.
            GenerationSchedule.Cell fallback = GenerationSchedule.cellFor(date, batchDomains);
            if (!opts.containsKey("domain")) {
                domain = fallback.domain();
            }
            difficulty = fallback.difficulty();
        } else if (resolved != null && !opts.containsKey("domain")) {
            DomainCode aligned = SourceDocumentFinder.alignDomainWithDocument(domain, resolved.domain());
            if (!Objects.equals(aligned, domain)) { // record라 != 는 참조 비교 — 같은 분야도 "다르다"가 된다
                System.out.printf("주기 분야(%s)와 근거 문서 분야(%s)가 달라 문서 쪽으로 맞춥니다%n",
                        domain, aligned);
                domain = aligned;
            }
        }

        // ── 6. 중복 회피 목록 + 거절 사례 되먹이기 ────────────────
        List<String> avoid = BatchHistory.buildAvoidList(outDir, domain);
        List<RejectionNote> rejectionNotes = BatchHistory.readRejectionNotes(outDir);
        System.out.printf("생성 시작: %s × %s, %d문제 (모델 %s, 근거 문서 %s, 중복 회피 %d건, 거절 사례 %d건)%n",
                domain, difficulty, count, model,
                source == null ? "없음(폴백)" : source.slug(), avoid.size(), rejectionNotes.size());

        // ── 7. 실제 호출 ──────────────────────────────────────────
        // settings(DomainSettings)를 카탈로그 그대로 넘긴다 — 분야 이름도 힌트와 같은 자리에서
        // 나와야 "화면에서 분야명을 고쳤는데 배치 프롬프트만 옛 이름"이 되지 않는다(Task 4).
        // displayName()·hints() 둘 다 파일이 비어 있으면 DefaultDomains로 떨어진다(DomainSettings
        // Javadoc) — 관리 화면을 아직 안 썼거나 파일이 깨졌을 때도 2026-09-21 이전과 같은
        // 프롬프트가 나가야 하므로, 여기서 따로 null 방어를 하지 않는다.
        List<GeneratedProblemItem> problems = new ClaudeProblemGenerator(model, settings)
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
        YieldReporter.reportYield(YieldReporter.checkYield(problems, count, type, difficulty, source), date);

        // ── 7-2. 껍데기 걷어내기 ──────────────────────────────────
        // 점검을 <끝낸 뒤에> 뺀다. 순서가 거꾸로면 경고가 "모델 응답 3개"라고 말하게 되어
        // 모델이 실제로 몇 개를 돌려줬는지가 로그에서 사라지고, 결함 줄의 번호(4번·5번)도
        // 원본과 어긋난다 — 프롬프트를 고칠 때 보는 것이 바로 그 두 가지다.
        List<GeneratedProblemItem> kept = YieldReporter.dropBlankQuestions(problems);

        // ── 8. 파일로 저장 ────────────────────────────────────────
        GeneratedBatchFile batch = new GeneratedBatchFile(
                "GitHub Actions가 자동 생성한 문제 초안입니다. 로컬 앱이 기동할 때 검수 대기함으로 흡수합니다(docs/14). 손으로 고쳐도 되지만, 흡수 시 규약 검증을 다시 거칩니다.",
                date.toString(), Instant.now().toString(),
                domain, difficulty, type, model,
                source == null ? null : source.slug(), kept,
                // 걷어내기 <전> 목록에서 뽑는다 — kept에는 이미 껍데기가 없다.
                YieldReporter.shortfallReasons(problems));

        Files.createDirectories(outDir);
        Files.writeString(outFile, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(batch));
        // 성공한 날도 요약에 남긴다. 건너뛴 날만 적으면 "요약이 비었다"가 두 가지 뜻을
        // 갖게 된다 — 잘 돌았거나, 요약 쓰기가 실패했거나. 둘을 구분할 수 있어야 한다.
        //
        // 여기 찍는 수는 <파일에 든 개수>다. 전에는 모델 응답 개수를 찍어서, 바로 위 경고가
        // "3개만 쓸 수 있다"고 적은 날에도 이 줄은 "5건"이라고 했다(2026-09-13). 같은 화면에
        // 두 수가 나란히 있으면 사람은 큰 쪽을 믿는다 — 검수함을 열어 보고서야 어긋남을 안다.
        BatchReports.announce("✅ **%s 문제 초안 %d건** — %s × %s, 근거 문서 %s → `%s`"
                .formatted(date, kept.size(), domain, difficulty,
                        source == null ? "없음(폴백)" : source.slug(), outFile));

        // 문제 검수(docs/22 §3.2~3.4). 객관식이고 근거 문서가 있을 때만 — 근거 대조에 문서가 필요하다
        if (type == ProblemType.MULTIPLE_CHOICE && source != null) {
            BatchReports.reportProblemReview(new ClaudeProblemReviewer(model), kept, difficulty, source, date);
        }
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
    static void generateDocument(Map<String, String> opts, String model, DomainSettings settings,
                                         List<DomainCode> batchDomains, LocalDate cycleAnchor) throws Exception {
        LocalDate date = BatchOptions.resolveDate(opts);
        // main()이 이미 같은 opts로 outDir을 정해 뒀지만, 이 메서드는 main()의 지역 변수를
        // 볼 수 없어 resolveOutDir로 다시 구한다 — opts가 같으므로 값은 항상 같다.
        Path outDir = BatchOptions.resolveOutDir(opts);
        Path docDir = outDir.resolve(BatchOptions.DOCUMENT_SUBDIR);
        // 문서에는 --suffix를 적용하지 않는다(2026-08-29). 근거 문서를 가리키는 유일한 통로가
        // --document-date, 즉 <날짜>인데 접미사가 붙은 문서는 그 이름으로 가리킬 수 없다 —
        // 만들 수는 있지만 아무도 못 쓰는 파일이 된다. 주소 체계를 한 줄로 지킨다:
        // 문서는 날짜로, 문제는 이름으로 부른다.
        Path outFile = docDir.resolve(date + ".json");

        // 문제 생성과 같은 멱등성 보호 — 수동으로 두 번 눌러도 요금이 두 번 나가지 않는다
        if (Files.exists(outFile)) {
            BatchReports.announce("""
                    ⏭️ **아무것도 만들지 않았습니다 — 그 날짜의 개념 문서가 이미 있습니다**

                    `%s`

                    이 주기의 문제일 사흘은 이 문서를 근거로 삼습니다.""".formatted(outFile));
            return;
        }

        // 분야를 지정하지 않으면 그날의 주기 분야를 쓴다(난이도는 문서에 의미가 없어 버린다)
        DomainCode domain = documentDomain(date, batchDomains, opts.get("domain"), cycleAnchor);
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
                // batchDomains를 함께 넘긴다 — 등록부 밖(또는 꺼진) 분야를 대기열에 적어 뒀을 때
                // 경고를 남기는 잣대다(topicDomain Javadoc "등록부에 없는 분야는 알린다").
                domain = topicDomain(domain, opts.get("domain"), picked, batchDomains);
            }
        }
        reportTopicQueue(queue, picked, topic);

        ExistingDocuments snapshot = readExistingDocuments(outDir);
        List<String> avoidTitles = snapshot.titles();
        List<String> tags = snapshot.tags();

        System.out.printf("문서 생성 시작: %s / 주제 %s (모델 %s, 기존 문서 %d편, 태그 %d개)%n",
                domain, topic == null ? "자동 선택" : topic, model, avoidTitles.size(), tags.size());

        ClaudeDocumentGenerator generator = new ClaudeDocumentGenerator(model, settings);
        GeneratedDocumentItem document = generator.generate(domain, topic, avoidTitles, tags);

        // 빈 응답은 성공이 아니다 — job을 실패시켜 메일을 받는 쪽이 낫다(문제 생성과 같은 판단)
        if (document == null || document.contentMd() == null || document.contentMd().isBlank()) {
            throw new IllegalStateException("모델이 문서 본문을 반환하지 않았습니다 — 프롬프트/모델 설정을 확인하세요.");
        }

        // 심화편(2026-09-03). 입문편 전문을 넘겨 "이미 푼 용어는 다시 풀지 마라"를 판정 가능하게 만든다.
        //
        // 실패해도 job을 죽이지 않는 이유: 이 시점에 입문편은 이미 요금을 내고 만들어져 있다.
        // 여기서 예외를 던지면 <파일을 쓰기 전>이라 그 입문편이 통째로 증발하고, 다음 날 초급
        // 문제까지 폴백으로 떨어진다. 심화편만 없으면 고급 날 하루가 입문편 폴백으로 가면 된다
        // (findSourceDocument가 그 경로를 이미 갖고 있다) — 손해가 사흘에서 하루로 줄어든다.
        GeneratedDocumentItem advanced = null;
        try {
            advanced = generator.generateAdvanced(domain, document, tags);
        } catch (RuntimeException e) {
            System.out.println("⚠️ 심화편 생성에 실패했습니다(입문편은 그대로 저장합니다): " + e.getMessage());
        }
        if (advanced != null && (advanced.contentMd() == null || advanced.contentMd().isBlank())) {
            System.out.println("⚠️ 심화편 본문이 비어 버립니다 — 입문편만 저장합니다.");
            advanced = null;
        }

        GeneratedDocumentFile file = new GeneratedDocumentFile(
                "GitHub Actions가 자동 생성한 개념 문서 초안입니다. 로컬 앱이 기동할 때 검수 대기함으로 흡수합니다(docs/15). 승인 전까지는 정식 문서가 아닙니다.",
                date.toString(), Instant.now().toString(), domain, model, document, advanced);

        Files.createDirectories(docDir);
        Files.writeString(outFile, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(file));
        BatchReports.announce("✅ **%s 개념 문서 %d편** — %s → `%s`%n%n> 입문편(%,d자) %s%n%n> 심화편(%s) %s"
                .formatted(date, advanced == null ? 1 : 2, domain, outFile,
                        document.contentMd().length(), document.title(),
                        advanced == null ? "없음" : "%,d자".formatted(advanced.contentMd().length()),
                        advanced == null ? "— 고급 날은 입문편으로 폴백합니다" : advanced.title()));

        // 문제 쪽의 수확량 점검에 해당하는 자리다. 지금까지 문서에는 이런 점검이 없었고,
        // 검증은 <며칠 뒤 승인 화면에서만> 돌았다. 그 사이 이 문서로 사흘 치 문제가 만들어진다.
        BatchReports.reportDraftChecks(document, date);
        if (advanced != null) {
            BatchReports.reportDraftChecks(advanced, date);
        }

        // 사실 검수(docs/22 §3.1). 파일을 쓴 뒤에 도는 이유: 검수가 실패해도 문서는 남아야 한다
        DocumentFactChecker factChecker = new ClaudeDocumentFactChecker(model);
        BatchReports.reportFactCheck(factChecker, document, date);
        if (advanced != null) {
            BatchReports.reportFactCheck(factChecker, advanced, date);
        }

        // 사용 표시는 <저장이 끝난 뒤> 찍는다(TopicQueue.markUsed 주석). 여기서 실패해도
        // 문서는 이미 파일에 있으므로 job을 죽이지 않는다 — 대신 다음 주기에 같은 주제가
        // 또 나올 수 있다는 사실을 또렷이 알린다.
        if (picked != null && !queue.markUsed(outDir, picked, date)) {
            System.out.println("⚠️ 주제 범위에 사용 기록을 못 남겼습니다 — 다음 문서일에 같은 범위가 또 걸릴 수 있습니다: "
                    + picked.topic());
            BatchReports.appendToStepSummary("⚠️ `%s`에 사용 기록을 못 남겼습니다 — \"%s\" 범위가 다음 문서일에 또 걸릴 수 있습니다.%n"
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
     * <h2>등록부에 없는 분야는 알린다(최종 리뷰 Minor 3)</h2>
     *
     * <p>Task 7에서 {@link TopicQueue#parseDomain}이 "형식만" 보도록 바뀌면서, 등록되지 않은
     * 분야를 {@code _topics.json}에 손으로 적어도 그 항목이 그대로 쓰인다. 예전에 뜨던 경고가
     * 그 자리에서 사라진 것이라, 오타 하나가 <b>요금을 쓰고 문서를 만든 뒤</b> 로컬 앱의
     * {@code DocumentImportService}가 흡수를 거절하는 데서야 드러난다. 그래서 {@link #knownDomain}이
     * 수동 지정을 재는 것과 <b>같은 잣대</b>(이번 실행의 전체 = {@code batchDomains})로 대기열
     * 분야도 재서 경고를 되살린다.
     *
     * <p><b>막지 않고 알리기만 한다.</b> {@code knownDomain}은 예외로 실행을 끊지만 여기서는
     * 경고에서 멈춘다 — {@code batchDomains}는 <b>켜진</b> 분야만이라, 등록은 돼 있고 순환에서만
     * 빠진 분야로 주제를 적어 두는 것은 정상적인 사용법이다. 그 경우까지 죽이면 "순환 밖 분야의
     * 문서를 손으로 한 편 만든다"는 길이 막힌다. 판단을 사람에게 넘기되, 조용하지는 않게 한다.
     *
     * @param planned         주기(또는 수동 지정)가 계산해 둔 분야
     * @param requestedDomain 수동 실행의 {@code --domain}. 비어 있으면 대기열 쪽을 쓴다
     * @param picked          대기열에서 꺼낸 항목
     * @param batchDomains    이번 실행의 후보 전체({@link #resolveBatchDomains}) — 경고 판정의 잣대
     */
    static DomainCode topicDomain(DomainCode planned, String requestedDomain, TopicQueue.Picked picked,
                                  List<DomainCode> batchDomains) {
        // 등록부 밖 분야 경고 — 수동 지정이 이기든 대기열이 이기든, 사람이 적어 둔 그 줄은
        // 언젠가 쓰이므로 지금 알린다.
        if (picked != null && batchDomains != null && !batchDomains.contains(picked.domain())) {
            System.out.printf("⚠️ 대기열 주제의 분야(%s)가 이번 실행의 후보에 없습니다 — "
                            + "분야 설정에서 꺼져 있거나 등록되지 않은 코드입니다. 주제: %s%n",
                    picked.domain(), picked.topic());
        }
        if (requestedDomain != null && !requestedDomain.isBlank()) {
            // Objects.equals다. DomainCode는 record라 ==는 참조 비교이고, picked.domain()은
            // TopicQueue가 파일에서 만든 인스턴스라 planned와 같은 물건일 수 없다 — ==면
            // --domain=을 준 모든 실행에서 이 경고가 <늘> 찍혀 진짜 어긋남을 가린다
            // (최종 리뷰 Minor 1). 반환값은 원래 맞았으므로 증상이 로그뿐이었다.
            if (picked != null && !Objects.equals(picked.domain(), planned)) {
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
    static void reportTopicQueue(TopicQueue queue, TopicQueue.Picked picked, String topic) {
        if (picked != null) {
            String message = "📌 오늘의 주제 범위: **%s** (%s) — 등록된 범위 %d개 중 차례%n"
                    .formatted(picked.topic(), picked.domain(), queue.size());
            BatchReports.announce(message);
        } else if (topic == null) {
            // 수동 지정도 범위 목록도 없는 평소 경로. 오류가 아니므로 아이콘도 정보(ℹ️)로 둔다 —
            // 매일 경고가 뜨면 사람이 경고 전체를 무시하게 된다.
            String message = ("ℹ️ 주제 범위 목록이 비어 모델이 주제를 자동으로 고릅니다. "
                    + "관리자 화면에서 범위를 넣고 `generated/%s`를 커밋하면 다음 문서일부터 그 안에서 고릅니다.%n")
                    .formatted(TopicQueue.FILE_NAME);
            BatchReports.announce(message);
        }

        if (!queue.problems().isEmpty()) {
            String message = "⚠️ **주제 범위 목록에서 건너뛴 항목 %d건**%n%s%n"
                    .formatted(queue.problems().size(), BatchReports.bullets(queue.problems()));
            BatchReports.announce(message);
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
     * @param cycleAnchor     주기의 0일차({@code llm.generation.cycle-anchor}). 문제 쪽 배선과
     *                        <b>같은 값</b>이어야 한다 — 어긋나면 문서와 문제의 분야가 갈린다
     */
    static DomainCode documentDomain(LocalDate date, List<DomainCode> candidates, String requestedDomain,
                                 LocalDate cycleAnchor) {
        if (requestedDomain != null && !requestedDomain.isBlank()) {
            // candidates는 이 호출의 "이번 실행의 전체"다(위 knownDomain 주석과 같은 판단) —
            // 수동 지정도 그 밖으로 나가면 안 된다.
            return BatchOptions.knownDomain(requestedDomain.trim(), candidates);
        }
        // ⚠️ 반드시 planFor다. cellFor로 바꾸면 위 계산대로 두 분야만 반복된다.
        return GenerationSchedule.planFor(date, candidates, cycleAnchor).domain();
    }

    /**
     * 기존 문서 제목·태그 스냅샷을 읽는다({@code generated/_existing-documents.json}).
     *
     * <p>중복 회피 목록과 같은 경계 문제다 — 클라우드에는 DB가 없으니 저장소에 커밋된 스냅샷으로
     * 대신한다. 파일이 없어도 진행한다: 첫 실행이거나 아직 내보내지 않았을 수 있고, 이건 정상
     * 상황이지 오류가 아니다(중복이 날 뿐 생성 자체는 된다).
     */
    static ExistingDocuments readExistingDocuments(Path dir) {
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
    record ExistingDocuments(String note, String exportedAt, List<String> titles,
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
    static String resolveTopic(Map<String, String> opts) {
        String fromEnv = System.getenv("DRAFT_TOPIC");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        return opts.get("topic");
    }

}
