package project.study.study_project.llm.support;

import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.llm.client.GeneratedProblemItem;
import project.study.study_project.llm.client.QuestionKind;

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

    /**
     * 객관식 보기의 <b>기대</b> 개수 — 프롬프트가 요구하는 값. 차단 기준이 아니라 경고 기준이다.
     *
     * <p>{@link #MIN_CHOICES}가 "버릴지"를 정한다면 이 값은 "알릴지"를 정한다. 둘을 나눈 이유는
     * 목적이 다르기 때문이다 — 버리는 기준은 <b>퀴즈로 성립하는가</b>라 느슨해야 하고,
     * 알리는 기준은 <b>프롬프트대로 나왔는가</b>라 정확해야 한다. 한 숫자로 합치면 둘 중
     * 하나가 망가진다(합치면 4개 미만이 전부 버려지거나, 5개짜리가 조용히 통과한다).
     *
     * <p>모자란 쪽(2~3개)은 경고하지 않는다. 그건 {@code [개수를 채우지 못할 때]}가 허용한
     * 결과일 수 있고, 재료가 마른 것은 수확량 점검이 따로 잡는다. 넘치는 쪽만 본다 —
     * 그건 지시를 어긴 것 외에 설명할 길이 없다.
     */
    public static final int EXPECTED_CHOICES = 4;

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

        // 해설이 보기를 번호로 가리키면 <학습자에게만> 어긋난 해설이 나간다 — 2026-08-25에
        // 경고에서 차단으로 올렸다. 이 검사만 경고 목록이 아니라 여기 있는 이유:
        //
        // 다른 경고들("해설이 짧음", "정답이 가장 긴 보기")은 사람이 보고 판단할 여지가 있고,
        // 버리면 요금까지 낸 멀쩡한 문제를 잃는다. 그런데 이건 판단할 여지가 없다 —
        // 보기를 섞어 내보내기로 한 이상(QuizProblemItem) 번호로 가리킨 해설은 <반드시> 틀린다.
        // 게다가 검수자는 섞이기 전 화면을 보므로 번호가 맞아 보여서, 경고로 두면
        // "번호 맞는데?" 하며 그대로 승인된다. 사람 눈으로는 영영 안 걸리는 결함이라
        // 기계가 막는 수밖에 없다(DraftCheck.Severity 주석의 "판단해 봐야 답이 하나인 것은 차단").
        String choiceRef = contextOf(CHOICE_NUMBER_REFERENCE, item.explanation());
        if (choiceRef != null) {
            return "해설이 보기를 번호로 가리킴 (\"%s\") — 내보낼 때 섞으므로 학습자에게는 어긋난다"
                    .formatted(choiceRef);
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

    /*
     * hasBlankExplanation()이 여기 있었다 — 2026-08-25에 지웠다.
     *
     * "해설이 비었으면 버리지는 말되 알려라"는 뜻으로 만들어 뒀는데, <아무 데서도 부르지
     * 않았다>. 그 사이 qualityWarningsOf가 같은 일을 하는 "해설 없음" 경고를 갖게 되면서
     * 같은 판단이 두 벌이 됐고, 그중 한 벌은 죽어 있었다.
     *
     * 지운 이유는 안 쓰여서가 아니라 <있으면 안 쓰인 채로 또 오해를 부르기 때문>이다.
     * 실제로 이번 점검에서 "빈 해설을 아무도 안 잡는다"고 잘못 읽었다 — 죽은 쪽을 보고
     * 산 쪽을 못 본 것이다. 판단은 qualityWarningsOf 한 곳에만 둔다.
     */

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
     * 중급 지문의 길이 상한 — 2026-08-18 신설.
     *
     * <p><b>왜 뒤늦게 생겼나.</b> 초급에는 상한이 있었는데 중급에는 없었다. 초급의 상한은
     * "상황이 붙었는지"를 재는 장치라 중급에는 필요 없다고 봤기 때문인데, 중급의 문제는
     * 상황의 <b>유무</b>가 아니라 <b>길이</b>였다. 사용자가 실물을 짚었다 — 쓰레드와 static
     * 카운터를 대조하는 지문을 읽고 "사람은 무슨 상황인지 이해할 수가 없다"고 했다.
     *
     * <p>길면 무엇을 묻는지가 흐려진다. 상황 자체는 중급의 정의라 없앨 수 없으니
     * (없애면 초급이 된다) <b>짧게 만드는 쪽</b>으로 잡았다. 프롬프트의
     * {@code [상황 지문 쓰는 법]}이 요구하는 두 요소 — 목적 한 문장, 증상 한 문장 —
     * 이면 대개 150자 안팎이고, 여유를 얹어 250자로 둔다.
     *
     * <p>초급과 같은 이유로 <b>경고이지 차단이 아니다</b>. 조건을 한 줄 더 붙여야 하는
     * 상황도 있고, 그 판단은 검수자의 몫이다. 길이는 결함의 <b>신호</b>일 뿐 결함 자체가 아니다 —
     * 진짜 결함("개념을 보여주려고 지어낸 장치")은 사람만 알아볼 수 있다.
     */
    public static final int INTERMEDIATE_QUESTION_MAX = 350;

    /**
     * 중급 <b>상황 적용형</b> 지문의 길이 하한 — 2026-08-25 신설.
     *
     * <p><b>상한만 있고 하한이 없던 것이 문제였다.</b> 사용자가 파일럿 5문제를 풀고
     * "지문을 더 구체화해야겠다"고 했다. 세어 보니 118·134·152·154·173자로,
     * 상한 250에 <b>한 문제도 근접하지 않았다</b>. 상한은 아무 일도 하지 않고 있었던 셈이다.
     *
     * <p>그래서 상한을 250→350으로 올리는 것만으로는 아무것도 바뀌지 않는다. 모델은 이미
     * 상한 근처에도 안 갔다. 실제로 지면을 쓰게 하는 것은 <b>하한</b>이다 — 문서 프롬프트에서
     * 이미 배운 것과 같다("개수를 박은 지시만 지켜졌다", {@code ClaudeDocumentGenerator}).
     *
     * <p><b>150인 근거.</b> 프롬프트의 {@code [상황 지문 쓰는 법]}은 두 요소를 요구한다 —
     * 무엇을 하려던 중인가(구체적 기능 이름), 무엇이 어긋났는가(숫자나 현상).
     * 둘을 제대로 쓰면 대략 150자다. 실측 다섯 중 셋(152·154·173)이 이미 넘었고,
     * 못 넘은 둘(118·134)이 정확히 "기능 이름이 뭉뚱그려졌거나 증상에 숫자가 없는" 쪽이었다.
     *
     * <p><b>상황형에만 건다.</b> 같은 날 중급에 네 형태(비교·인과·판정·순서)를 열었는데
     * ({@link project.study.study_project.llm.client.QuestionKind}) 그쪽은 <b>짧은 것이 정상</b>이다.
     * 진술 판정형은 지문이 한 줄이고 보기가 본문이다. 구분 없이 하한을 걸면 짧아도 되는
     * 문제에 군더더기를 붙이게 만든다 — 지금 고치려는 것과 정확히 반대 방향의 사고다.
     *
     * <p>초급·제목과 같은 이유로 <b>경고이지 차단이 아니다</b>. 149자짜리 멀쩡한 지문을
     * 버리면 요금까지 낸 문제를 통째로 잃는다.
     */
    public static final int SITUATION_QUESTION_MIN = 150;

    /**
     * 한 배치에 허용할 <b>상황 적용형</b>의 최대 개수 — 2026-08-25 신설, 같은 날 방향을 뒤집었다.
     *
     * <h2>왜 "최소 2개"에서 "최대 2개"로 뒤집었나</h2>
     *
     * <p>처음에는 하한이었다. 형태를 다섯으로 열면 모델이 쓰기 쉬운 쪽으로 쏠릴 텐데, 쏠려서
     * 곤란한 방향은 <b>상황형이 사라지는 쪽</b>이라고 봤다(면접에서 가장 많이 나오는 형태다).
     * 실물을 뽑아 보니 정반대였다 — 5문제 중 5개가 상황형이었고, 이어서 1건씩 세 번 더 뽑았는데
     * 세 번 다 상황형이었다. 막아야 할 쪽은 <b>상황형이 전부를 차지하는 쪽</b>이었다.
     *
     * <p><b>상황형 자체가 나쁜 것은 아니다.</b> 같은 날 잰 실측이 그것을 말한다 — NETWORK 중급
     * 5문제는 <b>전부 상황형인데도</b> 첫머리와 마지막 물음이 다섯 가지로 다 달랐다. 반면
     * SYSTEM_DESIGN 6문제는 {@code [업종] + ○○ 상세} 틀이 넷, "가장 적절한 것은?"이 다섯이었다.
     * 그러니 상황형을 없애는 것(사용자가 검토한 안)은 증상을 지우고 원인을 남기는 선택이다.
     * 상한을 걸어 <b>다른 형태가 들어올 자리를 만드는 쪽</b>이 맞다.
     *
     * <p><b>왜 유형별 개수를 전부 박지 않았나.</b> "상황 2 / 비교 2 / 인과 1" 식으로 박으면
     * 문서에 그 재료가 없을 때 <b>억지로 만든다</b>. 순서·절차형은 특히 그렇다 — 순서가 결과를
     * 가르는 주제(캐시 무효화·트랜잭션)에만 재료가 있는데, 개수를 박으면 순서가 아무 상관 없는
     * 주제에 순서를 지어낸다. 기존 {@code [개수를 채우지 못할 때]} 규칙과도 부딪힌다.
     * <b>천장만 씌우고 나머지는 재료에 맡기는 것</b>이 이 파이프라인의 방식이다.
     *
     * <p>배치 단위라 항목 하나만 보는 {@link #qualityWarningsOf}에서는 잴 수 없다.
     * {@link #batchWarningsOf}가 따로 있는 이유다.
     */
    public static final int SITUATION_MAX_PER_BATCH = 2;

    /**
     * 고급에 <b>열어 둔</b> 형태 — 2026-08-25 신설. 중급의 다섯 중 셋만이다.
     *
     * <p><b>왜 고급에도 형태를 여는가.</b> 전에는 고급을 "지문에 상황과 이미 시도한 것이 있다"로
     * 못 박아 사실상 {@link QuestionKind#SITUATION} 하나였다. 그런데 고급이 캐는 절을 실제로 열어
     * 보니 재료가 더 넓었다 — {@code ### 흔한 오해}("TIME_WAIT가 많으면 장애다")와
     * {@code ## 면접에서 이렇게 물어본다}의 질문들("왜 하필 2MSL인가", "커널 옵션이 있는데 왜
     * 함부로 쓰지 않는가")은 <b>상황 지문으로 만들려면 오히려 장면을 지어내야</b> 한다.
     * 프롬프트가 가장 경계하는 실패 방식이 그것이다.
     *
     * <p><b>왜 둘은 닫아 두는가.</b>
     * <ul>
     *   <li>{@link QuestionKind#JUDGMENT}: 중급 판정형과 겉모습이 같다(진술 넷 중 고르기).
     *       오답 설계로만 갈리는데, 그 구분이 이 프롬프트에서 가장 자주 무너지는 자리다
     *       (2026-08-13 중급과 08-14 고급의 지문이 형태상 똑같았던 사고).
     *   <li>{@link QuestionKind#SEQUENCE}: 순서는 정답이 하나로 딱 떨어져 "넷 다 그럴듯"이
     *       성립하지 않는다. 고급의 정의와 정면으로 부딪힌다.
     * </ul>
     *
     * <p><b>부수 효과</b>: 2026-08-14에 고급 날 재료가 말라 3문제밖에 못 나온 사고가 있었다.
     * 같은 재료를 여러 형태로 캘 수 있으면 그 압박이 줄어든다.
     */
    public static final java.util.Set<QuestionKind> ADVANCED_KINDS =
            java.util.Set.of(QuestionKind.SITUATION, QuestionKind.COMPARISON, QuestionKind.CAUSE);

    /**
     * 목록 제목의 길이 상한 — 2026-08-21 신설. <b>생성 프롬프트의 숫자와 같아야 한다</b>
     * ({@code ClaudeProblemGenerator.SYSTEM_PROMPT}의 [제목] 절).
     *
     * <p>제목은 목록 한 행에 <b>한 줄로</b> 들어가야 한다. 넘치면 말줄임표로 잘리는데,
     * 잘린 제목은 없느니만 못하다 — 앞부분만 보고 고르려던 사람이 결국 문제를 열어 봐야 한다.
     * 목록 화면의 제목 칸은 14px 기준 대략 35자라 여유를 조금 얹어 40자로 잡았다.
     *
     * <p><b>차단이 아니라 경고인 이유</b>는 해설·지문 길이와 같다. 41자짜리 멀쩡한 제목을
     * 버리면 요금까지 낸 문제를 통째로 잃는다. 게다가 제목은 검수자가 그 자리에서 고칠 수
     * 있는 <b>한 줄</b>이라, 버리는 비용이 고치는 비용보다 훨씬 크다.
     *
     * <p>DB 컬럼은 120자다(V13). 상한 둘이 다른 것은 의도다 — DB는 사고를 막는 선까지만 걸고,
     * 품질 기준은 여기서 잰다. 같게 맞추면 한 글자 넘겼다고 <b>저장 자체가 실패</b>한다.
     */
    public static final int TITLE_MAX = 40;

    /**
     * 보기 길이의 최대/최소 허용 비율. <b>생성 프롬프트의 숫자와 같아야 한다</b>
     * ([오답 보기의 조건] 절) — 갈라지면 지시대로 쓴 문제가 매번 경고를 단다.
     *
     * <p><b>왜 이 검사가 필요한가.</b> 승인·미승인 22문항을 세어 보니 <b>18개(82%)에서
     * 정답이 가장 긴 보기</b>였다. 균등하면 25%다. 이 상태면 학습자가 지문을 읽지 않고
     * "제일 긴 보기"만 골라도 대부분 맞힌다 — 정답이 4번에 한 번도 없던 사고와 같은 종류다.
     *
     * <p>다른 점은 <b>고칠 방법이 없다</b>는 것이다. 위치 편향은 내보낼 때 섞어서 없앴지만
     * ({@code QuizProblemItem}) 길이는 섞을 수가 없다. 그래서 프롬프트로 줄이고 여기서 센다.
     * 프롬프트에는 이미 "정답만 유독 길면 읽지 않고도 찍힌다"가 <b>있었는데도</b> 82%였다 —
     * 숫자 없는 요구는 지켜지지 않는다는 것을 여기서 또 확인했다.
     *
     * <p><b>1.5인 이유는 실측이다.</b> 문턱을 1.4로 하면 22개 중 8개(36%)가 걸려 시끄럽고,
     * 1.6이면 3개(14%)만 걸려 놓친다. 1.5는 5개(23%) — 네 문제에 한 건이라 읽힌다.
     *
     * <p><b>비율만 보거나 최장 여부만 보면 안 된다.</b> 정답이 우연히 최장인 경우는 넷 중 하나꼴로
     * 늘 생기므로 그것만으로 경고하면 4분의 1이 헛울린다. 반대로 비율만 보면 오답이 유독 긴
     * 멀쩡한 문제까지 걸린다. <b>둘이 겹칠 때</b>만 "읽지 않고 찍히는" 상태다.
     */
    public static final double CHOICE_LENGTH_RATIO = 1.5;

    /**
     * 지문·보기·해설에 섞인 마크다운 문법.
     *
     * <p>이 셋은 화면에 <b>평문 그대로</b> 나간다({@code player.js}의 {@code escapeHtml}) —
     * 백틱과 별표가 글자로 보인다. 문서 본문만 마크다운으로 렌더링되므로 헷갈리기 쉽다.
     *
     * <p><b>줄바꿈과 하이픈 목록은 잡지 않는다.</b> 해설은 "정답 근거 + 오답 셋"이라 본래 목록이
     * 어울리는 내용이고, 실제로 22개 중 17개가 그렇게 쓴다. 그쪽은 화면에서 살리는 것이 맞아
     * {@code .explain}에 {@code white-space: pre-wrap}을 넣었다 — 프롬프트로 목록을 금지하면
     * 해설이 오히려 나빠진다. 여기서 막는 것은 <b>살릴 수 없는 것</b>뿐이다.
     */
    private static final Pattern MARKDOWN_SYNTAX = Pattern.compile("`|\\*\\*");

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
     *
     * <p><b>"항목"을 뺀 이유(2026-08-17 오탐).</b> 처음에는 {@code 보기|선택지|항목}을 함께
     * 받았는데, 평가 실행에서 멀쩡한 해설 두 건이 걸렸다 — 걸린 자리는 보기가 아니라
     * <b>프롬프트가 시킨 마지막 줄</b>이었다:
     * "(문서의 '언제 깨지는가' <b>2번 항목</b>과 '흔한 오해 3'을 다시 읽어 보라)".
     * [해설] 절이 "다시 읽을 절을 한 줄로 가리킨다"고 요구하므로 이 형태는 <b>정상 동작</b>이다.
     * 보기를 "항목"이라 부르는 경우는 드물고, 문서 항목을 가리키는 것은 매번 나오므로
     * 오탐 비용이 훨씬 크다.
     */
    private static final Pattern CHOICE_NUMBER_REFERENCE =
            Pattern.compile("([1-4]번|첫\\s*번째|두\\s*번째|세\\s*번째|네\\s*번째)\\s*(보기|선택지)"
                    + "|(보기|선택지)\\s*([1-4]번|[①-④])"
                    + "|[①-④]");

    /**
     * 해설이 <b>근거 문서의 절</b>을 가리키는 마지막 줄 — 2026-08-25 신설.
     *
     * <p>프롬프트의 {@code [해설]} 절은 "근거 문서가 주어졌다면 마지막에 다시 읽을 절을 한 줄로
     * 가리킨다"를 요구한다. 이 한 줄이 <b>학습자가 틀린 뒤 돌아갈 유일한 입구</b>다 —
     * 오답노트와 복습 화면은 문서와 떨어져 있어서, 해설에 이 줄이 없으면 "그래서 어디를 읽지?"에
     * 답이 없다. 요구해 놓고 재지 않던 항목이라 실제로 빠져도 아무도 몰랐다.
     *
     * <p><b>{@code documentSlug}가 있을 때만 본다.</b> 근거 없이 만든 문제나 관리자가 올린 파일로
     * 만든 문제는 가리킬 문서가 없다({@code LlmProblemService.generateFromDocument} 주석).
     * 그런 문제에까지 경고를 달면 헛울리는 경고가 되어 다음부터 아무도 안 본다.
     *
     * <p>패턴을 느슨하게 잡은 이유: 프롬프트 예시는 {@code (문서의 '언제 깨지는가' 절을 다시
     * 읽어 보라)}인데 실물은 따옴표 종류·절 이름 표기가 매번 조금씩 다르다. 형식을 엄격히
     * 재면 정상 동작이 매번 걸린다. 여기서 확인하려는 것은 <b>그 줄이 있는지</b>뿐이다.
     *
     * <h2>2026-08-25 — 첫 패턴이 4/5를 헛짚었다</h2>
     *
     * <p>처음에는 {@code 문서의 '○○' 절}이라는 <b>한 가지 모양</b>만 봤다. 실물을 뽑아 보니
     * 다섯 중 넷이 그 모양이 아니었다:
     * <pre>
     *   (문서의 '언제 깨지는가' 중 '서버가 능동 종료자가 되는 배치' 대목을 다시 읽어 보라)
     *   (문서의 '면접에서 이렇게 물어본다' 중 TIME_WAIT 10만 개 문답을 다시 읽어 보라)
     * </pre>
     * 절 이름 뒤에 <b>하위 항목이 붙으면</b> "절"이 문장 끝이 아니라 중간에 오고, 끝나는 말이
     * "대목"·"문답"으로 바뀐다. 프롬프트가 요구한 것은 "다시 읽을 자리를 한 줄로 가리켜라"이고
     * 이것들은 <b>더 정확하게</b> 가리킨 것인데, 검사가 형식 하나만 알아서 벌을 준 셈이다.
     *
     * <p>그래서 기준을 <b>"문서를 가리키며 다시 읽으라고 했는가"</b>로 바꿨다. 절 이름의 표기나
     * 뒤에 붙는 말은 보지 않는다. 오탐이 넷이면 그 경고는 그 순간 죽는다 — 이 저장소가
     * 여러 번 확인한 것이다({@code CHOICE_NUMBER_REFERENCE}의 "항목"을 뺀 이유와 같다).
     */
    private static final Pattern DOCUMENT_SECTION_HINT =
            Pattern.compile("문서(의|에서|를)?\\s*.{0,120}?다시\\s*(읽어|보)");

    /**
     * 해설이 오답을 <b>내용으로 인용</b>한 자리 — 2026-08-25 신설.
     *
     * <p>프롬프트는 오답마다 왜 틀렸는지를 짚되 "순서가 아니라 내용으로 가리켜라"라고 요구하고,
     * 예시까지 따옴표로 묶인 형태다({@code "MVCC도 읽기에 공유 락을 건다"는 보기는…}).
     * 그래서 <b>따옴표로 묶인 인용의 개수</b>가 오답을 몇 개 짚었는지의 대리 지표가 된다.
     *
     * <p><b>대리 지표라는 것을 분명히 해 둔다.</b> 인용 없이 "잔여 수량을 캐시에만 쓰겠다는 판단은"
     * 처럼 풀어 쓴 해설도 정상이고, 이 검사는 그걸 놓친다. 반대로 정답 근거를 설명하며 문서
     * 문장을 따옴표로 인용해도 개수에 들어간다. 그래서 <b>개수가 모자랄 때만</b> 경고하고
     * 넘칠 때는 아무 말도 하지 않는다 — 한쪽 방향으로만 트는 검사가 오탐이 훨씬 적다.
     *
     * <p>실측(2026-08-25 파일럿 5문제)은 전부 정확히 3건이었다. 오답이 셋이니 셋을 짚은 것이고,
     * 지금 잘 나오는 것을 <b>재서 유지</b>하는 것이 이 검사의 목적이다 — 해설 400~700자를
     * 적어 두고도 359~395자로 나오던 일을 겪은 뒤로 이 저장소가 택한 방식이다.
     */
    private static final Pattern QUOTED_CHOICE_REFERENCE =
            Pattern.compile("[\"“][^\"“”]{5,}?[\"”]|['']?'[^']{5,}?'");

    /**
     * 항목 하나의 품질 경고를 모두 모아 돌려준다. 없으면 빈 목록.
     *
     * <p>{@link #defectOf}가 하나만 돌려주는 것과 달리 여기는 목록이다 — 규약 위반은 하나만
     * 있어도 그 항목을 버리므로 첫 번째면 충분하지만, 경고는 사람이 <b>한 번에 다 보고</b>
     * 고치는 편이 왕복이 적다({@code DocumentDraftValidator.validate}와 같은 판단).
     *
     * @param difficulty        난이도. {@code null}이면 난이도별 검사(지문 길이)를 건너뛴다 —
     *                          관리자 화면의 직접 생성처럼 난이도를 알 수 없는 경로를 위한 여지
     * @param hasSourceDocument 근거 문서를 보고 만든 문제인지(2026-08-25 추가). 해설이 "다시 읽을 절"을
     *                          가리켜야 하는지가 여기에 달렸다 — 근거 없이 만든 문제는 가리킬 곳이 없다
     */
    public static List<String> qualityWarningsOf(GeneratedProblemItem item, Difficulty difficulty,
                                                 boolean hasSourceDocument) {
        List<String> warnings = new ArrayList<>();

        // 제목 — 없어도 퀴즈는 성립하므로 defectOf가 아니라 여기서 본다(화면이 지문으로 대신한다).
        // 다만 조용히 넘기면 제목 없는 문제가 쌓이고, 그러면 목록이 지문 조각으로 채워져
        // 이 컬럼을 만든 이유가 사라진다. 물음표로 끝나는 제목을 함께 잡는 이유는 아래 주석 참고.
        String title = item.title();
        if (isBlank(title)) {
            warnings.add("제목 없음");
        } else {
            String trimmed = title.trim();
            if (trimmed.length() > TITLE_MAX) {
                warnings.add("제목이 김 (%d자, 기준 %d자 — 목록에서 잘린다)".formatted(trimmed.length(), TITLE_MAX));
            }
            // 제목이 질문문이면 지문을 한 번 더 쓴 것이라 목록에서 아무것도 더 알려 주지 않는다.
            // "무엇이 원인인가?"가 열 줄 늘어선 목록을 상상하면 된다 — 이름이 아니라 물음이다.
            if (trimmed.endsWith("?")) {
                warnings.add("제목이 물음표로 끝남 (\"%s\" — 제목은 물음이 아니라 이름이다)".formatted(trimmed));
            }
        }

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
            // 보기 번호 지칭은 2026-08-25에 defectOf(차단)로 올라갔다 — 여기서 다시 세지 않는다.
            // 두 곳에서 보면 차단으로 버려진 항목이 경고 목록에도 뜨거나, 문구가 갈라진다.

            // 오답을 몇 개나 짚었는가 — 객관식에만 해당한다. 대리 지표라 <모자랄 때만> 말한다
            // (자세한 배경은 QUOTED_CHOICE_REFERENCE).
            List<GeneratedProblemItem.GeneratedChoice> mc = item.choices();
            if (mc != null && mc.size() >= MIN_CHOICES) {
                long wrongCount = mc.stream().filter(c -> !c.correct()).count();
                long quoted = QUOTED_CHOICE_REFERENCE.matcher(explanation).results().count();
                if (quoted < wrongCount) {
                    warnings.add("해설이 짚은 오답이 적음 (인용 %d건, 오답 %d개 — 오답마다 어떤 오해인지 밝혀야 한다)"
                            .formatted(quoted, wrongCount));
                }
            }

            // 근거 문서로 돌아갈 한 줄이 있는가 — 문서를 근거로 만든 문제에만 해당한다.
            // 학습자가 틀린 뒤 돌아갈 유일한 입구라, 빠지면 해설이 그 자리에서 끝나 버린다.
            if (hasSourceDocument && contextOf(DOCUMENT_SECTION_HINT, explanation) == null) {
                warnings.add("해설에 다시 읽을 문서 절이 없음 (틀린 학습자가 돌아갈 곳이 사라진다)");
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
            // 중급 — 2026-08-25에 상한만 보던 것을 유형별로 갈랐다.
            //
            // 전에는 상한(250) 하나였는데, 실측 다섯이 118~173자로 상한 근처에도 안 갔다.
            // 즉 상한은 아무 일도 하지 않고 있었고, 정작 문제는 "짧아서 무엇을 묻는지 흐리다"였다.
            // 그래서 상한을 350으로 올리고 <상황형에만 하한 150>을 걸었다.
            // 나머지 네 형태(비교·인과·판정·순서)는 짧은 것이 정상이라 하한을 걸지 않는다 —
            // 걸면 짧아도 되는 문제에 군더더기를 붙이게 만든다(SITUATION_QUESTION_MIN 주석).
            // 고급에 열지 않은 형태를 골랐는가 — 2026-08-25. 항목 하나만 봐도 알 수 있어
            // batchWarningsOf가 아니라 여기 있다(쏠림과 달리 개수와 무관한 위반이다).
            if (difficulty == Difficulty.ADVANCED && item.questionKind() != null
                    && !ADVANCED_KINDS.contains(item.questionKind())) {
                warnings.add("고급에 열지 않은 형태 (%s — 판정형은 중급과 구별이 안 되고, 순서형은 넷 다 그럴듯할 수 없다)"
                        .formatted(item.questionKind().getLabel()));
            }

            if (difficulty == Difficulty.INTERMEDIATE) {
                int qlen = question.trim().length();
                if (qlen > INTERMEDIATE_QUESTION_MAX) {
                    warnings.add("중급 지문이 김 (%d자, 기준 %d자 — 길면 무엇을 묻는지가 흐려진다)"
                            .formatted(qlen, INTERMEDIATE_QUESTION_MAX));
                } else if (item.questionKind() == QuestionKind.SITUATION && qlen < SITUATION_QUESTION_MIN) {
                    // 유형이 null이면(옛 초안, 테스트) 조용히 넘어간다 — 유형을 도입하기 전에
                    // 만들어진 초안이 갑자기 경고를 달고 나오면 검수자가 경고를 안 보게 된다.
                    warnings.add("상황형 지문이 짧음 (%d자, 기준 %d자 — 기능 이름과 어긋난 증상을 숫자로 적었는지 보라)"
                            .formatted(qlen, SITUATION_QUESTION_MIN));
                }
            }
        }

        // 보기 개수 — 차단하지 않고 알리기만 한다. 차단하지 않는 이유는 MIN_CHOICES 주석 참고
        // (보기가 3개여도 퀴즈로는 성립하고, 4개를 강제하면 멀쩡한 문제가 버려진다).
        // 그런데 그 주석은 "개수 어긋남은 검수자가 눈으로 볼 몫"이라고 해 놓고 정작 알려 주는
        // 장치가 없었다 — 2026-08-18에 보기 5개짜리가 조용히 통과했다. 해설 길이도 지문 길이도
        // 세면서 보기 개수만 안 센 것은 빠뜨린 것이지 의도가 아니다.
        List<GeneratedProblemItem.GeneratedChoice> choices = item.choices();
        if (choices != null && choices.size() > EXPECTED_CHOICES) {
            warnings.add("보기가 %d개 (기준 %d개 — 프롬프트는 정확히 %d개를 요구한다)"
                    .formatted(choices.size(), EXPECTED_CHOICES, EXPECTED_CHOICES));
        }

        String lengthBias = choiceLengthBiasOf(item);
        if (lengthBias != null) {
            warnings.add(lengthBias);
        }

        String markdown = markdownTraceOf(item);
        if (markdown != null) {
            warnings.add(markdown);
        }
        return warnings;
    }

    /**
     * 항목 하나가 아니라 <b>배치 전체</b>를 보고 내는 경고 — 2026-08-25 신설. 없으면 빈 목록.
     *
     * <p>{@link #qualityWarningsOf}는 문제 하나만 본다. 그런데 유형 쏠림은 한 문제만 봐서는
     * 알 수 없다 — 비교형 하나는 아무 문제도 아니고, 다섯 중 다섯이 비교형인 것이 문제다.
     * 그래서 검사를 따로 뒀다.
     *
     * <p><b>중급에만 적용한다.</b> 초급은 정의를 묻는 자리라 형태를 나눌 것이 없고, 고급은
     * 정의상 언제나 상황형이다({@link QuestionKind} 주석). 다른 난이도에 이 경고를 내면
     * 매번 울리는 경고가 되고, 그러면 아무도 안 본다.
     *
     * <p><b>유형을 아무도 선언하지 않았으면 조용히 넘어간다.</b> 유형을 도입하기 전에 만들어진
     * 배치가 통째로 경고를 다는 것을 막는다 — 지금 와서 알려 줘 봐야 고칠 방법이 없다.
     *
     * @param items      한 번의 생성으로 나온 문제들. 규약 위반으로 버려진 것은 빼고 넘긴다
     * @param difficulty 그 배치의 난이도
     */
    public static List<String> batchWarningsOf(List<GeneratedProblemItem> items, Difficulty difficulty) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<String> warnings = new ArrayList<>();

        // 유형 쏠림 — 중급에만 해당한다. 초급은 정의를 묻는 자리라 형태를 나눌 것이 없고,
        // 고급은 정의상 언제나 상황형이라 상한을 걸면 매번 울린다.
        if (difficulty == Difficulty.INTERMEDIATE
                && items.stream().anyMatch(i -> i.questionKind() != null)) {
            long situations = items.stream().filter(i -> i.questionKind() == QuestionKind.SITUATION).count();
            if (situations > SITUATION_MAX_PER_BATCH) {
                warnings.add("상황 적용형이 %d개 (상한 %d개 — 나머지는 비교·인과·판정·순서로 채운다)"
                        .formatted(situations, SITUATION_MAX_PER_BATCH));
            }
        }

        // 마지막 물음이 겹치는가 — 난이도와 무관하다(초급도 "~은?"이 반복될 수 있다).
        String repeated = repeatedQuestionTailOf(items);
        if (repeated != null) {
            warnings.add("마지막 물음이 겹침 (\"%s\" — 문형이 같으면 무엇을 묻는지가 아니라 형식이 외워진다)"
                    .formatted(repeated));
        }
        return warnings;
    }

    /**
     * 배치 안에서 <b>두 번 이상 나온 마지막 물음</b>. 없으면 {@code null}.
     *
     * <p><b>왜 이걸 재나.</b> 2026-08-25 실측에서 SYSTEM_DESIGN 중급 6문제 중 다섯이
     * "~가장 적절한 것은?"으로 끝났다. 반면 NETWORK 5문제는 "이 차이를 가장 잘 설명한 것은?",
     * "가장 먼저 할 확인은?", "가장 적절한 조치는?"처럼 다섯이 전부 달랐다. 같은 상황형인데
     * 한쪽만 쏠렸다는 것은 <b>형태가 아니라 문형이 문제</b>라는 뜻이다.
     *
     * <p>문형이 같으면 학습자가 개념이 아니라 <b>형식을 외운다</b>. "원인으로 가장 적절한 것은?"이
     * 열 번 나오면 지문을 대충 읽고 정답 냄새가 나는 보기를 고르는 요령이 생긴다.
     *
     * <p><b>마지막 물음만 재는 이유</b>: 지문 첫 문장의 "틀"도 겹쳤지만({@code [업종] + ○○ 상세})
     * 그건 기계가 재기 어렵다 — 업종이 매번 달라 문자열로는 안 겹치고, 구조가 같다는 것을
     * 알아보려면 자연어를 분석해야 한다. 마지막 물음은 <b>문자열이 실제로 같아서</b> 셀 수 있다.
     * 첫 문장 쪽은 프롬프트의 (X)/(O) 대비에 맡긴다 — 이 저장소에서 실물 예시는 잘 지켜졌다.
     *
     * <p>공백만 정규화하고 그 밖은 그대로 본다. 조사나 어미까지 정규화하면 "무엇이 겹쳤는지"를
     * 사람에게 보여 줄 수 없고, 정규화가 과하면 다른 물음까지 같은 것으로 묶여 헛울린다.
     *
     * <p><b>알려진 한계 — 한 문장짜리 지문은 접미사가 같아도 안 걸린다.</b> 잘라 낼 문장 경계가
     * 없어 지문 전체가 비교 대상이 되기 때문이다. "핸드셰이크의 정의로 옳은 것은?"과
     * "TIME_WAIT의 정의로 옳은 것은?"은 통과한다. <b>일부러 이렇게 뒀다</b> — 접미사로 비교하면
     * 초급이 매번 걸리는데, 초급에서 "정의로 옳은 것은?"이 반복되는 것은 정상이다(같은 것을
     * 묻는 자리라 형식이 같은 편이 낫다). 잡으려는 것은 중급·고급에서 <b>상황 한 문단을 써 놓고
     * 물음만 복사하는</b> 경우이고, 그쪽은 앞 문장이 있어 마지막 물음이 깨끗하게 잘린다.
     */
    private static String repeatedQuestionTailOf(List<GeneratedProblemItem> items) {
        List<String> tails = new ArrayList<>();
        for (GeneratedProblemItem item : items) {
            String tail = questionTailOf(item.question());
            if (tail != null) {
                tails.add(tail);
            }
        }
        for (int i = 0; i < tails.size(); i++) {
            for (int j = i + 1; j < tails.size(); j++) {
                String shared = sharedTailOf(tails.get(i), tails.get(j));
                if (shared != null) {
                    return shared;
                }
            }
        }
        return null;
    }

    /**
     * 두 물음이 <b>사실상 같은 물음</b>이면 겹치는 부분을, 아니면 {@code null}.
     *
     * <h2>왜 완전 일치로는 부족했나 — 2026-08-25 실물</h2>
     *
     * <p>처음에는 마지막 물음이 <b>글자까지 같을 때만</b> 잡았다. 그런데 고급 실물에서 이런 짝이 나왔다:
     * <pre>
     *   #921  가장 적절한 조치는?
     *   #926  이 상황에서 가장 적절한 조치는?
     * </pre>
     * 문자열로는 다르지만 학습자에게는 같은 물음이다. 앞에 말 몇 개를 붙이는 것만으로
     * 검사를 빠져나가면, 있으나 마나 한 검사가 된다.
     *
     * <p><b>왜 "끝 N글자 비교"로 하지 않았나.</b> 그게 더 간단하지만 <b>초급을 매번 잡는다</b> —
     * "핸드셰이크의 정의로 옳은 것은?"과 "TIME_WAIT의 정의로 옳은 것은?"은 끝 10글자가 같다.
     * 그런데 초급에서 그 반복은 정상이다(같은 것을 묻는 자리라 형식이 같은 편이 낫다).
     * 그래서 <b>한쪽이 다른 쪽의 접미사인지</b>만 본다. 위 초급 두 물음은 서로 접미사가 아니라
     * 통과하고, {@code #921}/{@code #926}처럼 <b>앞에 말만 덧붙인</b> 경우는 잡힌다.
     * 잡으려던 것이 정확히 후자다.
     *
     * <p>{@link #SHARED_TAIL_MIN}으로 짧은 쪽에 하한을 두는 이유: "것은?" 같은 조각이 우연히
     * 접미사가 되는 것까지 세면 헛울린다. 물음 하나를 이룰 만한 길이는 돼야 한다.
     */
    private static String sharedTailOf(String a, String b) {
        String shorter = a.length() <= b.length() ? a : b;
        String longer = a.length() <= b.length() ? b : a;
        if (shorter.length() < SHARED_TAIL_MIN) {
            return null;
        }
        return longer.endsWith(shorter) ? shorter : null;
    }

    /**
     * 두 물음이 겹쳤다고 보려면 짧은 쪽이 적어도 이만큼은 돼야 한다.
     *
     * <p>8자인 근거: 잡으려는 실물이 "가장 적절한 조치는?"(11자)이고, 걸리면 곤란한 것이
     * "옳은 것은?"(6자)·"것은?"(4자)처럼 어느 물음에나 붙는 꼬리다. 그 사이에 선을 긋는다.
     */
    private static final int SHARED_TAIL_MIN = 8;

    /** 지문의 마지막 물음 문장(물음표로 끝나는 마지막 조각). 물음표가 없으면 {@code null}. */
    private static String questionTailOf(String question) {
        if (isBlank(question)) {
            return null;
        }
        String flat = question.replaceAll("\\s+", " ").trim();
        if (!flat.endsWith("?")) {
            return null;
        }
        // 마지막 문장 경계부터 자른다. 문장 부호가 없으면 지문 전체가 한 문장이라는 뜻이고,
        // 그때는 통째로 쓴다(초급 지문이 대개 그렇다).
        int cut = Math.max(flat.lastIndexOf('.'), Math.max(flat.lastIndexOf('!'), flat.lastIndexOf('。')));
        return flat.substring(cut + 1).trim();
    }

    /**
     * 항목 하나의 검사 결과를 검수 화면이 쓰는 모양으로 돌려준다 — 2026-08-25 신설.
     *
     * <p><b>왜 이 다리가 필요한가.</b> 이 클래스는 원래 배치(CLI)가 콘솔에 찍으려고 만든 것이라
     * 결과가 {@code String}이다. 그런데 같은 검사를 검수 화면에도 띄우게 되면서
     * 심각도가 필요해졌다 — 화면은 차단과 경고를 <b>다른 모양으로</b> 그린다
     * ("둘을 같은 모양으로 보여주면 빨간 게 늘 있으니 그러려니 하게 된다", {@code llm.html}).
     *
     * <p>{@link #defectOf}를 여기 섞지 않은 이유: 규약 위반은 저장 자체가 안 되므로
     * 화면에 뜰 일이 없다. 문서 쪽은 초안을 일단 저장하고 승인 때 막지만, 문제 쪽은
     * 애초에 버린다 — 그 차이를 이 메서드가 지우면 "차단인데 왜 목록에 없지"가 된다.
     */
    public static List<DraftCheck> checksOf(GeneratedProblemItem item, Difficulty difficulty,
                                            boolean hasSourceDocument) {
        return qualityWarningsOf(item, difficulty, hasSourceDocument).stream()
                .map(DraftCheck::warning)
                .toList();
    }

    /**
     * 정답이 가장 긴 보기이면서 길이 편차까지 큰가 — 자세한 배경은 {@link #CHOICE_LENGTH_RATIO}.
     * 아니면 {@code null}.
     */
    private static String choiceLengthBiasOf(GeneratedProblemItem item) {
        List<GeneratedProblemItem.GeneratedChoice> choices = item.choices();
        if (choices == null || choices.size() < MIN_CHOICES) {
            return null; // 객관식이 아니거나 이미 규약 위반 — defectOf가 볼 몫이다
        }

        int longest = 0;
        int shortest = Integer.MAX_VALUE;
        int correct = -1;
        for (GeneratedProblemItem.GeneratedChoice choice : choices) {
            int length = choice.text() == null ? 0 : choice.text().trim().length();
            longest = Math.max(longest, length);
            shortest = Math.min(shortest, length);
            if (choice.correct()) {
                correct = length;
            }
        }
        if (shortest <= 0 || correct != longest) {
            return null;
        }

        double ratio = (double) longest / shortest;
        if (ratio <= CHOICE_LENGTH_RATIO) {
            return null;
        }
        // 소수 둘째 자리까지 보여 준다. 한 자리면 86/57=1.508이 "1.5배 — 기준 1.5배"로 찍혀,
        // 왜 걸렸는지 알 수 없는 경고가 된다(실물에서 실제로 그렇게 나왔다).
        return "정답이 가장 긴 보기 (%d자 vs 최단 %d자, %.2f배 — 기준 %.1f배 이하)"
                .formatted(longest, shortest, ratio, CHOICE_LENGTH_RATIO);
    }

    /** 화면에 글자로 보일 마크다운이 섞였는가 — 자세한 배경은 {@link #MARKDOWN_SYNTAX}. */
    private static String markdownTraceOf(GeneratedProblemItem item) {
        String where = null;
        if (!isBlank(item.question()) && MARKDOWN_SYNTAX.matcher(item.question()).find()) {
            where = "지문";
        } else if (!isBlank(item.explanation()) && MARKDOWN_SYNTAX.matcher(item.explanation()).find()) {
            where = "해설";
        } else if (item.choices() != null && item.choices().stream()
                .anyMatch(c -> c.text() != null && MARKDOWN_SYNTAX.matcher(c.text()).find())) {
            where = "보기";
        }
        return where == null ? null
                : "%s에 마크다운이 섞임 (백틱·별표는 화면에 글자로 나온다)".formatted(where);
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
        // 빈 값에는 걸릴 것이 없다. 호출부가 늘 !isBlank로 감싸 왔는데, 2026-08-25에 차단 검사가
        // defectOf로 옮겨 오면서 <해설이 빈 채로> 들어오는 경로가 생겼다(해설 없음은 차단이 아니다).
        // 호출부마다 감싸는 대신 여기서 막는다 — 검사 하나 늘 때마다 감쌀 곳이 늘어나는 구조가
        // 결국 한 곳을 빠뜨린다.
        if (isBlank(text)) {
            return null;
        }
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
