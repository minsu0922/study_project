package project.study.study_project.llm.cli;

import project.study.study_project.global.common.Difficulty;
import project.study.study_project.llm.client.ClaudeCalls;
import project.study.study_project.llm.client.ClaudeDocumentGenerator;
import project.study.study_project.llm.client.DocumentFactChecker;
import project.study.study_project.llm.client.ProblemReview;
import project.study.study_project.llm.client.ProblemReviewer;
import project.study.study_project.llm.client.FactCheckFinding;
import project.study.study_project.llm.client.GeneratedDocumentItem;
import project.study.study_project.llm.client.GeneratedProblemItem;
import project.study.study_project.llm.client.SourceDocument;
import project.study.study_project.llm.support.DraftCheck;
import project.study.study_project.llm.support.DocumentDraftValidator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 배치 실행 결과를 표준 출력과 Actions 요약 화면에 남긴다. 검수 보고와 비용 요약도 여기 있다.
 *
 * <p>{@link DraftGeneratorCli}에서 역할별로 떼어 냈다(2026-09-29). 동작은 그대로다.
 */
final class BatchReports {

    private BatchReports() {
    }

    /**
     * 이번 실행의 호출별 토큰과 추정 금액을 이름별로 묶어 요약에 남긴다(docs/23).
     * 2주 점검(docs/22 §6)과 Batch API 효과 확인에 추정이 아닌 실제 값이 필요하다.
     *
     * @return 요약 화면에 쓴 글(테스트용). 호출이 없으면 빈 문자열
     */
    static String reportCostSummary(LocalDate date, List<ClaudeCalls.Usage> ledger) {
        if (ledger.isEmpty()) {
            return "";
        }
        Map<String, List<ClaudeCalls.Usage>> byLabel = new java.util.LinkedHashMap<>();
        ledger.forEach(u -> byLabel.computeIfAbsent(u.label(), k -> new ArrayList<>()).add(u));
        StringBuilder lines = new StringBuilder();
        double total = 0;
        for (var entry : byLabel.entrySet()) {
            long in = 0, out = 0, batched = 0;
            double usd = 0;
            for (ClaudeCalls.Usage u : entry.getValue()) {
                in += u.inputTokens();
                out += u.outputTokens();
                batched += u.batch() ? 1 : 0;
                usd += ReviewEvalCli.usd(u.model(), u.inputTokens(), u.outputTokens(), u.webSearches(), u.batch());
            }
            total += usd;
            lines.append("| %s | %d (배치 %d) | %,d | %,d | %s |%n".formatted(entry.getKey(),
                    entry.getValue().size(), batched, in, out, Double.isNaN(usd) ? "?" : "$%.2f".formatted(usd)));
        }
        String rendered = ("💰 **%s API 비용 약 %s** — 사고 토큰은 출력에 포함. 배치 호출은 반값으로 계산%n%n"
                + "| 호출 | 횟수 | 입력 토큰 | 출력 토큰 | 금액 |%n|---|---|---|---|---|%n")
                .formatted(date, Double.isNaN(total) ? "?" : "$%.2f".formatted(total)) + lines;
        System.out.println(rendered);
        appendToStepSummary(rendered);
        return rendered;
    }

    /**
     * 문제 검수를 돌려 지적을 요약 화면에 남긴다. 실패해도 job을 죽이지 않는다.
     * 파일은 이미 저장됐고, 검수는 덧붙이는 단계다.
     *
     * @return 요약 화면에 쓴 글(테스트용)
     */
    static String reportProblemReview(ProblemReviewer reviewer, List<GeneratedProblemItem> problems,
                                      Difficulty difficulty, SourceDocument source, LocalDate date) {
        String rendered;
        try {
            rendered = renderProblemReview(date, problems, reviewer.review(problems, difficulty, source));
        } catch (RuntimeException e) {
            rendered = "⚠️ **%s 문제 검수 실패** — 문제는 저장했습니다. 승인 전에 사람이 풀어 보세요 (%s)%n"
                    .formatted(date, e.getMessage());
        }
        System.out.println(rendered);
        appendToStepSummary(rendered);
        return rendered;
    }

    static String renderProblemReview(LocalDate date, List<GeneratedProblemItem> problems,
                                      List<ProblemReview.Finding> findings) {
        if (findings.isEmpty()) {
            return "🔎 %s 문제 검수: 지적 없음%n".formatted(date);
        }
        String lines = findings.stream()
                .map(f -> {
                    GeneratedProblemItem p = problems.get(f.problemIndex());
                    String name = p.title() == null || p.title().isBlank() ? p.question() : p.title();
                    return "- %d번 「%s」 [%s] %s".formatted(f.problemIndex() + 1,
                            name.length() > 40 ? name.substring(0, 40) + "…" : name, f.type().label(), f.message());
                })
                .collect(java.util.stream.Collectors.joining("\n"));
        return "🔎 **%s 문제 검수: %d건** — 승인 전에 확인하세요. AI 지적이라 틀릴 수 있습니다%n%s%n"
                .formatted(date, findings.size(), lines);
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
    static void reportDraftChecks(GeneratedDocumentItem document, LocalDate date) {
        List<DraftCheck> checks = DocumentDraftValidator.validate(
                document.title(), document.slug(), document.contentMd());
        // 하루에 두 편을 만들게 되면서(2026-09-03) 어느 편의 결과인지 밝히지 않으면
        // 요약 화면에 같은 모양의 블록이 둘 나란히 서서 구별되지 않는다.
        String edition = ClaudeDocumentGenerator.editionOf(document.contentMd()).getDisplayName();
        if (checks.isEmpty()) {
            System.out.println(edition + " 검증 통과: 형식 문제 없음");
            return;
        }

        boolean blocking = DocumentDraftValidator.hasBlocking(checks);
        String lines = checks.stream()
                .map(c -> "- %s %s".formatted(c.isBlocking() ? "[차단]" : "[경고]", c.message()))
                .collect(java.util.stream.Collectors.joining("\n"));

        String rendered = "%s **%s %s 검증: %d건**%s%n%s%n".formatted(
                blocking ? "❌" : "⚠️", date, edition, checks.size(),
                blocking ? " — 차단 항목이 있어 이대로는 승인되지 않습니다" : "",
                lines);

        // 서식은 여기서 끝낸다 — 본문에 '%'가 들어 있으면 다시 포맷할 때 예외로 죽는다
        // (reportYield에서 실제로 겪은 함정).
        System.out.println(rendered);
        appendToStepSummary(rendered);
    }

    /**
     * 사실 검수를 돌려 지적을 요약 화면에 남긴다. 실패해도 job을 죽이지 않는다.
     * 검수는 덧붙이는 단계라, 여기서 죽으면 이미 요금을 낸 문서까지 커밋되지 않는다.
     *
     * @return 요약 화면에 쓴 글(테스트용)
     */
    static String reportFactCheck(DocumentFactChecker checker, GeneratedDocumentItem document, LocalDate date) {
        var documentEdition = ClaudeDocumentGenerator.editionOf(document.contentMd());
        String edition = documentEdition.getDisplayName();
        String rendered;
        try {
            rendered = renderFactCheck(date, edition,
                    checker.check(document.title(), document.contentMd(), documentEdition));
        } catch (RuntimeException e) {
            rendered = "⚠️ **%s %s 사실 검수 실패** — 문서는 저장했습니다. 승인 전에 사람이 읽어 주세요 (%s)%n"
                    .formatted(date, edition, e.getMessage());
        }
        System.out.println(rendered);
        appendToStepSummary(rendered);
        return rendered;
    }

    static String renderFactCheck(LocalDate date, String edition, List<FactCheckFinding> findings) {
        if (findings.isEmpty()) {
            return "🔎 %s %s 사실 검수: 지적 없음%n".formatted(date, edition);
        }
        String lines = findings.stream()
                .map(f -> "- [%s·%s] \"%s\"%n  - 이유: %s%n  - 바로잡으면: %s%s".formatted(
                        switch (f.kind()) {
                            case INTERNAL_MISMATCH -> "문서 안 불일치";
                            case UNDEFINED_TERM -> "정의 없는 용어";
                            case FACT_ERROR -> "사실 오류";
                        },
                        f.confidence() == FactCheckFinding.Confidence.HIGH ? "확신" : "의심",
                        f.quote(), f.reason(), f.correction(),
                        // URL은 인자로 넘긴다. 형식 문자열에 붙이면 %20 같은 인코딩이 서식으로 읽힌다
                        f.sourceUrl() == null || f.sourceUrl().isBlank() ? "" : "\n  - 근거: " + f.sourceUrl()))
                .collect(java.util.stream.Collectors.joining("\n"));
        return "🔎 **%s %s 사실 검수: %d건** — 승인 전에 확인하세요. AI 지적이라 틀릴 수 있습니다%n%s%n"
                .formatted(date, edition, findings.size(), lines);
    }

    /** 사유 목록을 마크다운 불릿으로. 요약 화면과 표준 출력 양쪽에서 읽히는 형태다. */
    static String bullets(List<String> lines) {
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
    /**
     * 사람에게 알린다 — 로그에 찍고 <b>실행 요약에도</b> 남긴다.
     *
     * <h2>왜 만들었나(2026-08-29)</h2>
     *
     * <p>끝나는 길이 여섯인데(꺼짐·쉬는 날·문제 건너뜀·문서 건너뜀·문제 저장·문서 저장)
     * 요약에 무언가를 남기는 것은 수확 경고와 주제 범위뿐이었다. 나머지는 stdout에만 찍고
     * 종료 코드 0으로 끝나서, Actions 화면에서 <b>5문제 만든 날과 아무것도 안 한 날이 똑같이
     * 초록불</b>로 보였다. 요약 스텝이 찍는 것도 {@code ls | tail -5}뿐이라 전날과 같은 목록이었다.
     *
     * <p>이 프로젝트는 "조용히 아무것도 안 하는 배치"에 이미 한 번 당했다(docs/14, 초안 0건).
     * 그때 옮긴 것은 <b>실행 주체</b>였고, 이번에 막는 것은 <b>보고</b>다 — 돌긴 도는데 무엇을
     * 했는지 알 수 없으면 결국 같은 자리로 돌아온다.
     *
     * <p>로그와 요약에 같은 문장을 보내는 이유: 두 곳에 다른 말을 쓰기 시작하면 언젠가 한쪽만
     * 고쳐져 어긋난다. 요약은 로그의 발췌가 아니라 <b>같은 문장의 다른 창</b>이다.
     */
    static void announce(String markdown) {
        System.out.println(markdown.stripTrailing());
        appendToStepSummary(markdown);
    }

    static void appendToStepSummary(String markdown) {
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
}
