package project.study.study_project.llm.support;

import project.study.study_project.llm.client.ClaudeDocumentGenerator;

import java.util.ArrayList;
import java.util.List;
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
     * 권장 분량 상한(경고). 프롬프트가 요구하는 "2,500~3,500자"의 위쪽 끝.
     * 넘겼다고 막지 않는 이유는 {@link DocumentCheck.Severity} 주석 참고 — 억지로 줄이면
     * 용어 설명과 면접 질문부터 잘려 나가는데, 그 두 절이 분량을 올린 목적이었다.
     */
    static final int WARN_LENGTH = 3_500;

    /**
     * 하드 상한(차단). 권장치의 약 1.4배.
     *
     * <p>이 선을 넘으면 "길게 잘 썼다"가 아니라 <b>모델이 지시를 통째로 무시했거나 같은 말을
     * 반복하기 시작했다</b>는 신호로 본다. 실측 최대가 4,578자였으므로 정상 범위의 문서가
     * 여기 걸릴 일은 없고, 걸린다면 실제로 뭔가 잘못된 것이다.
     */
    static final int MAX_LENGTH = 5_000;

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

        checkSlug(slug, checks);
        checkTitleMatchesHeading(title, body, checks);
        checkRequiredSections(body, checks);
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
    private static void checkTitleMatchesHeading(String title, String body, List<DocumentCheck> checks) {
        Matcher m = H1_PATTERN.matcher(body);
        if (!m.find()) {
            checks.add(DocumentCheck.blocking("본문에 최상위 제목(# ...)이 없습니다."));
            return;
        }
        String heading = m.group(1);
        if (title == null || !squash(title).equals(squash(heading))) {
            checks.add(DocumentCheck.warning(
                    "제목 필드와 본문 제목이 다릅니다. 필드=\"" + title + "\" / 본문=\"" + heading + "\""));
        }
    }

    /** 필수 절 존재 — 목록의 출처는 생성 프롬프트다(두 곳이 어긋나지 않도록 상수 하나를 공유). */
    private static void checkRequiredSections(String body, List<DocumentCheck> checks) {
        for (String section : ClaudeDocumentGenerator.REQUIRED_SECTIONS) {
            if (!body.contains(section)) {
                checks.add(DocumentCheck.blocking("필수 절이 없습니다: " + section));
            }
        }
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
