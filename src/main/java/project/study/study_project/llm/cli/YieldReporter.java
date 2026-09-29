package project.study.study_project.llm.cli;

import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.llm.client.GeneratedProblemItem;
import project.study.study_project.llm.client.SourceDocument;
import project.study.study_project.llm.support.ProblemItemRule;
import project.study.study_project.llm.support.SourceQuoteRule;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 문제 배치의 수확량 점검. 요청한 개수만큼 쓸 수 있는 문제가 나왔는지 본다.
 *
 * <p>{@link DraftGeneratorCli}에서 역할별로 떼어 냈다(2026-09-29). 동작은 그대로다.
 */
final class YieldReporter {

    private YieldReporter() {
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
        return checkYield(problems, requested, type, difficulty, null);
    }

    /**
     * 근거 문서까지 들고 세는 판 — 인용 검사({@link SourceQuoteRule})가 추가로 붙는다.
     *
     * <p><b>왜 인자를 하나 더 받는 오버로드인가.</b> 인용 검사만 유독 문서를 필요로 하는데,
     * 그 사정 때문에 기존 4인자 형태를 없애면 이 검사와 아무 상관 없는 호출부와 테스트가
     * 전부 흔들린다. 손대야 할 곳이 많을수록 정작 봐야 할 변경이 그 안에 묻힌다.
     *
     * @param source 근거 문서. {@code null}이면(폴백으로 돈 날) 인용 검사를 건너뛴다
     */
    static YieldCheck checkYield(List<GeneratedProblemItem> problems, int requested, ProblemType type,
                                 Difficulty difficulty, SourceDocument source) {
        List<String> defects = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int usable = 0;

        for (int i = 0; i < problems.size(); i++) {
            GeneratedProblemItem item = problems.get(i);
            String defect = ProblemItemRule.defectOf(item, type);
            if (defect != null) {
                // 빈 껍데기면 지문 조각 대신 모델이 남긴 이유를 싣는다(2026-09-19). 지문이 없으니
                // snippet은 늘 "(지문 없음)"이라 아무것도 말해 주지 않았다 — 네 주기 동안 그랬다.
                String reason = skipReasonOf(item);
                defects.add("%d번 — %s: %s".formatted(i + 1, defect,
                        reason != null ? "모델이 남긴 이유 — " + reason : ProblemItemRule.snippet(item)));
                continue;
            }
            usable++;
            // 버릴 것은 아니지만 알려야 할 것. 흡수를 통과하므로 usable을 센 <뒤에> 본다 —
            // 여기서 usable에서 빼면 경고가 말하는 개수와 실제 검수함 개수가 어긋난다.
            // source가 있으면 해설이 "다시 읽을 절"을 가리켜야 한다(2026-08-25) — 폴백으로
            // 근거 없이 만든 날은 가리킬 곳이 없으므로 그 검사를 켜지 않는다.
            for (String warning : ProblemItemRule.qualityWarningsOf(item, difficulty, source != null, type)) {
                warnings.add("%d번 [%s] — %s".formatted(i + 1, warning, ProblemItemRule.snippet(item)));
            }
            // 인용 검사는 문서를 들고 있어야 해서 규칙이 따로 산다(SourceQuoteRule 클래스 주석).
            // 경고를 내보내는 통로는 같게 둔다 — 검수자에게는 한 줄로 나란히 보이는 편이 낫다.
            String quoteWarning = SourceQuoteRule.warningOf(item, source, difficulty);
            if (quoteWarning != null) {
                warnings.add("%d번 [%s] — %s".formatted(i + 1, quoteWarning, ProblemItemRule.snippet(item)));
            }
        }

        // 배치 전체를 봐야 알 수 있는 것 — 형태 쏠림(2026-08-25)과 OX 참·거짓 쏠림(2026-08-31).
        // 한 문제만 봐서는 알 수 없어 항목 루프 밖에 둔다. 번호를 붙이지 않는 것도 그래서다:
        // 특정 문제의 잘못이 아니다.
        warnings.addAll(ProblemItemRule.batchWarningsOf(problems, difficulty, type));

        return new YieldCheck(requested, problems.size(), usable, defects, warnings);
    }

    /**
     * 지문이 빈 항목을 배열에서 뺀다 — 저장 직전에 한 번만 부른다.
     *
     * <p><b>왜 껍데기를 파일에 남기지 않는가.</b> 이 클래스의 원칙은 "모델이 준 것을 있는 그대로
     * 남긴다"이고, 그 이유는 나중에 원본과 대조해 프롬프트를 고치기 위해서다. 그런데 물음이 없는
     * 항목에는 대조할 것이 없다 — 해설과 보기만 보고 "무엇을 물으려 했는가"를 되짚을 수 없으므로
     * 재료가 되지 못한다. 남겨 두면 <b>파일 개수만 부풀린다</b>.
     *
     * <p><b>실제로 겪은 일.</b> 2026-09-13 배치는 요약 화면에 두 줄을 나란히 찍었다 —
     * "요청 5개 중 3개만 쓸 수 있습니다"와 "문제 초안 5건". 아래 줄이 모델 응답 개수를 세고
     * 있었기 때문이다. 같은 화면에서 두 수가 다르면 사람은 큰 쪽을 믿고 넘어가고, 검수함을
     * 열어 본 뒤에야 어긋남을 안다. 세는 대상을 <b>파일에 든 것</b>으로 맞춰 그 어긋남을 없앤다.
     *
     * <p><b>다른 결함은 건드리지 않는다.</b> 정답이 둘인 문제, 보기를 번호로 가리키는 해설 따위는
     * 그대로 남겨 파일에 들어간다. 그런 항목은 읽을 수 있으니 대조할 재료가 되고, 거르는 일은
     * 흡수 단계({@code LlmProblemService})의 몫이다 — 같은 규칙을 두 곳에 두지 않는다는
     * 클래스 주석의 원칙 그대로다.
     *
     * <p>0건이 되는 경우는 여기까지 오지 않는다. 바로 앞의 {@link #reportYield}가
     * {@code usable == 0}에서 이미 job을 실패시킨다.
     */
    static List<GeneratedProblemItem> dropBlankQuestions(List<GeneratedProblemItem> problems) {
        return problems.stream()
                .filter(item -> !ProblemItemRule.hasBlankQuestion(item))
                .toList();
    }

    /**
     * 빈 껍데기가 남긴 이유 — 없으면 {@code null}. 지문이 있는 항목은 이유가 있어도 무시한다
     * (문제를 냈으면 못 만든 이유가 성립하지 않는다 — 모델이 습관처럼 채운 값일 뿐이다).
     */
    static String skipReasonOf(GeneratedProblemItem item) {
        if (!ProblemItemRule.hasBlankQuestion(item)) {
            return null;
        }
        String reason = item.skipReason();
        return (reason == null || reason.isBlank()) ? null : reason.trim();
    }

    /**
     * 걷어낼 껍데기들의 이유를 결과 파일에 옮겨 둘 모양으로 — {@code dropBlankQuestions}와 짝이다(2026-09-19).
     *
     * <p><b>왜 걷어내기 전에 따로 빼 두나.</b> 껍데기를 파일에 남기지 않는 판단({@link #dropBlankQuestions} 주석)은 옳지만,
     * 그러면 이유도 함께 사라진다. 이유는 "물음이 없어 대조할 것이 없는" 껍데기에서 <b>유일하게
     * 대조할 수 있는 것</b>이라 따로 살린다. 이유를 안 남긴 껍데기는 그 사실 자체를 적는다 —
     * 빠뜨리면 "요청 3, 나옴 1인데 이유는 1개"가 되어 나머지 하나가 왜 없는지 또 모르게 된다.
     *
     * @return 빈 자리가 없으면 {@code null} — 파일에 빈 배열 대신 필드 자체가 안 보이게
     */
    static List<String> shortfallReasons(List<GeneratedProblemItem> problems) {
        List<String> reasons = new ArrayList<>();
        for (int i = 0; i < problems.size(); i++) {
            GeneratedProblemItem item = problems.get(i);
            if (!ProblemItemRule.hasBlankQuestion(item)) {
                continue;
            }
            String reason = skipReasonOf(item);
            reasons.add("%d번: %s".formatted(i + 1, reason != null ? reason : "(이유를 남기지 않음)"));
        }
        return reasons.isEmpty() ? null : reasons;
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
    static void reportYield(YieldCheck yield, LocalDate date) {
        if (yield.usable() == 0) {
            // 요약 화면에도 남긴다 — 실패한 job일수록 원인이 위에 보여야 한다
            BatchReports.appendToStepSummary("""
                    ❌ **%s 생성 실패 — 쓸 수 있는 문제가 하나도 없습니다**

                    모델이 %d개를 돌려줬지만 전부 규약을 어겼습니다.

                    %s
                    """.formatted(date, yield.received(), BatchReports.bullets(yield.defects())));
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
                message.append("버려질 항목:%n%s%n%n".formatted(BatchReports.bullets(yield.defects())));
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
                    .formatted(yield.warnings().size(), BatchReports.bullets(yield.warnings())));
        }

        // 여기서 String.format을 한 번 더 돌리면 안 된다 — 지문 앞부분에 '%'가 들어 있으면
        // ("평시 캐시 히트율이 95%인 조회 API…") 그걸 서식 지시자로 읽고 예외로 죽는다.
        // 서식은 위에서 인자로 넘겨 이미 끝냈다.
        String rendered = message.toString();
        System.out.println(rendered);
        BatchReports.appendToStepSummary(rendered);
    }
}
