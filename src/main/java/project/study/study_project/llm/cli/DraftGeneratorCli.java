package project.study.study_project.llm.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.DomainCode;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.llm.client.ClaudeCalls;
import project.study.study_project.llm.client.ClaudeProblemGenerator;
import project.study.study_project.llm.client.ClaudeProblemReviewer;
import project.study.study_project.llm.client.GeneratedProblemItem;
import project.study.study_project.llm.client.ProblemReview;
import project.study.study_project.llm.client.RejectionNote;
import project.study.study_project.llm.client.SourceDocument;
import project.study.study_project.llm.dto.GeneratedBatchFile;
import project.study.study_project.llm.support.DomainSettings;
import project.study.study_project.llm.support.GenerationSchedule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 * <p><b>그 원칙의 유일한 예외: 지문이 빈 항목.</b> {@link YieldReporter#dropBlankQuestions}가 저장 직전에
 * 배열에서 뺀다. 원본을 남기는 목적이 "나중에 읽어 볼 재료"인데, 물음이 없는 항목에는
 * 읽을 것이 없다 — 해설과 보기만 남은 껍데기로는 무엇을 물으려 했는지 복원할 수 없으므로
 * 프롬프트를 고칠 재료도 되지 못한다. 사유는 {@link YieldReporter#reportYield}가 이미 번호와 함께
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

    static final ObjectMapper MAPPER = new ObjectMapper();

    private DraftGeneratorCli() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = BatchOptions.parseArgs(args);
        // 기본이 Batch API다(2026-10-10). 손으로 채울 때 옵션을 빠뜨려 정가를 낸 날이 있었다 —
        // 급해서 바로 받아야 할 때만 --batch-api=false를 준다.
        ClaudeCalls.useBatch(!"false".equalsIgnoreCase(opts.getOrDefault(BatchOptions.BATCH_API_OPT, "true")));
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
            DocumentBatch.generateDocument(opts, model, settings, batchDomains, cycleAnchor);
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
                YieldReporter.shortfallReasons(problems),
                // 검수는 저장 뒤에 돈다 — 아래 attachReviewFindings가 채운다
                null);

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
            List<ProblemReview.Finding> findings = BatchReports.reportProblemReview(
                    new ClaudeProblemReviewer(model), kept, difficulty, source, date).findings();
            if (findings != null) {
                attachReviewFindings(outFile, batch, findings);
            }
        }
    }

    /**
     * 검수 지적을 넣어 결과 파일을 다시 쓴다 — 검수함이 지적을 보여 주려면 파일에 실려야 한다(docs/22 §6).
     *
     * <p>실패해도 예외를 던지지 않는다. 지적 없는 파일이 이미 저장돼 있고, 여기서 죽으면
     * 커밋 스텝이 돌지 않아 요금을 낸 문제까지 버려진다. 임시 파일에 쓰고 바꿔치는 이유도 같다 —
     * 쓰다 끊기면 멀쩡하던 파일이 반쪽이 된다.
     */
    static void attachReviewFindings(Path outFile, GeneratedBatchFile batch, List<ProblemReview.Finding> findings) {
        Path temp = outFile.resolveSibling(outFile.getFileName() + ".tmp");
        try {
            Files.writeString(temp, MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(batch.withReviewFindings(findings)));
            Files.move(temp, outFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            BatchReports.announce("⚠️ 검수 지적을 파일에 싣지 못했습니다 — 문제는 저장했고, 지적은 위 요약에만 있습니다 (%s)"
                    .formatted(e.getMessage()));
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // 임시 파일이 남으면 커밋에 딸려 가지만 흡수 대상(*.json)이 아니라 해가 없다
            }
        }
    }

}
