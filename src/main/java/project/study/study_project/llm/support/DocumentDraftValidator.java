package project.study.study_project.llm.support;

import project.study.study_project.llm.client.ClaudeDocumentGenerator;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 문서 초안 자동 검증 — 사람이 읽기 전에 기계가 먼저 훑는다. docs/15.
 *
 * <p><b>왜 필요한가.</b> LLM이 쓴 문서는 3,000자가 넘고 문장이 매끄러워서, 검수자가 죽 읽다 보면
 * "제목이 본문과 다르다" 같은 <b>형식 결함</b>을 그냥 지나친다. 내용의 옳고 그름은 사람만
 * 판단할 수 있지만 형식은 기계가 훨씬 잘 본다. 그래서 사람은 내용에만 집중하게 하는 것이 목표다.
 *
 * <p><b>여기 있는 규칙은 전부 실물에서 나왔다</b>(2026-08-11·12 생성분 2편을 읽고 도출):
 * <ol>
 *   <li>{@code title} 필드와 본문 {@code # H1}이 어긋났다 — 두 값이 따로 생성되므로 상시 위험
 *   <li>분량이 매번 30% 넘게 초과됐다 — 프롬프트 문구로는 못 잡는다는 것이 확인됨
 *   <li>XSS 문서 본문에 {@code <script>}가 <b>정상적으로</b> 들어 있었다 — 코드블록 안이라 안전
 * </ol>
 *
 * <p><b>스프링 빈이 아닌 static 유틸인 이유</b>: 입력(제목·slug·본문)만으로 결과가 정해지는
 * 순수 함수라 주입받을 상태가 없다. 빈으로 만들면 테스트마다 컨텍스트를 띄워야 하는데,
 * 이 클래스야말로 <b>입력을 잔뜩 바꿔 가며 빠르게 돌려야 하는</b> 종류다.
 */
public final class DocumentDraftValidator {

    /**
     * 권장 분량 상한(경고). 프롬프트가 요구하는 "9,000~12,000자"의 위쪽 끝.
     * 넘겼다고 막지 않는 이유는 {@link DocumentCheck.Severity} 주석 참고 — 억지로 줄이면
     * 용어 설명과 면접 질문부터 잘려 나가는데, 그 두 절이 분량을 올린 목적이었다.
     *
     * <p><b>이 값은 프롬프트의 분량 지시를 따라간다.</b> 2026-08-14에 고급 재료를 늘리려고
     * 지시를 3,500자에서 5,500자로 올리면서 여기도 함께 올렸고, 2026-08-15에
     * {@code ## 실무에서는 이렇게 쓴다} 절을 신설하면서 그 절의 몫만큼 5,800자로 올렸고,
     * 같은 날 프롬프트를 다시 다듬으면서 6,000자가 됐고, 2026-08-18에 <b>용어 정의를 넣을
     * 자리</b>를 만들려고 6,800자가 됐다.
     * 한쪽만 고치면 <b>지시대로 쓴 문서가 전부 경고를 달고 나오고</b>, 경고가 매번 뜨면
     * 사람이 경고 자체를 안 보게 된다.
     *
     * <p><b>2026-08-18에 800자를 올린 이유는 다른 때와 다르다.</b> 앞선 인상은 전부 절을
     * 신설한 대가였는데, 이번에는 절이 늘지 않았다. 8/15 실물이 상한에 17자를 남기고 나온
     * 탓에 <b>모델이 용어 정의를 넣을 곳이 없었다</b>는 것이 원인이었다 — 어려운 용어 아홉 개가
     * 정의 없이 지나갔다. 지면을 주지 않고 "정의를 빼먹지 마라"만 세게 적는 것은 소용이 없다.
     *
     * <p><b>하드 상한과의 배율도 같이 본다.</b> 지금은 8,800 / 6,800 = 1.29배다. 권장치를
     * 계속 올리면서 하드 상한을 그대로 두면 언젠가 둘이 붙어, 경고 없이 곧바로 차단되는
     * 구간이 생긴다. 그때는 하드 상한도 함께 올려야 한다(이번이 그런 경우였다 —
     * 7,700을 두면 배율이 1.13으로 좁아져 경고 구간이 사실상 사라진다).
     *
     * <p>{@code public}인 이유: 다른 패키지의 테스트({@code LlmDocumentServiceTest})가 "경고만
     * 있는 문서"를 만들 때 이 값을 기준으로 삼는다. 거기서 숫자를 따로 적어 두면 이 값을 올린 날
     * <b>그 테스트가 조용히 무의미해진다</b> — 경고 없는 문서로 "경고만 있으면 승인된다"를
     * 검사하게 되고, 통과하므로 아무도 모른다. {@link ClaudeDocumentGenerator#REQUIRED_SECTIONS}를
     * 공개한 것과 같은 이유다.
     *
     * <p><b>2026-08-23에 6,800 → 12,000으로 올렸다.</b> 지금까지의 인상은 전부 절 하나·자리
     * 하나의 대가였는데 이번에는 목표 자체가 바뀌었다 — 문서를 그대로 기술 블로그에 올릴 수
     * 있는 완결된 글로 만들고, 기본 개념을 처음부터 설명하는 절({@code ## 바탕이 되는 개념})을
     * 앞에 새로 뒀다. 늘린 5,200자의 갈 곳은 프롬프트 쪽 주석의 배정표에 적어 두었다.
     * 상한만 올리면 부연으로 찬다는 것을 두 번 겪었으므로 개수 지시를 함께 올렸다.
     */
    public static final int WARN_LENGTH = 12_000;

    /**
     * 하드 상한(차단). 권장치의 약 1.3배.
     *
     * <p>이 선을 넘으면 "길게 잘 썼다"가 아니라 <b>모델이 지시를 통째로 무시했거나 같은 말을
     * 반복하기 시작했다</b>는 신호로 본다.
     *
     * <p><b>권장치와 함께 올려야 하는 값이다.</b> 전에는 5,000자였는데, 프롬프트가 5,500자까지
     * 쓰라고 하면서 이 값을 그대로 뒀다면 <b>지시를 잘 따른 문서일수록 승인이 막히는</b>
     * 상태가 됐을 것이다. 차단은 조용하지 않지만 원인이 엉뚱한 곳(검수 화면)에서 보여서
     * 프롬프트를 의심하기까지 시간이 걸린다.
     *
     * <p>2026-08-23에 권장치를 12,000으로 올리면서 같은 1.3배를 유지해 15,600으로 옮겼다.
     */
    static final int MAX_LENGTH = 15_600;

    /** {@code AdminDocumentRequest.slug}의 정규식과 같아야 한다 — 다르면 승인 순간 400으로 튕긴다. */
    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    /**
     * 본문 첫 번째 H1({@code # 제목}). {@code ##} 이상은 잡지 않도록 {@code #} 뒤에 공백을 요구한다.
     * {@code (?m)}는 여러 줄 모드 — {@code ^}가 각 줄의 시작에 걸리게 한다.
     */
    private static final Pattern H1_PATTERN = Pattern.compile("(?m)^#\\s+(.+?)\\s*$");

    /** 펜스 코드블록. {@code (?s)}로 줄바꿈까지 포함하고, 최소 일치({@code *?})로 블록을 하나씩 끊는다. */
    private static final Pattern FENCED_CODE = Pattern.compile("(?s)```.*?```");

    /** 인라인 코드. 줄바꿈을 포함하지 않게 막아 두어 백틱 하나가 문서 절반을 삼키는 일을 방지. */
    private static final Pattern INLINE_CODE = Pattern.compile("`[^`\\n]*`");

    /**
     * HTML 태그. 태그 이름 뒤에 <b>공백·{@code /}·{@code >} 중 하나</b>를 요구하는 것이 핵심이다.
     *
     * <p>그냥 {@code <[a-zA-Z][^>]*>}로 쓰면 본문의 부등호를 태그로 오인한다 —
     * 예를 들어 "if (a&lt;b) return c&gt;d"에서 {@code <b) return c>}가 통째로 걸린다.
     * 실제 태그는 이름 바로 뒤가 반드시 공백(속성)이거나 닫는 괄호라 이 조건으로 갈린다.
     */
    private static final Pattern HTML_TAG = Pattern.compile("</?[a-zA-Z][a-zA-Z0-9-]*(\\s[^>]*)?/?>");

    /** 본문 {@code ## } 제목. {@code ###} 이상은 잡지 않도록 {@code ##} 뒤에 공백을 요구한다. */
    private static final Pattern H2_PATTERN = Pattern.compile("(?m)^##\\s+(.+?)\\s*$");

    /** 중급 재료가 나오는 소제목. 프롬프트가 {@code ### }로 못 박은 형태. */
    private static final Pattern DESIGN_RATIONALE_HEADING =
            Pattern.compile("(?m)^###\\s+왜 이렇게 설계됐는가");

    /** 같은 소제목의 <b>옛 형식</b>(굵은 글씨). 고치는 법을 알려 주려고 함께 센다. */
    private static final Pattern DESIGN_RATIONALE_BOLD =
            Pattern.compile("(?m)^\\*\\*왜 이렇게 설계됐는가\\*\\*");

    /** 하이픈으로 시작하는 용어 정의 줄 — 프롬프트가 요구하는 형태. */
    private static final Pattern TERM_BULLET = Pattern.compile("(?m)^-\\s+\\*\\*");

    /** 하이픈 없이 굵은 글씨로만 시작하는 용어 정의 줄 — 화면에서 한 문단으로 뭉치는 형태. */
    private static final Pattern BOLD_TERM_LINE = Pattern.compile("(?m)^\\*\\*[^*]+\\*\\*\\s*[—-]");

    /**
     * {@code ## 언제 깨지는가}의 항목 한 개. 형식이 프롬프트에 지정돼 있지 않아
     * 실물에서 나온 형태({@code **1. 제목**})와 있을 법한 형태들({@code ### }, {@code - },
     * {@code 1. })을 모두 항목으로 인정한다 — 자세한 이유는 {@link #checkFailureModeCount} 참고.
     *
     * <p><b>맨 하이픈을 포함한 것이 핵심이다.</b> 처음에는 {@code - **}(하이픈+굵게)만
     * 인정했는데, 그러면 {@code - 깨지는 조건 하나} 같은 <b>평범한 목록으로 잘 쓴 문서에
     * 경고가 뜬다</b>. 실제로 이 저장소의 테스트 도우미 문서가 그 형태였다.
     *
     * <p>대가는 항목 밑의 하위 목록까지 항목으로 세는 것이다. 그래서 개수가 부풀고,
     * 진짜 부족한 문서를 놓칠 수 있다. 그 방향을 택한 이유는 <b>오탐이 미탐보다 비싸기</b>
     * 때문이다 — 헛울리는 경고는 사람이 경고 전체를 안 보게 만들지만, 재료가 모자란 것은
     * 다음 날 수확량 점검이 한 번 더 잡는다({@code DraftGeneratorCli.reportYield}).
     *
     * <p><b>2026-08-18: 번호 없는 굵은 글씨를 더했다.</b> 같은 날 다시 뽑은 문서가 항목을
     * {@code **쓰기 스큐**}처럼 <b>번호 없이</b> 썼다. 항목이 7개인데 검증기는 1개로 세고
     * "기준 5개 이상"을 경고했다 — 잘 쓴 문서에 헛울린 것이라 이 주석이 말하는 실패 그대로다.
     * 원인은 {@code \*\*\d+\.}에 박아 둔 <b>{@code \d+\.}</b>였다. 프롬프트는 "각각 이름을
     * 붙이고"라고만 할 뿐 번호를 요구한 적이 없으니, 번호를 세는 쪽이 처음부터 틀렸다.
     * 숫자 부분을 떼어 굵은 글씨로 시작하는 줄을 모두 인정한다.
     *
     * <p>그 결과 {@code ### 흔한 오해}의 항목({@code **"...이다"**})까지 함께 세어 개수가 더
     * 부푼다. 알고 받아들인다 — 위 문단이 정한 방향(오탐 &gt; 미탐)과 같은 판단이고,
     * 여기서 형식을 하나로 좁히면 <b>다음 문서가 또 다른 형식으로 와서 같은 사고가 반복된다</b>.
     */
    private static final Pattern FAILURE_MODE_ITEM =
            Pattern.compile("(?m)^(\\*\\*|###\\s+|-\\s+|\\d+\\.\\s+)");

    /** 본론 끝의 용어 표 소제목 — 프롬프트가 못 박은 형태. */
    private static final Pattern GLOSSARY_HEADING = Pattern.compile("(?m)^###\\s+용어 한눈에");

    /**
     * 정의가 있어야 하는 용어 후보 — <b>넓게 잡지 않는다.</b>
     *
     * <p>이상적으로는 "문서에 나오는 모든 전문 용어"를 세고 싶지만, 한국어 문장에서 용어의
     * 경계를 정규식으로 자르면 오탐이 쏟아진다. 예를 들어 {@code ([가-힣]{2,6})\s*락}으로
     * 잡으면 "그래서 따로 락을 건다"에서 <b>"따로 락"</b>이 용어로 걸린다. 이 클래스의 오랜
     * 원칙대로 <b>오탐이 미탐보다 비싸다</b> — 헛울리는 경고는 사람이 경고 전체를 안 보게 만든다.
     *
     * <p>그래서 <b>2026-08-18에 실제로 새어 나간 두 계열만</b> 본다.
     * <ol>
     *   <li>잠금 계열 — 수식어를 목록으로 못 박는다. 실물에서 정의 없이 지나간 것이
     *       공유 락·배타 락·낙관적 잠금·비관적 잠금·갭 락 다섯이었다.
     *   <li>대문자 SQL 구문 — 한글이 섞이지 않아 경계가 분명하다. 실물의
     *       {@code SELECT ... FOR UPDATE}가 세 번 나오도록 정의가 없었다.
     * </ol>
     *
     * <p>다른 분야(네트워크·보안)의 용어는 이 목록으로 잡히지 않는다. <b>알고 그렇게 뒀다.</b>
     * 여기서 잡는 것은 "모든 미정의 용어"가 아니라 <b>사고가 났던 자리</b>이고, 같은 사고가
     * 다른 분야에서 나면 그때 그 계열을 한 줄 더한다. 미리 넓히면 지금 당장 오탐이 난다.
     */
    private static final List<Pattern> TERM_CANDIDATES = List.of(
            Pattern.compile("(?:공유|배타|낙관적|비관적|명시적|묵시적|갭|넥스트키|행|테이블|범위|분산)\\s*(?:락|잠금)"),
            // 대문자 두 낱말 이상이 이어지는 구문. "..."을 낱말로 인정해 SELECT ... FOR UPDATE를 통째로 잡는다.
            Pattern.compile("\\b[A-Z]{3,}(?:\\s+(?:\\.\\.\\.|[A-Z]{2,}))+")
    );

    /** 경고 메시지에 나열할 용어 개수 상한 — 다 적으면 메시지가 화면을 넘긴다. */
    private static final int MAX_LISTED_TERMS = 6;

    /** 본론 섹션 최소 개수 — 프롬프트는 2~3개를 요구한다. */
    private static final int MIN_BODY_SECTIONS = 2;

    /** {@code ## 언제 깨지는가} 항목 최소 개수 — 프롬프트의 "7가지 이상"과 같아야 한다. */
    private static final int MIN_FAILURE_MODES = 7;

    /**
     * 펜스 코드블록 최소 개수 — 프롬프트 {@code [코드 예제]}의 "2~4개"와 짝이다.
     *
     * <p><b>2026-08-23에 코드 예제를 허용하면서 같이 넣었다.</b> 그 전까지 프롬프트는
     * "긴 예제는 쓰지 마라"였고, 허용으로 뒤집는 것만으로는 예제가 나오지 않는다 —
     * 모델에게 코드를 빼는 쪽이 언제나 더 안전한 선택이기 때문이다(틀릴 위험이 없다).
     * 이 저장소가 두 번 확인한 대로 <b>개수를 박은 지시만 지켜지고</b>, 그 개수는 세는 곳이
     * 있어야 유지된다. 없으면 몇 주 뒤 조용히 코드 없는 문서로 돌아간다.
     *
     * <p>상한(4개)은 세지 않는다. 넘쳐서 생기는 문제는 분량 경고가 이미 잡고,
     * 여기서 두 방향을 다 막으면 "코드가 잘 어울리는 주제"에서 헛경고가 난다.
     */
    private static final int MIN_CODE_BLOCKS = 2;

    /**
     * {@code ## 바탕이 되는 개념} 절의 최소 분량 — 프롬프트는 1,500~2,500자를 요구한다.
     *
     * <p><b>기준을 1,000자로 낮춰 잡은 이유</b>: 이 검사가 잡으려는 것은 "조금 짧다"가 아니라
     * <b>절이 이름만 남고 비었다</b>는 상태다. 2026-08-23 이전 문서들이 정확히 그랬다 —
     * OSI 7계층을 용어 목록 한 줄로 처리하고 넘어갔다. 1,500에 딱 맞춰 경고하면 1,400자짜리
     * 멀쩡한 절에도 울리고, 헛울리는 경고는 사람이 경고 전체를 안 보게 만든다.
     */
    private static final int MIN_FOUNDATION_LENGTH = 1_000;

    private DocumentDraftValidator() {
    }

    /**
     * 초안 하나를 검증해 발견한 문제를 모두 돌려준다. 문제가 없으면 빈 목록.
     *
     * <p><b>첫 실패에서 멈추지 않는 이유</b>: 검수자는 한 번에 다 보고 판단하고 싶어 한다.
     * "절이 빠졌다"를 고쳐 다시 뽑았더니 이번엔 "분량 초과"가 나오는 식이면 왕복만 늘어난다.
     */
    public static List<DocumentCheck> validate(String title, String slug, String contentMd) {
        List<DocumentCheck> checks = new ArrayList<>();
        String body = contentMd == null ? "" : contentMd;

        // 제목을 찾는 검사들은 <코드블록을 지운 사본>에서 본다. 배경은 maskFencedCode 주석 참고.
        String structure = maskFencedCode(body);

        checkSlug(slug, checks);
        checkTitleMatchesHeading(title, body, structure, checks);
        checkRequiredSections(structure, checks);
        checkDesignRationaleHeading(structure, checks);
        checkTermList(body, structure, checks);
        checkGlossaryTable(structure, checks);
        checkUndefinedTerms(body, checks);
        checkBodySections(body, structure, checks);
        checkFailureModeCount(body, structure, checks);
        checkCodeExamples(body, checks);
        checkFoundationSection(body, structure, checks);
        checkHtmlTags(body, checks);
        checkLength(body, checks);
        return checks;
    }

    /** 승인해도 되는가 — BLOCKING이 하나라도 있으면 안 된다. */
    public static boolean hasBlocking(List<DocumentCheck> checks) {
        return checks.stream().anyMatch(DocumentCheck::isBlocking);
    }

    /* ── 개별 규칙 ───────────────────────────────────────────── */

    /**
     * slug 형식. 어차피 {@code AdminDocumentRequest}의 {@code @Pattern}이 잡지 않느냐 싶지만,
     * <b>잡지 못한다</b>: 승인은 컨트롤러가 아니라 서비스에서 {@code AdminDocumentService.create}를
     * 직접 부르는 경로라 {@code @Valid}가 걸리지 않는다. 여기서 안 보면 그대로 통과한다.
     */
    private static void checkSlug(String slug, List<DocumentCheck> checks) {
        if (slug == null || slug.isBlank()) {
            checks.add(DocumentCheck.blocking("slug가 비어 있습니다."));
        } else if (!SLUG_PATTERN.matcher(slug).matches()) {
            checks.add(DocumentCheck.blocking(
                    "slug 형식이 규칙에 맞지 않습니다(영문 소문자·숫자·하이픈만): " + slug));
        }
    }

    /**
     * {@code title} 필드와 본문 H1이 같은지. <b>실물에서 실제로 어긋났다</b> —
     * title은 "XSS와 CSRF", H1은 "CSRF와 XSS"였다.
     *
     * <p>왜 차단이 아니라 경고인가: 어느 쪽이 맞는지는 사람만 정할 수 있고, 둘 다 틀리지 않았다.
     * 승인 후 문서 수정 화면에서 한쪽을 고치면 되는 정도의 문제라 왕복시킬 이유가 없다.
     *
     * <p>비교 전에 공백을 모두 지우는 이유: 줄바꿈·중복 공백처럼 사람 눈에 같은 차이로
     * 경고가 뜨면 <b>경고를 무시하는 습관</b>이 생긴다. 진짜 다를 때만 울려야 쓸모가 있다.
     */
    private static void checkTitleMatchesHeading(String title, String body, String structure,
                                                 List<DocumentCheck> checks) {
        // 코드블록을 지운 사본에서 찾는다 — 셸 주석("# 개수를 센다")이 제목 행세를 하면
        // "제목이 없습니다"(차단)가 "제목이 다릅니다"(경고)로 내려앉는다(maskFencedCode 주석).
        Matcher m = H1_PATTERN.matcher(structure);
        if (!m.find()) {
            checks.add(DocumentCheck.blocking("본문에 최상위 제목(# ...)이 없습니다."));
            return;
        }
        // 위치는 사본에서 찾았지만 글자는 원문에서 꺼낸다(두 문자열의 길이가 같아 인덱스가 통한다).
        String heading = body.substring(m.start(1), m.end(1));
        if (title == null || !squash(title).equals(squash(heading))) {
            checks.add(DocumentCheck.warning(
                    "제목 필드와 본문 제목이 다릅니다. 필드=\"" + title + "\" / 본문=\"" + heading + "\""));
        }
    }

    /**
     * 필수 절 존재 — 목록의 출처는 생성 프롬프트다(두 곳이 어긋나지 않도록 상수 하나를 공유).
     *
     * @param structure 코드블록을 지운 사본. 원문으로 보면 코드 주석에 적힌 {@code ## 무엇인가}만으로
     *                  절이 있는 것으로 통과한다.
     */
    private static void checkRequiredSections(String structure, List<DocumentCheck> checks) {
        for (String section : ClaudeDocumentGenerator.REQUIRED_SECTIONS) {
            if (!structure.contains(section)) {
                checks.add(DocumentCheck.blocking("필수 절이 없습니다: " + section));
            }
        }
    }

    /* ── 형식 규칙 ───────────────────────────────────────────────
     * 아래 넷은 프롬프트가 "이렇게 써라"라고 시켰지만 검사하는 곳이 없던 것들이다.
     * 2026-08-15 문서 한 편에서 <넷 중 둘이 어긋나 있었고> 승인까지 통과했다.
     *
     * 전부 경고다. 형식이 어긋났다고 문서를 막으면 그 주기 나흘이 통째로 날아가는데,
     * 정작 사람은 검수 화면에서 한 줄 고치면 되는 것들이다. */

    /**
     * {@code ### 왜 이렇게 설계됐는가} 소제목이 <b>정확히 하나</b> 있는지.
     *
     * <p><b>이게 중급 문제의 재료다.</b> 2026-08-15 문서에는 이 소제목이 {@code ###}로
     * 하나도 없었고 대신 {@code **왜 이렇게 설계됐는가**}가 <b>굵은 글씨로 세 번</b> 있었다.
     * 프롬프트가 "굵은 글씨에서 {@code ###}로 올렸다"고 바꿔 놓은 뒤에도 모델이 옛 형식으로
     * 쓴 것인데, 검사가 없어 승인까지 통과했다.
     *
     * <p>그 대가는 하루 뒤에 온다. 중급 날 배치가 이 절을 찾다 못 찾으면 폴백으로 떨어져
     * ({@code DraftGeneratorCli.hasMaterialFor}) 그 주기의 중급이 근거 없이 만들어진다.
     * 여기서 경고를 띄우면 <b>승인 시점에</b> 사람이 굵은 글씨를 {@code ###}로 고칠 수 있다 —
     * 하루 먼저 알아차리는 것이 이 검사의 값어치다.
     *
     * <p>굵은 글씨 형태도 함께 세어 메시지에 담는 이유: "없습니다"만 보면 무엇을 해야 할지
     * 모르지만, "굵은 글씨로 3개 있습니다"까지 보면 <b>고칠 방법이 곧바로 보인다</b>.
     */
    private static void checkDesignRationaleHeading(String structure, List<DocumentCheck> checks) {
        int headings = count(DESIGN_RATIONALE_HEADING, structure);
        if (headings == 1) {
            return;
        }
        if (headings == 0) {
            int bold = count(DESIGN_RATIONALE_BOLD, structure);
            checks.add(DocumentCheck.warning(bold > 0
                    ? "'### 왜 이렇게 설계됐는가' 소제목이 없습니다(굵은 글씨로 %d개 있습니다 — ###로 올리세요). 중급 문제가 이 절을 재료로 씁니다."
                            .formatted(bold)
                    : "'### 왜 이렇게 설계됐는가' 소제목이 없습니다. 중급 문제가 이 절을 재료로 씁니다."));
            return;
        }
        checks.add(DocumentCheck.warning(
                "'### 왜 이렇게 설계됐는가' 소제목이 %d개입니다(문서 전체에 1개). 설계 근거는 본문 문장으로 녹여 쓰세요."
                        .formatted(headings)));
    }

    /**
     * {@code ## 무엇인가} 절의 핵심 용어가 <b>하이픈 목록</b>인지.
     *
     * <p><b>줄만 바꿔서는 안 되는 이유가 마크다운에 있다.</b> 홑줄바꿈은 문단을 끊지 않으므로
     * ({@code marked}의 기본값 {@code breaks:false}) 하이픈 없이 다섯 줄을 쓰면 화면에서
     * 한 문단으로 뭉쳐 나온다. 정의를 맨 앞에 올려 찾기 쉽게 하려던 절이 정작 가장 읽기
     * 어려운 덩어리가 된다 — 실제로 그렇게 나왔다.
     *
     * <p>프롬프트에 두 번 적었는데도 안 지켜졌다(커밋 bafb2e9가 고치려다 실패). 원인은
     * 규칙 옆의 예시에 하이픈이 없어서였고, 그때 <b>예시가 규칙을 이긴다</b>는 것을 배웠다.
     * 프롬프트를 또 고치는 것보다 세어 보는 쪽이 확실하다.
     */
    private static void checkTermList(String body, String structure, List<DocumentCheck> checks) {
        Section section0 = sectionOf(body, structure, "## 무엇인가");
        if (section0 == null || section0.raw().isBlank()) {
            return; // 절 자체가 없으면 checkRequiredSections가 이미 차단으로 잡는다
        }
        // 코드블록 안의 하이픈 목록에 속으면 "용어 목록이 있다"고 잘못 판정한다
        String section = section0.masked();
        if (!TERM_BULLET.matcher(section).find() && BOLD_TERM_LINE.matcher(section).find()) {
            checks.add(DocumentCheck.warning(
                    "'## 무엇인가'의 용어 정의가 하이픈 목록이 아닙니다. "
                            + "줄만 바꾸면 화면에서 한 문단으로 뭉쳐 나옵니다(마크다운의 홑줄바꿈)."));
        }
    }

    /**
     * 본론 끝에 {@code ### 용어 한눈에} 표가 있는지.
     *
     * <p><b>이 표는 뒤쪽 절이 쓸 용어를 미리 올려 두는 자리다.</b> 2026-08-18에 사용자가
     * "문서에 모르는 용어가 설명 없이 나온다"고 지적했고, 실물을 세어 보니 정의된 용어는
     * {@code ## 무엇인가}의 5개뿐이었다. 뒤쪽 절에서 처음 나온 잠금·격리 용어 아홉 개는
     * 정의 없이 지나갔다. 프롬프트에는 이미 "정의 없이 지나가는 용어가 하나도 없어야 한다"가
     * 있었으므로, 문구를 더 세게 적는 것으로는 고쳐지지 않는다는 것이 확인된 셈이다.
     * <b>용어를 둘 자리를 만들어 주는 쪽</b>으로 바꾸고, 그 자리가 생겼는지 여기서 센다.
     *
     * <p>차단이 아니라 경고인 이유는 이 절의 다른 형식 규칙과 같다 — 표 하나가 없다고 나흘치
     * 문서를 버리는 것보다, 검수자가 승인 전에 표를 채워 넣는 편이 싸다.
     */
    private static void checkGlossaryTable(String structure, List<DocumentCheck> checks) {
        if (!GLOSSARY_HEADING.matcher(structure).find()) {
            checks.add(DocumentCheck.warning(
                    "'### 용어 한눈에' 표가 없습니다. 뒤쪽 절에서 처음 나오는 용어를 올려 두는 자리입니다."));
        }
    }

    /**
     * 어려운 용어가 <b>정의 없이</b> 지나가는지 — 초보 학습자가 문서를 놓치는 가장 흔한 이유다.
     *
     * <p><b>이 검사의 한계를 먼저 적는다.</b> 문서에 나오는 모든 전문 용어를 세지 않는다.
     * 셀 수도 없고, 넓게 잡으면 오탐이 경고 전체를 무력하게 만든다({@link #TERM_CANDIDATES}
     * 주석의 "따로 락" 예). 여기서 보는 것은 <b>2026-08-18에 실제로 새어 나간 두 계열</b>뿐이고,
     * 다른 계열에서 같은 사고가 나면 그때 목록을 한 줄 늘린다.
     *
     * <p>그래도 값어치가 있는 이유: 이 두 계열이 <b>고급 문제의 보기에 그대로 실려 나간다</b>.
     * 8/18 문제 5개 중 3개의 보기에 {@code SELECT ... FOR UPDATE}와 낙관적 잠금이 들어 있었는데,
     * 문서 어디에도 그 뜻이 없었다. 문서에서 새면 문제까지 새는 구조라 <b>문서 단계에서</b>
     * 잡는 것이 가장 싸다.
     */
    private static void checkUndefinedTerms(String body, List<DocumentCheck> checks) {
        // LinkedHashSet — 같은 용어가 여러 번 나와도 한 번만 알리고, 문서에 나온 순서를 지킨다.
        // 검수자는 메시지를 들고 문서를 위에서 아래로 훑으므로 순서가 곧 찾는 순서다.
        Set<String> undefined = new LinkedHashSet<>();
        List<String> lines = List.of(body.split("\n", -1));

        for (Pattern candidate : TERM_CANDIDATES) {
            Matcher m = candidate.matcher(body);
            while (m.find()) {
                String term = m.group();
                if (!isDefinedSomewhere(lines, term)) {
                    undefined.add(term);
                }
            }
        }
        if (undefined.isEmpty()) {
            return;
        }

        List<String> listed = undefined.stream().limit(MAX_LISTED_TERMS).toList();
        String tail = undefined.size() > listed.size()
                ? " 외 %d개".formatted(undefined.size() - listed.size())
                : "";
        checks.add(DocumentCheck.warning(
                "정의 없이 쓰인 용어가 있습니다: %s%s. 그 자리에서 풀거나 '### 용어 한눈에' 표에 올리세요."
                        .formatted(String.join(", ", listed), tail)));
    }

    /**
     * 그 용어를 담은 <b>정의 줄</b>이 문서 어딘가에 있는지.
     *
     * <p>정의 줄로 인정하는 형태는 셋이다 — 표의 행({@code |}로 시작), 하이픈 정의 목록
     * ({@code - **용어** — 뜻.}), 굵은 글씨 정의 줄({@code **용어** — 뜻.}). 마지막 것을
     * 함께 인정하는 이유는 {@link #checkTermList}가 그 형태를 <b>경고하되 허용</b>하기
     * 때문이다. 한쪽이 봐준 형식을 다른 쪽이 "정의가 아니다"라고 하면 같은 결함으로 경고가
     * 두 번 뜨고, 검수자는 무엇을 고쳐야 하는지 헷갈린다.
     *
     * <p>표 행에 줄표를 요구하지 않는 이유: 표는 칸이 뜻을 나누므로 줄표가 필요 없다.
     */
    private static boolean isDefinedSomewhere(List<String> lines, String term) {
        for (String line : lines) {
            String trimmed = line.strip();
            if (!trimmed.contains(term)) {
                continue;
            }
            if (trimmed.startsWith("|")) {
                return true;
            }
            if ((trimmed.startsWith("- **") || trimmed.startsWith("**")) && trimmed.contains("—")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 본론 섹션이 <b>2개 이상</b>인지 — 프롬프트는 2~3개를 요구한다.
     *
     * <p>{@link ClaudeDocumentGenerator#REQUIRED_SECTIONS}에는 본론이 없다. 제목이 주제마다
     * 달라서 목록으로 못 박을 수가 없기 때문인데, 그 결과 <b>본론이 통째로 비어도 통과하는</b>
     * 구멍이 남았다. 이름을 못 박을 수 없으면 <b>개수</b>를 세면 된다 — 필수 절 목록에 없는
     * {@code ## } 제목이 곧 본론이다.
     *
     * <p>본론이 얇으면 초급·중급 재료가 함께 마른다. 초급이 캘 곳은 {@code ## 무엇인가}와
     * 본론의 기본 동작이고, 중급의 설계 판단도 대부분 본론에 녹아 있다.
     */
    private static void checkBodySections(String body, String structure, List<DocumentCheck> checks) {
        List<String> bodySections = new ArrayList<>();
        // 코드블록 안의 "## " 주석을 본론 섹션으로 세면 본론이 빈 문서가 통과한다
        Matcher m = H2_PATTERN.matcher(structure);
        while (m.find()) {
            String heading = "## " + body.substring(m.start(1), m.end(1)).trim();
            if (!ClaudeDocumentGenerator.REQUIRED_SECTIONS.contains(heading)) {
                bodySections.add(heading);
            }
        }
        if (bodySections.size() < MIN_BODY_SECTIONS) {
            checks.add(DocumentCheck.warning(
                    "본론 섹션이 %d개입니다(권장 %d~3개). 본론이 얇으면 초급·중급 재료가 함께 마릅니다."
                            .formatted(bodySections.size(), MIN_BODY_SECTIONS)));
        }
    }

    /**
     * {@code ## 언제 깨지는가}의 항목이 <b>5개 이상</b>인지 — 고급 문제의 재료다.
     *
     * <p>2026-08-14에 고급 날 재료가 말라 5개를 요청해 3개만 받은 적이 있다. 그 뒤 프롬프트에
     * "5가지 이상"을 박았지만 세는 곳은 없었다.
     *
     * <p><b>항목의 형식이 정해져 있지 않아</b> 세는 방법을 넉넉하게 잡았다 — 실물은
     * {@code **1. 쓰기 스큐**} 꼴이었는데 프롬프트는 형식을 지정하지 않으므로 다음 문서는
     * {@code ### }나 {@code - }로 올 수 있다. 셋을 모두 항목으로 센다. 여기서 형식 하나만
     * 인정하면 멀쩡한 문서에 경고가 뜨고, 오탐은 경고를 무력하게 만든다.
     */
    private static void checkFailureModeCount(String body, String structure, List<DocumentCheck> checks) {
        Section section = sectionOf(body, structure, "## 언제 깨지는가");
        if (section == null) {
            return; // 절 자체가 없으면 checkRequiredSections가 차단으로 잡는다
        }
        // 사본으로 센다 — 코드 예제의 "- " 줄까지 항목으로 세면 모자란 문서가 통과한다
        int items = count(FAILURE_MODE_ITEM, section.masked());
        if (items < MIN_FAILURE_MODES) {
            checks.add(DocumentCheck.warning(
                    "'## 언제 깨지는가'의 항목이 %d개입니다(기준 %d개 이상). 고급 문제가 여기를 재료로 씁니다."
                            .formatted(items, MIN_FAILURE_MODES)));
        }
    }

    /**
     * 코드 예제가 <b>2개 이상</b>인지 — 2026-08-23에 블로그 수준 품질을 목표로 삼으면서 넣었다.
     *
     * <p>목표로 제시된 기술 블로그 글에는 Java 예제가 일곱 개 있었다. 우리 문서에는 하나도
     * 없었는데, 프롬프트가 "긴 예제는 쓰지 마라"로 막고 있었기 때문이다. 금지를 풀었지만
     * <b>푸는 것만으로는 나오지 않는다</b> — 모델에게는 코드를 빼는 쪽이 언제나 안전하다.
     *
     * <p>경고인 이유는 이 절의 다른 형식 규칙과 같다. 코드가 하나 모자란다고 나흘치 문서를
     * 버리는 것보다, 검수자가 승인 전에 한 토막 넣는 편이 싸다. 게다가 주제에 따라서는
     * (설계 원칙·방법론) 코드가 억지가 되는 날도 있어 차단으로 두면 그런 날 길이 막힌다.
     */
    private static void checkCodeExamples(String body, List<DocumentCheck> checks) {
        int blocks = count(FENCED_CODE, body);
        if (blocks < MIN_CODE_BLOCKS) {
            checks.add(DocumentCheck.warning(
                    "코드 예제가 %d개입니다(기준 %d개 이상). 코드가 없으면 블로그로 옮겼을 때 밀도가 확 떨어집니다."
                            .formatted(blocks, MIN_CODE_BLOCKS)));
        }
    }

    /**
     * {@code ## 바탕이 되는 개념} 절이 <b>이름만 남고 비지</b> 않았는지.
     *
     * <p><b>이 절이 2026-08-23 개정의 전부다.</b> 사용자가 8/23 실물을 읽고 "실무 상황만 있고
     * OSI 7계층 자체는 설명하지 않는다"고 지적했다. 원인은 바로 위 8/19 개정이 주제를 좁히도록
     * 시킨 것이었고, 좁힌 주제와 기본 개념이 한 절을 두고 겨루면 좁은 쪽이 이긴다.
     * 그래서 상위 개념 전용 자리를 따로 냈는데, <b>자리만 만들면 이름만 남는다</b>는 것을
     * {@code ### 용어 한눈에}에서 이미 겪었다(8/18 — 표를 만들라고 시켰더니 만들긴 했다).
     *
     * <p>그래서 존재가 아니라 <b>분량</b>을 센다. 이 검사가 없으면 모델은 [분량] 압박을 받을
     * 때 이 절부터 줄인다 — 문서의 주제와 가장 먼 절이라 지우기 가장 쉬워 보이기 때문이다.
     * {@link #MIN_FOUNDATION_LENGTH}의 기준을 프롬프트 요구치(1,500)보다 낮춘 이유는
     * 그 상수 주석 참고.
     */
    private static void checkFoundationSection(String body, String structure, List<DocumentCheck> checks) {
        Section section = sectionOf(body, structure, "## 바탕이 되는 개념");
        if (section == null) {
            return; // 절 자체가 없으면 checkRequiredSections가 이미 차단으로 잡는다
        }
        // 분량은 원문으로 잰다 — 코드 예제도 독자가 읽는 양이다(항목을 세는 검사와 갈리는 지점)
        int length = section.raw().strip().length();
        if (length < MIN_FOUNDATION_LENGTH) {
            checks.add(DocumentCheck.warning(
                    "'## 바탕이 되는 개념'이 %d자입니다(기준 %d자 이상, 프롬프트 요구는 1,500~2,500자). "
                            .formatted(length, MIN_FOUNDATION_LENGTH)
                            + "이 절이 얇으면 배경 지식이 없는 사람은 첫 문단에서 막힙니다."));
        }
    }

    /**
     * 코드블록 안을 <b>같은 길이의 공백</b>으로 지운 사본 — 제목을 찾는 모든 검사의 입력이다.
     *
     * <p><b>왜 필요한가.</b> 2026-08-23에 코드 예제를 허용하면서 셸 예제가 문서에 들어오기
     * 시작했는데, 셸 주석이 하필 마크다운 제목과 <b>글자 하나까지 똑같다</b>:
     * <pre>
     * ```bash
     * # 상태별 소켓 개수를 많은 순으로 센다
     * ```
     * </pre>
     * {@link #H1_PATTERN}에게 이 줄은 문서 제목이고, {@code ## }로 시작하는 주석은
     * {@link #H2_PATTERN}에게 절 제목이다. 실물(TIME_WAIT 문서)에 이미 두 줄 들어 있었다.
     *
     * <p><b>무엇이 조용히 망가지나.</b> 세 가지인데 전부 오류 없이 지나간다.
     * <ol>
     *   <li>H1이 없는 문서에서 셸 주석이 <b>제목 행세</b>를 한다. 그러면 "최상위 제목이 없습니다"
     *       (차단)가 아니라 "제목이 다릅니다"(경고)로 나온다 — 구조가 무너진 문서가 승인된다.
     *   <li>{@link #sectionOf}가 절의 끝을 찾을 때 {@code ## } 주석에서 <b>먼저 끊는다</b>.
     *       그 절이 실제보다 짧아 보여 "바탕이 되는 개념이 얇다"·"깨지는 조건이 모자라다"가
     *       멀쩡한 문서에 뜬다. 이 클래스가 가장 경계하는 오탐이다.
     *   <li>{@code ## 무엇인가}가 코드 주석으로만 있어도 필수 절이 있는 것으로 통과한다.
     * </ol>
     *
     * <p><b>왜 지우지 않고 공백으로 바꾸나.</b> 길이와 줄 수가 그대로 유지돼야
     * <b>사본에서 찾은 위치로 원본을 그대로 잘라낼 수</b> 있다({@link #sectionOf}).
     * 아예 지우면 인덱스가 어긋나 원본 대신 사본을 넘겨야 하고, 그러면 절 안의 코드가
     * 통째로 사라진 채 길이를 재게 된다 — 검사마다 원본이 필요한지 사본이 필요한지가
     * 다르므로, 둘 다 쓸 수 있게 두는 편이 낫다.
     *
     * <p>줄바꿈을 살려 두는 것도 같은 이유다. {@code (?m)^}가 줄 시작에 걸리는 패턴들이라
     * 줄 구조가 무너지면 바로 옆 줄과 이어져 엉뚱한 곳에서 일치한다.
     */
    private static String maskFencedCode(String body) {
        StringBuilder masked = new StringBuilder(body);
        Matcher m = FENCED_CODE.matcher(body);
        while (m.find()) {
            for (int i = m.start(); i < m.end(); i++) {
                if (body.charAt(i) != '\n') {
                    masked.setCharAt(i, ' ');
                }
            }
        }
        return masked.toString();
    }

    /**
     * 특정 {@code ## } 절을 잘라낸 결과 — 같은 구간의 <b>원문</b>과 <b>코드블록을 지운 사본</b>.
     *
     * <p>검사마다 필요한 쪽이 다르다. 항목 개수를 세는 검사는 코드 안의 {@code - } 줄에
     * 속으면 안 되니 {@code masked}를 보고, 분량을 재는 검사는 독자가 실제로 읽는 양이
     * 기준이니 {@code raw}를 본다.
     */
    private record Section(String raw, String masked) {
    }

    /**
     * 특정 {@code ## } 절의 본문 — 다음 {@code ## }가 나오기 전까지. 절이 없으면 {@code null}.
     *
     * <p>절 단위로 잘라 보는 이유: 문서 전체에서 찾으면 다른 절의 하이픈 목록에 속아
     * "용어 목록이 있다"고 판정한다. 검사는 <b>그 절 안에서</b> 이뤄져야 의미가 있다.
     *
     * <p><b>경계는 {@code structure}에서 찾고 자르기는 양쪽에서 한다.</b> 코드블록 안의
     * {@code ## } 주석에서 절이 잘리는 것을 막으면서도({@link #maskFencedCode} 참고)
     * 원문을 그대로 돌려줄 수 있는 것은 두 문자열의 길이가 같기 때문이다.
     */
    private static Section sectionOf(String body, String structure, String heading) {
        int start = structure.indexOf(heading);
        if (start < 0) {
            return null;
        }
        int from = start + heading.length();
        Matcher m = H2_PATTERN.matcher(structure);
        // 이 절 <다음>의 ## 제목을 찾는다. find(from)은 from 이후 첫 일치를 준다.
        int to = m.find(from) ? m.start() : structure.length();
        return new Section(body.substring(from, to), structure.substring(from, to));
    }

    /** 패턴이 몇 번 나오는지. */
    private static int count(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    /**
     * 코드블록 <b>바깥</b>의 HTML 태그.
     *
     * <p><b>이 예외 처리가 없으면 보안 문서를 영영 못 쓴다.</b> XSS를 설명하는 문서에는
     * {@code <script>}가 예시로 들어가는 게 당연하고, 코드블록 안이면 marked가 글자로
     * 이스케이프하므로 실행되지 않는다. 실제로 2026-08-12 문서가 그런 경우였다 —
     * {@code <}만 보고 막는 검증기를 만들었다면 정상 문서를 반려할 뻔했다.
     *
     * <p>막아야 하는 것은 코드블록 밖의 태그다. 문서 상세 화면이 마크다운 변환 결과를
     * {@code innerHTML}로 넣기 때문에(document.html), 그 자리의 태그는 진짜로 실행된다.
     */
    private static void checkHtmlTags(String body, List<DocumentCheck> checks) {
        // 코드 영역을 지운 사본에서만 찾는다. 길이가 달라져도 상관없다 — 위치가 아니라
        // "있는가 / 무엇이"만 알면 되기 때문. 펜스를 먼저 지워야 그 안의 백틱에 안 휘둘린다.
        String stripped = INLINE_CODE.matcher(FENCED_CODE.matcher(body).replaceAll("")).replaceAll("");

        Matcher m = HTML_TAG.matcher(stripped);
        if (m.find()) {
            checks.add(DocumentCheck.blocking(
                    "코드블록 밖에 HTML 태그가 있습니다(문서 화면에서 그대로 실행됩니다): " + m.group()));
        }
    }

    /** 분량 — 권장 초과는 경고, 하드 상한 초과는 차단. */
    private static void checkLength(String body, List<DocumentCheck> checks) {
        int length = body.length();
        if (length > MAX_LENGTH) {
            checks.add(DocumentCheck.blocking(
                    "본문이 하드 상한을 넘었습니다: " + length + "자 (상한 " + MAX_LENGTH + "자)"));
        } else if (length > WARN_LENGTH) {
            checks.add(DocumentCheck.warning(
                    "본문이 권장 분량을 넘었습니다: " + length + "자 (권장 " + WARN_LENGTH + "자)"));
        }
    }

    /** 공백을 모두 제거한 비교용 문자열. */
    private static String squash(String s) {
        return s.replaceAll("\\s+", "");
    }
}
