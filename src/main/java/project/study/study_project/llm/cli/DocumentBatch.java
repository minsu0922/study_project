package project.study.study_project.llm.cli;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import project.study.study_project.global.common.DomainCode;
import project.study.study_project.llm.support.FileFormats;
import project.study.study_project.llm.client.ClaudeDocumentFactChecker;
import project.study.study_project.llm.client.ClaudeDocumentGenerator;
import project.study.study_project.llm.client.ClaudeDocumentPolisher;
import project.study.study_project.llm.client.ClaudeDocumentReaderCheck;
import project.study.study_project.llm.client.DocumentEdition;
import project.study.study_project.llm.client.DocumentFactChecker;
import project.study.study_project.llm.client.DocumentReaderCheck;
import project.study.study_project.llm.client.ReaderFinding;
import project.study.study_project.llm.dto.ReaderFindings;
import project.study.study_project.llm.client.FactCheckFinding;
import project.study.study_project.llm.client.GeneratedDocumentItem;
import project.study.study_project.llm.dto.GeneratedDocumentFile;
import project.study.study_project.llm.support.DomainSettings;
import project.study.study_project.llm.support.GenerationSchedule;
import project.study.study_project.llm.support.TopicBrief;
import project.study.study_project.llm.support.TopicQueue;

import com.fasterxml.jackson.databind.ObjectMapper;
import project.study.study_project.llm.support.JsonMappers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 문서일 흐름. 주제를 고르고 개념 문서 두 편(입문·심화)을 만들어 검사·검수한다.
 *
 * <p>{@link DraftGeneratorCli}에서 역할별로 떼어 냈다(2026-09-29). 동작은 그대로다.
 */
final class DocumentBatch {

    /** 기존 DraftGeneratorCli와 같은 설정이라 쓰는 JSON 파일 형식도 같다. */
    private static final ObjectMapper MAPPER = JsonMappers.STRICT;

    private DocumentBatch() {
    }

    /** 기존 문서 제목·태그 스냅샷. 문서 주제 중복을 피하고 태그 난립을 막는 데 쓴다. */
    static final String EXISTING_DOCUMENTS_FILE = "_existing-documents.json";

    /* ── 개념 문서 생성 ───────────────────────────────────────── */

    /**
     * 개념 문서 한 편을 만들어 {@code generated/documents/YYYY-MM-DD.json}으로 저장한다(docs/15).
     *
     * <p><b>문제 생성과 다른 점 세 가지</b>:
     * <ul>
     *   <li><b>난이도가 없다.</b> 문서는 한 편 안에 초급·중급·고급 재료를 모두 담는 것이 목표라
     *       (프롬프트의 [난이도 재료] 절) 날짜 순환의 난이도 축은 쓰지 않는다. 분야만 쓴다.
     *   <li><b>주제를 지정할 수 있다.</b> 우선순위는 <b>수동({@code --topic}) &gt; 주제 대기열
     *       ({@link TopicQueue})</b>이다. 대기열은 사람이 미리 적어 둔 세부 주제와 「글의 주제」를
     *       꺼내 쓰는 자리다. 둘 다 없으면 그날 문서를 만들지 않는다(2026-11-24, 모델 자동 선택 폐지).
     *   <li><b>하위 디렉터리에 저장한다.</b> 문제 파일과 형식이 달라 같은 폴더에 섞이면
     *       기존 흡수 코드가 파싱에 실패한다({@code GeneratedDocumentFile} 주석 참고).
     * </ul>
     */
    static void generateDocument(Map<String, String> opts, String model, DomainSettings settings,
                                         List<DomainCode> batchDomains, LocalDate cycleAnchor,
                                         boolean polishEnabled, boolean readerCheckEnabled) throws Exception {
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
        TopicBrief brief = null;
        if (topic == null) {
            picked = queue.next();
            if (picked != null) {
                topic = picked.topic();
                // batchDomains를 함께 넘긴다 — 등록부 밖(또는 꺼진) 분야를 대기열에 적어 뒀을 때
                // 경고를 남기는 잣대다(topicDomain Javadoc "등록부에 없는 분야는 알린다").
                domain = topicDomain(domain, opts.get("domain"), picked, batchDomains);
                brief = TopicBrief.parse(picked.memo());
                if (brief != null) {
                    brief = brief.withLaterTopics(queue.laterTopicsInBlock(picked));
                }
                brief = TopicBrief.withTopicBoxes(brief, picked.beginnerTopicBox(), picked.advancedTopicBox());
            } else if (queue.boxlessCount() > 0) {
                // 글의 주제는 사람이 쓴다(V48). 쓴 주제가 없을 때 모델 자동 선택으로 흘리면
                // 사람이 고르지도, 쓰지도 않은 문서가 나온다 — 그날은 만들지 않는다
                BatchReports.announce("""
                        ⏭️ **아무것도 만들지 않았습니다 — 글의 주제를 쓴 주제가 없습니다**

                        대기열의 개념 주제 %d개가 입문편·심화편 「글의 주제」를 아직 다 쓰지 않았습니다.
                        관리 콘솔 → 주제 상세 → 「글의 주제 쓰기」에서 두 편을 쓰고 `%s`을 커밋하세요."""
                        .formatted(queue.boxlessCount(), TopicQueue.FILE_NAME));
                return;
            } else {
                // 대기열이 비는 일은 평소 없다(다 쓴 주제도 다시 돈다). 비었다면 파일이 없거나 깨진 것이라,
                // 모델 자동 선택으로 흘리지 않고 그날은 만들지 않는다 — 글의 주제 없는 문서는 승인도 막힌다
                BatchReports.announce("""
                        ⏭️ **아무것도 만들지 않았습니다 — 주제 대기열이 비었습니다**

                        `%s`에 개념 주제가 없거나 파일을 읽지 못했습니다. 관리 콘솔 → 주제 대기열을 확인하세요."""
                        .formatted(TopicQueue.FILE_NAME));
                return;
            }
        }
        reportTopicQueue(queue, picked);
        if (picked != null && queue.boxlessCount() > 0) {
            System.out.printf("글의 주제를 아직 안 써서 건너뛴 주제: %d개%n", queue.boxlessCount());
        }
        if (picked != null) {
            // 형식이 안 맞는 메모는 조용히 빠진다. 그대로 두면 지시가 실렸는지 아무도 모른다
            System.out.println(brief != null && brief.hasMemoFields()
                    ? "작성 지시: 메모를 실었습니다(주제를 그대로 씁니다)."
                    : "작성 지시: 없음 — 메모에 '핵심 개념:'이 없습니다(글의 주제만 싣습니다).");
        }

        ExistingDocuments snapshot = readExistingDocuments(outDir);
        List<String> avoidTitles = snapshot.titles();
        List<String> tags = snapshot.tags();

        System.out.printf("문서 생성 시작: %s / 주제 %s (모델 %s, 기존 문서 %d편, 태그 %d개)%n",
                domain, topic, model, avoidTitles.size(), tags.size());

        ClaudeDocumentGenerator generator = new ClaudeDocumentGenerator(model, settings);
        GeneratedDocumentItem document = generator.generate(domain, topic, brief, avoidTitles, tags);

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
            advanced = generator.generateAdvanced(domain, document, brief, tags);
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
        Files.writeString(outFile, FileFormats.toJson(MAPPER, FileFormats.Kind.DOCUMENTS, file));
        BatchReports.announce("✅ **%s 개념 문서 %d편** — %s → `%s`%n%n> 입문편(%,d자) %s%n%n> 심화편(%s) %s"
                .formatted(date, advanced == null ? 1 : 2, domain, outFile,
                        document.contentMd().length(), document.title(),
                        advanced == null ? "없음" : "%,d자".formatted(advanced.contentMd().length()),
                        advanced == null ? "— 고급 날은 입문편으로 폴백합니다" : advanced.title()));

        // 윤문(설계 2026-11-18). 원본을 먼저 저장해 두었으므로 여기서 job이 죽어도 생성 요금은 남는다
        if (polishEnabled) {
            BothEditions.Pair<PolishOutcome> outcomes = PolishStep.polishBoth(
                    new ClaudeDocumentPolisher(model), document, advanced, brief);
            BatchReports.announce(PolishStep.summaryLine("입문편", outcomes.beginner()));
            if (outcomes.advanced() != null) {
                BatchReports.announce(PolishStep.summaryLine("심화편", outcomes.advanced()));
            }
            file = applyPolish(outFile, file, outcomes);
            document = file.document();
            advanced = file.advancedDocument();
        }

        // 문제 쪽의 수확량 점검에 해당하는 자리다. 윤문 뒤에 두어 실제로 나가는 본문을 검사한다.
        // 검증이 <며칠 뒤 승인 화면에서만> 돌면 그 사이 이 문서로 사흘 치 문제가 만들어진다.
        BatchReports.reportDraftChecks(document, date, brief == null ? null : brief.beginnerTopicBox());
        if (advanced != null) {
            BatchReports.reportDraftChecks(advanced, date, brief == null ? null : brief.advancedTopicBox());
        }

        // 사실 검수(docs/22 §3.1)와 독자 점검(설계 2026-11-20). 파일을 쓴 뒤에 도는 이유: 검수가 실패해도 문서는 남아야 한다
        checkAndAttach(outFile, file, date, new ClaudeDocumentFactChecker(model),
                readerCheckEnabled ? new ClaudeDocumentReaderCheck(model) : null,
                queue.earlierUsedTopicsInBlock(picked));

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
     * 사실 검수와 독자 점검을 동시에 돌려 결과를 문서 파일에 한 번에 싣는다. 단계마다 두 편도 동시에 보낸다 —
     * 배치 호출이 편마다 최대 45분이라 차례로 보내면 job 제한(300분)에 가까워진다.
     * 독자 점검이 한 편에서 실패하면 그 편 칸만 {@code null}로 두고 나머지는 싣는다.
     *
     * @param readerCheck 독자 점검이 꺼져 있으면 {@code null}. 그때는 파일에 독자 점검 칸을 만들지 않는다
     * @return 실제로 파일에 쓴 내용(쓰기에 실패했으면 받은 그대로)
     */
    static GeneratedDocumentFile checkAndAttach(Path outFile, GeneratedDocumentFile file, LocalDate date,
                                                DocumentFactChecker factChecker, DocumentReaderCheck readerCheck,
                                                List<String> earlierTopics) {
        GeneratedDocumentItem beginner = file.document();
        GeneratedDocumentItem advanced = file.advancedDocument();
        ParallelSteps.Pair<BothEditions.Pair<List<FactCheckFinding>>, BothEditions.Pair<List<ReaderFinding>>> both =
                ParallelSteps.run(
                        () -> BothEditions.run(
                                () -> BatchReports.checkFacts(factChecker, beginner, date).findings(),
                                advanced == null ? null : () -> BatchReports.checkFacts(factChecker, advanced, date).findings()),
                        () -> readerCheck == null ? null : BothEditions.run(
                                () -> BatchReports.checkReader(readerCheck, beginner, DocumentEdition.BEGINNER,
                                        earlierTopics, null, date),
                                advanced == null ? null : () -> BatchReports.checkReader(readerCheck, advanced,
                                        DocumentEdition.ADVANCED, earlierTopics, beginner.contentMd(), date)));
        GeneratedDocumentFile target = file;
        List<FactCheckFinding> facts = mergeFactFindings(both.first().beginner(), both.first().advanced());
        if (facts != null) {
            target = target.withFactFindings(facts);
        }
        BothEditions.Pair<List<ReaderFinding>> readers = both.second();
        if (readers != null && (readers.beginner() != null || readers.advanced() != null)) {
            target = target.withReaderFindings(new ReaderFindings(readers.beginner(), readers.advanced()));
        }
        if (target == file) {
            return file;
        }
        String error = replaceFile(outFile, target);
        if (error != null) {
            BatchReports.announce("⚠️ 검수 결과를 파일에 싣지 못했습니다 — 문서는 저장했고, 지적은 위 요약에만 있습니다 (%s)"
                    .formatted(error));
            return file;
        }
        return target;
    }

    /** 두 편의 지적을 한 목록으로 합친다. 두 편 다 검수하지 못했으면 {@code null}이다. */
    static List<FactCheckFinding> mergeFactFindings(List<FactCheckFinding> beginner, List<FactCheckFinding> advanced) {
        if (beginner == null && advanced == null) {
            return null;
        }
        List<FactCheckFinding> merged = new ArrayList<>();
        if (beginner != null) {
            merged.addAll(beginner);
        }
        if (advanced != null) {
            merged.addAll(advanced);
        }
        return merged;
    }

    /**
     * 사실 검수 지적을 넣어 문서 파일을 다시 쓴다 — 문제를 만드는 날 근거 문장과 대조하려면
     * 파일에 실려야 한다(docs/22 §6). 실패해도 예외를 던지지 않는 이유와 임시 파일을 쓰는 이유는
     * {@code DraftGeneratorCli.attachReviewFindings}와 같다.
     */
    static void attachFactFindings(Path outFile, GeneratedDocumentFile file, List<FactCheckFinding> findings) {
        String error = replaceFile(outFile, file.withFactFindings(findings));
        if (error != null) {
            BatchReports.announce("⚠️ 사실 검수 지적을 파일에 싣지 못했습니다 — 문서는 저장했고, 지적은 위 요약에만 있습니다 (%s)"
                    .formatted(error));
        }
    }

    /**
     * 채택된 윤문본으로 문서 파일을 바꿔 쓴다. 원본 칸에는 채택된 편의 원고만 넣는다.
     * 둘 다 원본 유지면 쓰지 않는다. 쓰기에 실패하면 알리고 원고 파일을 그대로 쓴다 — 문제 배치가 그 파일을 읽는다.
     *
     * @return 이후 단계가 이어 쓸 파일 내용(사실 검수 지적이 이 위에 붙는다)
     */
    static GeneratedDocumentFile applyPolish(Path outFile, GeneratedDocumentFile file,
                                             BothEditions.Pair<PolishOutcome> outcomes) {
        PolishOutcome b = outcomes.beginner();
        PolishOutcome a = outcomes.advanced();
        boolean beginnerApplied = b != null && b.applied();
        boolean advancedApplied = a != null && a.applied();
        if (!beginnerApplied && !advancedApplied) {
            return file;
        }
        GeneratedDocumentFile polished = file.withPolished(
                beginnerApplied ? b.chosen() : file.document(),
                advancedApplied ? a.chosen() : file.advancedDocument(),
                beginnerApplied ? file.document() : null,
                advancedApplied ? file.advancedDocument() : null);
        String error = replaceFile(outFile, polished);
        if (error != null) {
            BatchReports.announce("⚠️ 윤문본을 파일에 싣지 못해 원고를 그대로 씁니다 (%s)".formatted(error));
            return file;
        }
        return polished;
    }

    /**
     * 임시 파일에 쓴 뒤 바꿔 끼운다. 쓰다가 죽어도 원래 파일이 반쪽으로 남지 않는다.
     *
     * @return 실패하면 사유, 성공하면 {@code null}
     */
    private static String replaceFile(Path outFile, GeneratedDocumentFile file) {
        Path temp = outFile.resolveSibling(outFile.getFileName() + ".tmp");
        try {
            Files.writeString(temp, FileFormats.toJson(MAPPER, FileFormats.Kind.DOCUMENTS, file));
            Files.move(temp, outFile, StandardCopyOption.REPLACE_EXISTING);
            return null;
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // 임시 파일이 남아도 들여오기 대상(*.json)이 아니라 해가 없다
            }
            return e.getMessage();
        }
    }

    /**
     * 대기열에서 꺼낸 주제의 분야를 적용한다 — <b>수동 지정({@code --domain})이 있으면 그것이 이긴다</b>.
     *
     * <p><b>왜 대기열 분야가 주기 분야를 이기나.</b> 대기열에 "@Transactional 전파 속성"을
     * 적어 뒀는데 그날 주기가 운영체제 차례라면, 스프링 문서가 운영체제 칸에 들어간다.
     * 그 어긋남은 문서 한 편으로 끝나지 않는다 — 이어지는 사흘의 문제가 그 문서를 근거로
     * 만들어지므로({@link SourceDocumentFinder#alignDomainWithDocument}) 나흘이 통째로 엉킨다.
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
     * {@code DocumentImportService}가 흡수를 거절하는 데서야 드러난다. 그래서 {@link BatchOptions#knownDomain}이
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
     * @param batchDomains    이번 실행의 후보 전체({@link BatchOptions#resolveBatchDomains}) — 경고 판정의 잣대
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
     * <p><b>왜 요약 화면까지 쓰나.</b> 분야 상수명을 잘못 적은 항목은 오류 없이 건너뛰어진다.
     * 초록불로 끝나므로 <b>그 주제가 안 쓰이고 있다는 사실 자체를 모른다</b>.
     * 스냅샷 낡음 경고를 여기에 둔 것과 같은 판단이다(그 함수 주석 참고).
     */
    static void reportTopicQueue(TopicQueue queue, TopicQueue.Picked picked) {
        if (picked != null) {
            String message = "📌 오늘의 주제 범위: **%s** (%s) — 등록된 범위 %d개 중 차례%n"
                    .formatted(picked.topic(), picked.domain(), queue.size());
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
     * {@link SourceDocumentFinder#alignDomainWithDocument}가 분야를 문서 쪽으로 맞추므로 <b>문제까지 그 두 분야에
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
            ExistingDocuments snapshot = FileFormats.read(MAPPER, file, FileFormats.Kind.EXISTING_DOCUMENTS, ExistingDocuments.class);
            return new ExistingDocuments(snapshot.note(), snapshot.exportedAt(),
                    snapshot.titles() == null ? List.of() : snapshot.titles(),
                    snapshot.tags() == null ? List.of() : snapshot.tags(),
                    snapshot.rejectedSlugs() == null ? List.of() : snapshot.rejectedSlugs());
        } catch (Exception e) {
            System.out.println("기존 문서 스냅샷을 읽지 못해 건너뜁니다: " + e.getMessage());
            if (e instanceof FileFormats.NewerFormatException) {
                BatchReports.announce("⚠️ " + e.getMessage());
            }
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
    @JsonIgnoreProperties({FileFormats.FIELD}) // 형식 버전은 FileFormats가 확인한다. 직접 읽는 곳이 이 칸 때문에 실패하지 않게 한다
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
