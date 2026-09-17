package project.study.study_project.llm.support;

import java.util.regex.Pattern;

/**
 * 제목 형식 판정 — <b>문서 제목과 문제 제목이 같은 잣대를 쓰게</b> 한 곳에 모아 둔 규칙(2026-09-17).
 *
 * <h2>왜 클래스 하나로 뺐는가</h2>
 *
 * <p>같은 규칙을 두 곳에 적으면 갈라진다는 것을 이 저장소가 여러 번 겪었다(프롬프트의 숫자와
 * 검증기의 상수가 어긋난 건, 절 이름이 두 파일에 흩어진 건). 제목 형식은 지금 <b>세 곳</b>이
 * 본다 — {@link DocumentDraftValidator}(문서 초안), {@link ProblemItemRule}(문제 초안), 그리고
 * 두 생성 프롬프트의 {@code [제목]} 절이다. 판정을 각자 적으면 "문서에서는 걸리는데 문제에서는
 * 안 걸리는" 제목이 생기고, 그 차이는 목록 화면에서 <b>두 세대가 다르게 읽히는</b> 것으로 나타난다.
 *
 * <p>프롬프트 문구까지 여기로 끌어오지는 않았다. 문서 제목과 문제 제목은 요구가 겹치지 않는
 * 부분이 더 크다 — 문서는 제품 이름을 괄호로 뒤에 빼라고 하고, 문제는 40자 상한과 "정답을
 * 제목에 쓰지 마라"가 핵심이다. <b>겹치는 둘(의문문 금지·줄표 부연 금지)만</b> 여기서 판정한다.
 *
 * <h2>왜 이렇게 좁게 잡는가</h2>
 *
 * <p>한국어 의문형 어미를 넓게 잡으면 멀쩡한 명사구가 줄줄이 걸린다. {@code 나$}를 넣으면
 * "선택지 하나"가, {@code 가$}를 넣으면 "안정성 평가"가, {@code 요$}를 넣으면 "필요"가
 * 함께 걸린다. 오탐이 섞인 경고는
 * <b>경고 전체를 무력하게 만든다</b> — 사람이 매번 무시하는 습관이 생기고, 그때부터 검사는
 * 없는 것과 같다. 그래서 명사로는 거의 끝나지 않는 어미만 남겼다.
 *
 * <p>반대로 {@code 까$}는 넓어 보여도 안전해서 그대로 뒀다 — 한국어에서 "까"로 끝나는 명사가
 * 사실상 없어, 걸리는 것은 "~할까·~일까·~니까"뿐이다. 처음에는 {@code 을까|ㄹ까|일까}로 적었는데
 * <b>"할까"를 못 잡았다</b>: 한글은 자모가 글자로 합쳐지므로 {@code ㄹ까}라는 조각은 완성형
 * 글자와 영영 만나지 않는다. 테스트가 잡아 준 자리다({@code TitleStyleRuleTest}). 놓치는 것이 있는 편이
 * 헛울리는 것보다 낫다는 판단이고, 이건 {@code DocumentDraftValidator}의 용어 검사가
 * "실제로 새어 나간 계열만" 보는 것과 같은 결정이다.
 */
public final class TitleStyleRule {

    /**
     * 의문문으로 끝나는 제목. 물음표를 함께 보는 이유는 영어식·구어식 물음이 어미 없이
     * 물음표만으로 끝나기 때문이다("이 상황의 원인으로 가장 적절한 것은?").
     */
    private static final Pattern QUESTION_ENDING =
            Pattern.compile("(는가|은가|인가|한가|던가|까|나요|가요|\\?)$");

    /**
     * 제목에 붙은 줄표 부연.
     *
     * <p>줄표를 <b>제목 안 어디에서든</b> 잡는 이유: 줄표가 붙는 자리는 언제나 부연이 시작되는
     * 자리라서, 앞뒤 어느 쪽이 본체인지와 무관하게 제목이 둘을 담고 있다는 신호다.
     * 붙임표({@code -})는 제외했다 — "3-way 핸드셰이크"처럼 낱말 안에서 쓰인다.
     */
    private static final Pattern DASH_SUBTITLE = Pattern.compile("[—–]|\\s-\\s");

    private TitleStyleRule() {
    }

    /** 의문문 제목인가. {@code null}·공백은 판정하지 않는다(빈 제목은 부르는 쪽이 이미 잡는다). */
    public static boolean isQuestionForm(String title) {
        return title != null && QUESTION_ENDING.matcher(title.trim()).find();
    }

    /** 줄표 부연이 붙었는가. */
    public static boolean hasDashSubtitle(String title) {
        return title != null && DASH_SUBTITLE.matcher(title.trim()).find();
    }
}
