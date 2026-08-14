package project.study.study_project.llm.support;

import project.study.study_project.global.common.ProblemType;
import project.study.study_project.llm.client.GeneratedProblemItem;

import java.util.List;

/**
 * 모델이 만든 문제 항목 하나가 <b>쓸 수 있는 물건인지</b> 판정하는 규칙 — 생성 배치와 흡수가 공유한다.
 *
 * <p><b>왜 따로 뺐나.</b> 같은 질문을 서로 다른 두 곳에서 던지게 됐기 때문이다.
 * <ul>
 *   <li>{@code LlmProblemService.toDraft}: "이걸 DB에 넣어도 되나?" → 어기면 그 항목만 버린다
 *   <li>{@code DraftGeneratorCli}: "요금을 냈는데 물건이 제대로 왔나?" → 부족하면 사람에게 알린다
 * </ul>
 * 목적은 다르지만 <b>판정 기준은 같아야</b> 한다. 다르면 배치가 "5개 다 멀쩡하다"고 보고한 날
 * 흡수는 2개만 저장하는, 서로 다른 말을 하는 상태가 된다. 그러면 경고를 믿을 수 없게 되고,
 * 믿을 수 없는 경고는 없는 것만 못하다.
 *
 * <p>이 저장소는 같은 종류의 사고를 이미 두 번 겪었다 — 문제 쪽만 새 주기 규칙으로 옮기고
 * 문서 쪽은 옛 규칙을 두어 8분야 중 2개만 돌던 버그({@code DraftGeneratorCli.documentDomain}),
 * 그리고 손으로 만든 지문 스냅샷이 갱신되지 않아 없는 문제를 계속 피하던 건
 * ({@code ExistingQuestionsExporter}). 둘 다 "같은 정보가 두 곳에 적혀 있었다"가 원인이다.
 * {@code ClaudeDocumentGenerator.REQUIRED_SECTIONS}를 생성기와 검증기가 공유하는 것과 같은 처방이다.
 *
 * <p><b>여기서 걸러내지는 않는다.</b> 이 클래스는 판정만 하고, 걸러낼지 경고만 할지는 부르는 쪽이
 * 정한다. 배치는 <b>모델이 준 것을 있는 그대로</b> 파일에 남기고(나중에 "왜 버려졌지"를 원본과
 * 대조하기 위해, {@code DraftGeneratorCli} 클래스 주석), 흡수는 어긴 항목을 버린다.
 */
public final class ProblemItemRule {

    /**
     * 객관식 보기의 최소 개수.
     *
     * <p>프롬프트는 "정확히 4개"를 요구하지만 여기서는 2개까지 받아 준다. 판정의 목적이
     * "면접 문제로 좋은가"가 아니라 <b>"퀴즈로 성립하는가"</b>이기 때문이다 — 보기가 3개여도
     * 고르는 문제는 된다. 4개를 강제하면 멀쩡한 문제가 버려지고, 그건 손해다.
     * 개수가 프롬프트와 어긋나는 것은 검수자가 눈으로 볼 몫이다.
     */
    public static final int MIN_CHOICES = 2;

    /** 지문이 비었을 때 대신 보여 줄 문구 — 로그에 빈 문자열이 찍히면 무엇이 문제인지 안 보인다. */
    private static final String NO_QUESTION = "(지문 없음)";

    /** 로그 한 줄이 화면을 넘지 않을 길이. 무엇에 대한 말인지 알아볼 정도면 충분하다. */
    private static final int SNIPPET_LENGTH = 50;

    private ProblemItemRule() {
    }

    /**
     * 항목이 규약을 어겼으면 <b>사람이 읽을 수 있는 사유</b>를, 멀쩡하면 {@code null}을 돌려준다.
     *
     * <p>불리언이 아니라 사유 문자열을 돌려주는 이유: 부르는 쪽 둘 다 결국 "왜 버렸는지"를
     * 남겨야 하는데, 판정과 사유가 따로 있으면 사유 문구가 두 곳에서 갈라진다.
     * 판정한 자리에서 이유까지 만들어 주는 편이 어긋날 자리가 없다.
     *
     * <p><b>해설이 비었는지는 보지 않는다.</b> 해설 없는 문제도 퀴즈로는 성립하므로 흡수는
     * 통과시킨다. 다만 이 서비스에서 해설은 핵심 가치라 그냥 넘길 일도 아니어서,
     * {@link #hasBlankExplanation}으로 따로 물어볼 수 있게 나눠 뒀다 —
     * "버릴 것"과 "알릴 것"을 한 판정에 섞으면 유효 개수가 흡수 결과와 어긋난다.
     *
     * @param type 문제 유형. 객관식이냐에 따라 규약이 통째로 달라진다
     */
    public static String defectOf(GeneratedProblemItem item, ProblemType type) {
        if (isBlank(item.question())) {
            return "지문이 비어 있음";
        }

        if (type == ProblemType.MULTIPLE_CHOICE) {
            List<GeneratedProblemItem.GeneratedChoice> choices = item.choices();
            int size = choices == null ? 0 : choices.size();
            long correct = choices == null ? 0
                    : choices.stream().filter(GeneratedProblemItem.GeneratedChoice::correct).count();
            if (size < MIN_CHOICES || correct != 1) {
                return "객관식 보기 규약 위반 (보기 %d개, 정답 %d개)".formatted(size, correct);
            }
            return null;
        }

        // OX·단답형은 채점 기준값이 없으면 채점 자체를 할 수 없다.
        // (객관식의 answer는 반대로 <비어 있어야> 정상이다 — 정답이 보기 쪽에 있다, docs/01)
        if (isBlank(item.answer())) {
            return "%s 유형인데 answer 없음".formatted(type);
        }
        return null;
    }

    /** 규약을 지켰는지만 알고 싶을 때. */
    public static boolean isUsable(GeneratedProblemItem item, ProblemType type) {
        return defectOf(item, type) == null;
    }

    /**
     * 해설이 비었는지 — 흡수는 통과하지만 <b>알려야 하는</b> 상태.
     *
     * <p>해설은 "왜 정답인지"를 설명하는 이 서비스의 핵심 가치다. 없어도 문제는 돌아가므로
     * 버리지는 않지만, 조용히 넘어가면 해설 없는 문제가 쌓인다.
     */
    public static boolean hasBlankExplanation(GeneratedProblemItem item) {
        return isBlank(item.explanation());
    }

    /** 로그·경고에 쓸 지문 앞부분. 지문이 없으면 그 사실을 문구로 보여 준다. */
    public static String snippet(GeneratedProblemItem item) {
        String question = item.question();
        if (isBlank(question)) {
            return NO_QUESTION;
        }
        String trimmed = question.trim();
        return trimmed.length() > SNIPPET_LENGTH ? trimmed.substring(0, SNIPPET_LENGTH) + "…" : trimmed;
    }

    /**
     * 구조화 출력은 필수 필드를 <b>빈 문자열</b>로 채워 보낼 수 있다(스키마상 nullable 표현이 제한적이라
     * "값 없음"을 {@code ""}로 받기로 한 설계 — {@code GeneratedProblemItem} 주석).
     * 그래서 null과 공백을 같은 것으로 본다.
     */
    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
