package project.study.study_project.llm.support;

import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.llm.client.GeneratedProblemItem;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

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

    /* ── 품질 경고 ───────────────────────────────────────────────
     * 여기부터는 "퀴즈로 성립하는가"가 아니라 "우리가 원하는 물건인가"를 본다.
     *
     * 왜 defectOf에 넣지 않았나. 규약 위반은 흡수가 <버린다>. 해설이 350자인 문제를
     * 버리면 멀쩡한 문제를 요금까지 내고 잃는 셈이다. 품질은 사람이 검수함에서 보고
     * 판단할 몫이고, 여기서 할 일은 <놓치지 않게 세어 주는 것>까지다.
     *
     * 왜 검증기가 필요한가. 프롬프트에 적어 둔 숫자는 지켜지지 않는다는 것을 실측했다 —
     * "해설은 400~700자로 쓴다"고 적어 뒀는데 2026-08-13 배치는 5개가 전부 359~395자였고,
     * 아무도 몰랐다. 검사하지 않는 규칙은 없는 규칙이다. */

    /**
     * 해설 분량의 아래·위 한계. <b>생성 프롬프트의 숫자와 같아야 한다</b>
     * ({@code ClaudeProblemGenerator.SYSTEM_PROMPT}의 [해설] 절).
     *
     * <p>둘이 갈라지면 두 방향으로 다 나쁘다. 검사가 느슨하면 지시를 어겨도 조용하고,
     * 검사가 빡빡하면 <b>지시대로 쓴 해설이 매번 경고를 달고 나온다</b>. 경고가 일상이 되면
     * 사람이 경고를 안 보게 되는데, 그게 이 저장소가 이미 겪은 실패 방식이다
     * ({@code DocumentDraftValidator.WARN_LENGTH} 주석의 "한쪽만 고치면" 문단).
     * 그래서 {@code ClaudeProblemGeneratorPromptTest}가 프롬프트와 이 값을 대조한다.
     */
    public static final int EXPLANATION_MIN = 400;

    /** 해설 분량 위 한계 — {@link #EXPLANATION_MIN} 참고. */
    public static final int EXPLANATION_MAX = 700;

    /**
     * 초급 지문의 길이 상한.
     *
     * <p>초급 규칙은 "상황 없이 한두 문장"인데, 그 말은 세어 볼 수가 없다. 실측으로 대신했다 —
     * 2026-08-16 초급 5문제의 지문은 45·97·124·145·149자였고, <b>짧은 45자짜리 하나만</b>
     * 상황 없는 정의 문제였다. 나머지 넷은 모두 "~했다"로 시작하는 배경 서술이 붙어 있었다.
     * 그 경계가 대략 여기다.
     *
     * <p>넘었다고 곧바로 잘못은 아니다. 용어가 길거나 조건을 한 줄 붙여야 하는 문제도 있다.
     * 그래서 차단이 아니라 경고이고, 판단은 검수자가 한다.
     */
    public static final int BEGINNER_QUESTION_MAX = 120;

    /**
     * 지문이 <b>근거 문서를 가리키는</b> 표현. 문제는 혼자서 성립해야 한다.
     *
     * <p>2026-08-16 4번이 실제로 이랬다: "MVCC가 읽기를 대기 없이 처리할 수 있는 대신 치르는
     * 대가로 <b>문서가 든</b> 것은?" 문서를 읽지 않은 학습자에게는 <b>무슨 문서인지조차</b>
     * 알 수 없는 문제가 된다. 게다가 이 서비스에서 문제는 데일리 퀴즈·복습으로 문서와
     * 떨어져 노출되므로, 그 자리에는 가리킬 문서가 아예 없다.
     *
     * <p>근거 문서는 <b>출제의 재료</b>이지 문제의 등장인물이 아니다 — 그 구분이 무너진 자리다.
     */
    private static final Pattern DOCUMENT_REFERENCE =
            Pattern.compile("문서(가|에|에서|의|를|와|에는|에도)?\\s*(든|따르면|설명한|제시한|말한|언급한|나온|밝힌)");

    /**
     * 해설이 보기를 <b>번호로</b> 가리키는 표현. 내보낼 때 보기를 섞으므로 번호가 어긋난다.
     *
     * <p>정답 위치 편향을 고치면서 보기를 내보내는 시점에 섞기로 했는데({@code QuizProblemItem},
     * 커밋 9a4fd6b), 그 결정에는 <b>짝이 되는 제약</b>이 딸려 있었다 — 해설이 "2번 보기는"
     * 이라고 쓰면 섞인 뒤에는 엉뚱한 보기를 가리킨다. 지금까지 사고가 안 난 것은 모델이
     * 마침 번호를 안 썼기 때문이지 막아 둬서가 아니다.
     *
     * <p>이런 종류가 가장 위험하다. 검수자는 <b>섞이기 전</b> 화면을 보므로 번호가 맞아
     * 보이고, 학습자만 어긋난 해설을 읽는다. 사람 눈으로는 영영 안 걸리는 결함이다.
     *
     * <p><b>일부러 좁게 잡았다.</b> 처음에는 {@code (보기|선택지)\s*[1-4]\b}까지 넣었는데,
     * 자바의 {@code \b}는 {@code \w}(영숫자) 기준이라 한글 앞에서도 경계가 잡힌다 —
     * "보기 <b>4개</b> 중 하나만 옳다" 같은 멀쩡한 문장이 걸렸다. 맨 {@code [1-4]번}도
     * 뺐다("TCP는 <b>3번의</b> 왕복으로 연결을 맺는다"가 걸린다).
     *
     * <p>대신 <b>원문자({@code ①~④})는 단독으로도</b> 잡는다. 해설에서 원문자가 보기 말고
     * 다른 것을 가리키는 경우가 없기 때문이다.
     *
     * <p>그래서 "2번은 UDP의 특성이다"처럼 <b>'보기'라는 말 없이 번호만 쓴</b> 형태는
     * 놓친다. 여기서는 그 편을 택했다 — 이 경고는 "학습자에게만 어긋나 보인다"는 무서운
     * 문구를 달고 나가므로, 헛울리면 다음부터 아무도 안 믿는다.
     */
    private static final Pattern CHOICE_NUMBER_REFERENCE =
            Pattern.compile("([1-4]번|첫\\s*번째|두\\s*번째|세\\s*번째|네\\s*번째)\\s*(보기|선택지|항목)"
                    + "|(보기|선택지)\\s*([1-4]번|[①-④])"
                    + "|[①-④]");

    /**
     * 항목 하나의 품질 경고를 모두 모아 돌려준다. 없으면 빈 목록.
     *
     * <p>{@link #defectOf}가 하나만 돌려주는 것과 달리 여기는 목록이다 — 규약 위반은 하나만
     * 있어도 그 항목을 버리므로 첫 번째면 충분하지만, 경고는 사람이 <b>한 번에 다 보고</b>
     * 고치는 편이 왕복이 적다({@code DocumentDraftValidator.validate}와 같은 판단).
     *
     * @param difficulty 난이도. {@code null}이면 난이도별 검사(지문 길이)를 건너뛴다 —
     *                   관리자 화면의 직접 생성처럼 난이도를 알 수 없는 경로를 위한 여지
     */
    public static List<String> qualityWarningsOf(GeneratedProblemItem item, Difficulty difficulty) {
        List<String> warnings = new ArrayList<>();

        String explanation = item.explanation();
        if (isBlank(explanation)) {
            warnings.add("해설 없음");
        } else {
            int length = explanation.trim().length();
            if (length < EXPLANATION_MIN) {
                warnings.add("해설이 짧음 (%d자, 기준 %d자)".formatted(length, EXPLANATION_MIN));
            } else if (length > EXPLANATION_MAX) {
                warnings.add("해설이 김 (%d자, 기준 %d자)".formatted(length, EXPLANATION_MAX));
            }
            String choiceRef = contextOf(CHOICE_NUMBER_REFERENCE, explanation);
            if (choiceRef != null) {
                warnings.add("해설이 보기를 번호로 가리킴 (\"%s\" — 내보낼 때 섞으므로 어긋난다)"
                        .formatted(choiceRef));
            }
        }

        String question = item.question();
        if (!isBlank(question)) {
            String documentRef = contextOf(DOCUMENT_REFERENCE, question);
            if (documentRef != null) {
                warnings.add("지문이 근거 문서를 가리킴 (\"%s\" — 문제는 혼자 성립해야 한다)"
                        .formatted(documentRef));
            }
            if (difficulty == Difficulty.BEGINNER && question.trim().length() > BEGINNER_QUESTION_MAX) {
                warnings.add("초급 지문이 김 (%d자, 기준 %d자 — 상황 서술이 붙었을 수 있다)"
                        .formatted(question.trim().length(), BEGINNER_QUESTION_MAX));
            }
        }
        return warnings;
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
     * 걸린 자리의 <b>앞뒤를 조금 붙여</b> 돌려준다. 걸리지 않았으면 {@code null}.
     *
     * <p><b>왜 증거를 경고에 싣나.</b> "해설이 보기를 번호로 가리킴"만 알려 주면 사람이 해설
     * 500자를 처음부터 읽어 어디가 문제인지 찾아야 한다. 2026-08-17 평가 실행에서 실제로
     * 그랬다 — 경고는 떴는데 <b>무엇이 걸렸는지 볼 방법이 없었다</b>. 경고가 그 자리를 직접
     * 가리키면 고칠지 말지가 한눈에 정해진다.
     *
     * <p>전문이 아니라 조각만 싣는 이유: 이 문자열은 Actions 요약과 평가 보고서에 <b>줄 단위로</b>
     * 찍힌다. 해설 전문을 실으면 경고 하나가 화면 반쪽을 먹어, 여러 건이 났을 때 오히려 안 읽힌다.
     *
     * <p>줄바꿈과 연속 공백을 한 칸으로 눌러 두는 것도 같은 이유다 — 한 줄로 유지돼야
     * 목록의 다른 항목과 나란히 읽힌다.
     */
    private static String contextOf(Pattern pattern, String text) {
        java.util.regex.Matcher m = pattern.matcher(text);
        if (!m.find()) {
            return null;
        }
        int from = Math.max(0, m.start() - CONTEXT_PAD);
        int to = Math.min(text.length(), m.end() + CONTEXT_PAD);
        String snippet = text.substring(from, to).replaceAll("\\s+", " ").trim();
        return (from > 0 ? "…" : "") + snippet + (to < text.length() ? "…" : "");
    }

    /** 걸린 자리 앞뒤로 붙여 보여 줄 글자 수. 무엇에 대한 말인지 알아볼 정도면 충분하다. */
    private static final int CONTEXT_PAD = 18;

    /**
     * 구조화 출력은 필수 필드를 <b>빈 문자열</b>로 채워 보낼 수 있다(스키마상 nullable 표현이 제한적이라
     * "값 없음"을 {@code ""}로 받기로 한 설계 — {@code GeneratedProblemItem} 주석).
     * 그래서 null과 공백을 같은 것으로 본다.
     */
    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
