package project.study.study_project.llm.support;

import project.study.study_project.llm.client.ClaudeDocumentGenerator;
import project.study.study_project.llm.client.DocumentEdition;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
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

    /** {@code AdminDocumentRequest.slug}의 정규식과 같아야 한다 — 다르면 승인 순간 400으로 튕긴다. */
    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9]++(?:-[a-z0-9]++)*+$");

    /**
     * 본문 첫 번째 H1({@code # 제목}). {@code ##} 이상은 잡지 않도록 {@code #} 뒤에 공백을 요구한다.
     * {@code (?m)}는 여러 줄 모드 — {@code ^}가 각 줄의 시작에 걸리게 한다.
     */
    private static final Pattern H1_PATTERN = Pattern.compile("(?m)^#\\s+(.+?)\\s*$");

    /** 펜스 코드블록. {@code (?s)}로 줄바꿈까지 포함하고, 최소 일치({@code *?})로 블록을 하나씩 끊는다. */
    private static final Pattern FENCED_CODE = Pattern.compile("(?s)```.*?```");

    /**
     * 그림 블록 — {@code ```mermaid} 펜스. 1번 그룹이 그림 원문이다(2026-10-10).
     *
     * <p>문서 화면({@code document.html})이 이 펜스만 그림으로 바꿔 그린다. 코드 예제와 같은
     * 펜스 문법이라 {@link #FENCED_CODE}에도 걸리므로, 코드 예제를 셀 때는 이 개수를 뺀다 —
     * 안 빼면 모델이 코드 하나를 그림으로 바꿔치기해도 개수 검사가 통과한다.
     */
    private static final Pattern MERMAID_BLOCK = Pattern.compile("(?s)```mermaid[ \\t]*\\R(.*?)```");

    /** 용어 표 절 제목 — 입문편은 {@code ## 용어 한눈에}, 옛 심화편은 {@code ###}였다. */
    private static final Pattern GLOSSARY_HEADING = Pattern.compile("(?m)^#{2,3}\\s+용어 한눈에");

    /** 문서에 넣은 그림 파일 — {@code ![설명](/images/docs/x.svg)} 꼴. 손으로 그린 그림이 여기 든다. */
    private static final Pattern DOC_IMAGE = Pattern.compile("!\\[[^\\]\\n]*\\]\\([^)\\s]+\\.(?:svg|png|jpe?g|webp)\\)");

    /**
     * 프롬프트가 허용한 그림 종류의 첫 줄 — {@code ClaudeDocumentGenerator}의 {@code [그림]} 절과 같아야 한다.
     *
     * <p>mermaid 문법 전체를 자바에서 검사할 방법은 없다. 첫 줄만 본다 — 종류 선언이 틀리면
     * 그림이 통째로 안 그려지고, 화면에는 그림 대신 원문이 코드로 남는다.
     *
     * <p>gantt·xychart-beta·block-beta·packet-beta는 문서 화면의 mermaid 11.4.1이 그리는 것을
     * 실물 문서(패킷 교환 두 편)로 확인하고 넣었다. 화면의 mermaid 버전을 내리면 여기도 함께 본다.
     */
    private static final Pattern MERMAID_HEADER = Pattern.compile(
            "^(flowchart\\s+(TD|TB|LR)|sequenceDiagram|stateDiagram-v2|gantt|xychart-beta|block-beta|packet-beta)\\s*$");

    /** 글 끝의 출처 절. 본론이 아니므로 본론 개수에서 뺀다(2026-11-10). */
    static final String REFERENCES_SECTION = "## 참고 자료";

    /** 마크다운 링크 {@code [글자](주소)} — 1번 그룹이 주소다. */
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[[^\\]\\n]+\\]\\(([^)\\s]+)\\)");

    /**
     * 참고 자료로 믿고 받아 주는 곳 — 표준 문서와 제품을 만든 곳의 공식 문서만.
     *
     * <p>배치 모델은 인터넷을 볼 수 없어서, 그럴듯한데 없는 주소를 지어낼 수 있다. 이 목록 밖의
     * 링크는 막지 않고 경고만 띄워, 검수자가 승인 전에 한 번 눌러 보게 한다.
     * 하위 도메인도 받는다(예: {@code cheatsheetseries.owasp.org}).
     */
    static final List<String> TRUSTED_REFERENCE_HOSTS = List.of(
            "rfc-editor.org", "ietf.org", "w3.org", "whatwg.org", "developer.mozilla.org",
            "docs.oracle.com", "openjdk.org", "spring.io", "jakarta.ee", "hibernate.org",
            "dev.mysql.com", "postgresql.org", "redis.io", "kubernetes.io", "docs.docker.com",
            "docs.aws.amazon.com", "cloud.google.com", "learn.microsoft.com", "nginx.org",
            "kernel.org", "man7.org", "owasp.org", "nist.gov", "iana.org", "unicode.org");

    /** 경고 메시지에 적는 허용 종류 — {@link #MERMAID_HEADER}와 같이 고친다. */
    private static final String MERMAID_KINDS =
            "flowchart TD·LR, sequenceDiagram, stateDiagram-v2, gantt, xychart-beta, block-beta, packet-beta";

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

    private DocumentDraftValidator() {
    }

    /**
     * 초안 하나를 검증해 발견한 문제를 모두 돌려준다. 문제가 없으면 빈 목록.
     *
     * <p><b>첫 실패에서 멈추지 않는 이유</b>: 검수자는 한 번에 다 보고 판단하고 싶어 한다.
     * "절이 빠졌다"를 고쳐 다시 뽑았더니 이번엔 "분량 초과"가 나오는 식이면 왕복만 늘어난다.
     *
     * <p><b>편은 인자로 받지 않고 본문에서 알아낸다</b>(2026-09-03,
     * {@link ClaudeDocumentGenerator#editionOf}). 호출부가 넷인데 그중 승인 화면 경로는
     * 초안 한 건만 들고 있어 편을 알 방법이 없다. 시그니처를 바꾸면 그 자리에서 억지로
     * 값을 지어내게 되고, 지어낸 값과 본문이 어긋나면 <b>멀쩡한 문서가 통째로 차단된다</b> —
     * 필수 절 검사가 차단 등급이라 그렇다. 본문에서 읽으면 어긋날 자리가 없다.
     */
    public static List<DraftCheck> validate(String title, String slug, String contentMd) {
        List<DraftCheck> checks = new ArrayList<>();
        String body = contentMd == null ? "" : contentMd;
        DocumentEdition edition = ClaudeDocumentGenerator.editionOf(body);

        // 제목을 찾는 검사들은 <코드블록을 지운 사본>에서 본다. 배경은 maskFencedCode 주석 참고.
        String structure = maskFencedCode(body);

        checkSlug(slug, checks);
        checkTitleMatchesHeading(title, body, structure, checks);
        checkTitleStyle(title, checks);
        checkRequiredSections(structure, edition, checks);
        checkDiagrams(body, edition, checks);
        checkHtmlTags(body, checks);
        checkSectionOpeners(structure, checks);
        checkReferences(body, structure, checks);
        checkTopicBoxPresent(body, checks);
        checkImageFiles(body, checks);

        if (edition == DocumentEdition.BEGINNER) {
            // 아래 둘은 입문편에만 있는 절·규칙을 본다. 심화편에서 돌리면 전부 헛울린다 —
            // 심화편에는 ## 무엇인가도 ## 바탕이 되는 개념도 없는 것이 정상이다.
            checkDesignRationaleHeading(structure, checks);
        } else {
            checkFailureScenes(body, structure, checks);
            // 입문편은 '## 용어 한눈에'가 필수 절이라 checkRequiredSections가 이미 차단으로 잡는다.
            // 여기서 또 세면 한 결함에 메시지가 둘 뜬다.
            checkGlossaryTable(structure, checks);
        }
        return checks;
    }

    /**
     * 사람이 쓴 「글의 주제」(V48)가 있으면 본문 상자가 그 글과 같은지도 본다.
     * 생성은 상자를 코드로 끼우므로 다르다면 손으로 쓰는 흐름이나 수정 중에 바뀐 것이다.
     *
     * @param expectedTopicBox 대기열의 그 편 글. {@code null}이면 이 검사를 건너뛴다
     */
    public static List<DraftCheck> validate(String title, String slug, String contentMd, String expectedTopicBox) {
        List<DraftCheck> checks = validate(title, slug, contentMd);
        // 상자가 아예 없으면 기본 검사(checkTopicBoxPresent)가 이미 막는다. 여기서는 다를 때만 본다
        if (expectedTopicBox != null && !expectedTopicBox.isBlank()
                && TopicBoxes.extract(contentMd) != null && !TopicBoxes.matches(contentMd, expectedTopicBox)) {
            checks.add(DraftCheck.blocking(
                    "「글의 주제」가 대기열에 사람이 쓴 글과 다릅니다. 글을 고치려면 관리 콘솔의 주제 상세에서 고치세요."));
        }
        return checks;
    }

    /** 승인해도 되는가 — BLOCKING이 하나라도 있으면 안 된다. */
    public static boolean hasBlocking(List<DraftCheck> checks) {
        return checks.stream().anyMatch(DraftCheck::isBlocking);
    }

    /* ── 개별 규칙 ───────────────────────────────────────────── */

    /**
     * slug 형식. 어차피 {@code AdminDocumentRequest}의 {@code @Pattern}이 잡지 않느냐 싶지만,
     * <b>잡지 못한다</b>: 승인은 컨트롤러가 아니라 서비스에서 {@code AdminDocumentService.create}를
     * 직접 부르는 경로라 {@code @Valid}가 걸리지 않는다. 여기서 안 보면 그대로 통과한다.
     */
    private static void checkSlug(String slug, List<DraftCheck> checks) {
        if (slug == null || slug.isBlank()) {
            checks.add(DraftCheck.blocking("slug가 비어 있습니다."));
        } else if (!SLUG_PATTERN.matcher(slug).matches()) {
            checks.add(DraftCheck.blocking(
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
                                                 List<DraftCheck> checks) {
        // 코드블록을 지운 사본에서 찾는다 — 셸 주석("# 개수를 센다")이 제목 행세를 하면
        // "제목이 없습니다"(차단)가 "제목이 다릅니다"(경고)로 내려앉는다(maskFencedCode 주석).
        Matcher m = H1_PATTERN.matcher(structure);
        if (!m.find()) {
            checks.add(DraftCheck.blocking("본문에 최상위 제목(# ...)이 없습니다."));
            return;
        }
        // 위치는 사본에서 찾았지만 글자는 원문에서 꺼낸다(두 문자열의 길이가 같아 인덱스가 통한다).
        String heading = body.substring(m.start(1), m.end(1));
        if (title == null || !squash(title).equals(squash(heading))) {
            checks.add(DraftCheck.warning(
                    "제목 필드와 본문 제목이 다릅니다. 필드=\"" + title + "\" / 본문=\"" + heading + "\""));
        }
    }

    /**
     * 제목이 기술 문서 형식인지 — 명사구인가, 의문문이나 줄표 부연이 붙지 않았는가(2026-09-17).
     *
     * <p><b>왜 프롬프트로 끝내지 않는가.</b> 2026-09-17에 제목 규칙을 의문문에서 명사구로
     * 뒤집었는데, 프롬프트 문구만 고치고 끝낸 규칙이 조용히 옛 형식으로 돌아가는 것을 이 저장소가
     * 이미 겪었다(8/15 하이픈 사고 — 규칙이 두 번 적혀 있었는데도 예시를 따라갔다).
     * 제목은 문서당 한 줄뿐이라 <b>검수자가 본문을 읽느라 가장 잘 지나치는</b> 자리이기도 하다.
     *
     * <p><b>경고인 이유</b>는 {@link #checkTitleMatchesHeading}과 같다. 제목은 승인 뒤 수정
     * 화면에서 한 줄 고치면 되는 것이라, 차단으로 두면 멀쩡한 문서 한 편을 다시 뽑게 만든다.
     * 게다가 이 검사는 <b>형식만</b> 보고 좋은 제목인지는 사람만 안다.
     *
     * <p>고쳐 쓴 예를 메시지에 함께 넣는 이유: "형식에 맞지 않습니다"만 보여 주면 검수자가
     * 프롬프트를 열어 규칙을 찾아 읽어야 한다. 무엇으로 바꾸라는 것인지가 메시지에 있어야 고친다.
     *
     * <p><b>판정은 {@link TitleStyleRule}이 한다.</b> 같은 두 규칙을 문제 제목도 본다
     * ({@code ProblemItemRule}). 여기에 따로 적어 두면 "문서에서는 걸리는데 문제에서는 안 걸리는"
     * 제목이 생기고, 그 차이는 목록에서 두 세대가 다르게 읽히는 것으로 나타난다.
     */
    private static void checkTitleStyle(String title, List<DraftCheck> checks) {
        if (title == null || title.isBlank()) {
            return; // 비어 있는 제목은 checkTitleMatchesHeading이 이미 잡는다. 여기서 또 울리면 메시지가 둘이다.
        }
        String trimmed = title.trim();
        if (TitleStyleRule.isQuestionForm(trimmed)) {
            checks.add(DraftCheck.warning(
                    "제목이 의문문입니다. 개념을 가리키는 명사구로 바꾸세요"
                            + "(예: \"…를 무엇으로 아는가\" → \"…의 버전 관리 기법 (Flyway)\"): " + trimmed));
        }
        if (TitleStyleRule.hasDashSubtitle(trimmed)) {
            checks.add(DraftCheck.warning(
                    "제목에 줄표 부연이 붙어 있습니다. 줄표가 필요하다는 것은 제목이 두 가지를 담고 있다는 뜻입니다: "
                            + trimmed));
        }
    }

    /**
     * 필수 절 존재 — 목록의 출처는 생성 프롬프트다(두 곳이 어긋나지 않도록 상수 하나를 공유).
     *
     * @param structure 코드블록을 지운 사본. 원문으로 보면 코드 주석에 적힌 {@code ## 무엇인가}만으로
     *                  절이 있는 것으로 통과한다.
     */
    private static void checkRequiredSections(String structure, DocumentEdition edition,
                                              List<DraftCheck> checks) {
        for (String section : ClaudeDocumentGenerator.requiredSections(edition)) {
            if (!hasSectionHeading(structure, section)) {
                checks.add(DraftCheck.blocking(
                        "%s에 필수 절이 없습니다: %s".formatted(edition.getDisplayName(), section)));
            }
        }
    }

    /**
     * 그 제목이 <b>줄 처음에, 그 수준으로</b> 있는지.
     *
     * <p><b>{@code contains}로는 안 된다.</b> {@code "## 용어 한눈에"}는 {@code "### 용어 한눈에"}의
     * 부분 문자열이라, 입문편이 이 표를 본론 안 소제목({@code ###})으로 잘못 넣어도
     * 필수 절 검사가 <b>조용히 통과</b>한다. 프롬프트가 "본론 절 안의 소제목으로 넣지 마라"라고
     * 못 박은 바로 그 실수를 못 잡는 셈이다. 두 수준이 함께 쓰이는 제목이 생긴
     * 2026-09-03 후속 개정부터 실제로 뚫려 있었다.
     *
     * <p>줄 <b>시작</b>만 보고 끝은 보지 않는다. 실물이 {@code ## 바탕이 되는 개념: 프로세스}처럼
     * 제목 뒤에 그날의 주제를 덧붙이기 때문이다 — 그건 정상이고 막을 이유가 없다.
     */
    private static boolean hasSectionHeading(String structure, String section) {
        return Pattern.compile("(?m)^" + Pattern.quote(section)).matcher(structure).find();
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
    private static void checkDesignRationaleHeading(String structure, List<DraftCheck> checks) {
        int headings = count(DESIGN_RATIONALE_HEADING, structure);
        if (headings == 1) {
            return;
        }
        if (headings == 0) {
            int bold = count(DESIGN_RATIONALE_BOLD, structure);
            checks.add(DraftCheck.warning(bold > 0
                    ? "'### 왜 이렇게 설계됐는가' 소제목이 없습니다(굵은 글씨로 %d개 있습니다 — ###로 올리세요). 중급 문제가 이 절을 재료로 씁니다."
                            .formatted(bold)
                    : "'### 왜 이렇게 설계됐는가' 소제목이 없습니다. 중급 문제가 이 절을 재료로 씁니다."));
            return;
        }
        checks.add(DraftCheck.warning(
                "'### 왜 이렇게 설계됐는가' 소제목이 %d개입니다(문서 전체에 1개). 설계 근거는 본문 문장으로 녹여 쓰세요."
                        .formatted(headings)));
    }

    /**
     * 심화편에 {@code ## 용어 한눈에} 표가 있는지.
     *
     * <p><b>2026-09-04: 자리가 본론 안({@code ###})에서 최상위 절({@code ##})로 올라갔다.</b>
     * 패턴({@link #GLOSSARY_HEADING})이 {@code #{2,3}}을 함께 받으므로 검사 자체는 그대로지만,
     * 메시지가 옛 자리를 가리키면 검수자가 <b>없는 곳을 찾는다</b>. 옛 심화편 문서도 통과해야
     * 하므로 패턴은 두 자리를 다 받은 채로 둔다.
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
    private static void checkGlossaryTable(String structure, List<DraftCheck> checks) {
        if (!GLOSSARY_HEADING.matcher(structure).find()) {
            checks.add(DraftCheck.warning(
                    "'## 용어 한눈에' 표가 없습니다. "
                            + "본론 뒤, '## 실무에서 어디에 나타나는가' 바로 앞에 두는 절입니다 — "
                            + "독자가 아래 절에서 막혔을 때 돌아오는 자리입니다."));
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
    /**
     * 글 끝의 {@code ## 참고 자료} — 있는지, 링크가 믿을 만한 곳인지(2026-11-10).
     *
     * <p>전부 경고다. 링크가 실제로 열리는지는 여기서 알 수 없다(검사기는 인터넷에 나가지 않는다).
     * 그래서 허용 목록({@link #TRUSTED_REFERENCE_HOSTS}) 밖이거나 https가 아닌 링크를 짚어,
     * 검수자가 눌러 볼 곳을 좁혀 준다.
     */
    private static void checkReferences(String body, String structure, List<DraftCheck> checks) {
        Section section = sectionOf(body, structure, REFERENCES_SECTION);
        if (section == null) {
            checks.add(DraftCheck.warning(
                    "'## 참고 자료' 절이 없습니다. 본문의 수치·동작을 확인할 표준 문서나 공식 문서를 2~5개 답니다."));
            return;
        }
        Matcher m = MARKDOWN_LINK.matcher(section.masked());
        int links = 0;
        List<String> doubtful = new ArrayList<>();
        while (m.find()) {
            links++;
            String url = m.group(1);
            if (!isTrustedReference(url)) {
                doubtful.add(url);
            }
        }
        if (links == 0) {
            checks.add(DraftCheck.warning("'## 참고 자료'에 링크가 없습니다. [제목](https://...) 꼴로 답니다."));
        }
        if (!doubtful.isEmpty()) {
            checks.add(DraftCheck.warning(
                    "참고 자료 링크 %d개가 허용 목록 밖이거나 https가 아닙니다. 승인 전에 열어 보고 확인하세요: %s"
                            .formatted(doubtful.size(), String.join(", ", doubtful))));
        }
    }

    /** https이고 호스트가 허용 목록의 도메인이거나 그 하위 도메인인지. */
    static boolean isTrustedReference(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null) {
                return false;
            }
            String h = host.toLowerCase(Locale.ROOT);
            return TRUSTED_REFERENCE_HOSTS.stream().anyMatch(t -> h.equals(t) || h.endsWith("." + t));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 그림({@code ```mermaid})이 있는지, 있으면 화면이 그릴 수 있는 종류인지 — 2026-10-10 신설.
     *
     * <p>그날까지 나온 문서 30편에 그림이 한 장도 없었다. 프롬프트가 "표로도 목록으로도 담기지
     * 않는 구조만 예외로 그림을 허용한다"고 했는데, 허용만으로는 나오지 않는다 — 코드 예제에서
     * 이미 겪은 일이다. 그래서 입문편에는 한 장을 요구하고 여기서 센다. 손으로 그린 그림 파일도 센다.
     *
     * <p><b>심화편은 없어도 조용하다.</b> 프롬프트가 심화편의 그림을 선택으로 뒀다.
     * 대신 종류 검사는 두 편 모두에 건다 — 틀린 그림은 어느 편에서든 원문이 코드로 보인다.
     *
     * <p>전부 경고다. 그림 하나 때문에 나흘치 문서를 버리는 것보다 검수자가 지우거나
     * 고치는 편이 싸다(코드 예제 검사와 같은 판단).
     */
    private static void checkDiagrams(String body, DocumentEdition edition, List<DraftCheck> checks) {
        Matcher m = MERMAID_BLOCK.matcher(body);
        int diagrams = 0;
        while (m.find()) {
            diagrams++;
            String header = m.group(1).lines().map(String::strip).filter(l -> !l.isEmpty())
                    .findFirst().orElse("");
            if (!MERMAID_HEADER.matcher(header).matches()) {
                checks.add(DraftCheck.warning(
                        "%d번째 그림의 첫 줄이 \"%s\"입니다(%s만). 화면에 그림 대신 원문이 코드로 보입니다."
                                .formatted(diagrams, header.length() <= 30 ? header : header.substring(0, 30) + "…",
                                        MERMAID_KINDS)));
            }
        }
        // 손으로 그린 SVG도 그림이다(2026-11). 네트워크 입문편은 mermaid 대신 SVG만 쓴 편이 있다
        int pictures = diagrams + count(DOC_IMAGE, body);
        if (pictures == 0 && edition == DocumentEdition.BEGINNER) {
            checks.add(DraftCheck.warning(
                    "그림이 없습니다(입문편은 mermaid나 그림 파일 1개 이상). 구조나 흐름을 글로만 따라가야 합니다."));
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
    /**
     * 절의 첫 줄이 <b>목록·표·코드·소제목</b>인지 — 그러면 다리 문장이 없는 것이다.
     */
    private static final Pattern NOT_A_SENTENCE_OPENER =
            Pattern.compile("^(\\*\\*|#{3,}\\s|-\\s|\\*\\s|\\||```|\\d+\\.\\s|>\\s)");

    /**
     * 다리 문장을 요구하지 <b>않는</b> 절 — 원래 목록이거나 표인 자리다.
     *
     * <p>「핵심 요약」과 각 편의 그다음 절은 글의 첫머리라 받을 앞 절이 없고,
     * 「용어 한눈에」는 표 자체가 내용이다. 여기에까지 문장을 요구하면 <b>프롬프트대로 잘 쓴
     * 문서에 매번 경고가 셋 붙는다</b>. 늘 떠 있는 경고는 진짜 차단이 왔을 때도 그러려니
     * 하게 만든다 — 이 검증기가 오탐을 미탐보다 비싸게 치는 이유다.
     */
    private static final Set<String> BRIDGE_EXEMPT_SECTIONS = Set.of(
            "핵심 요약", "바탕이 되는 개념", "이 글을 읽기 전에", "용어 한눈에");

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

    /** 「글의 주제」 상자가 있는지(2026-11-13). 상자는 사람이 쓰므로 없으면 승인하지 않는다. */
    private static void checkTopicBoxPresent(String body, List<DraftCheck> checks) {
        if (TopicBoxes.extract(body) == null) {
            checks.add(DraftCheck.blocking(
                    "「글의 주제」 상자가 없습니다. 제목 바로 아래에 '> **글의 주제**' 상자를 두세요."));
        }
    }

    /** 본문이 부르는 그림 파일 — {@code ![설명](/images/docs/x.svg)}의 괄호 속 경로를 꺼낸다. */
    private static final Pattern DOC_IMAGE_PATH = Pattern.compile("!\\[[^\\]\\n]*\\]\\((/[^)\\s]+)\\)");

    /**
     * 그림 파일이 실제로 있는지(2026-11-13). 경로 오타 하나면 화면에 깨진 그림이 뜬다.
     * 사이트가 정적 파일을 {@code static/} 아래에서 내주므로 클래스패스의 같은 자리를 찾는다.
     */
    private static void checkImageFiles(String body, List<DraftCheck> checks) {
        Matcher m = DOC_IMAGE_PATH.matcher(body);
        ClassLoader loader = DocumentDraftValidator.class.getClassLoader();
        while (m.find()) {
            String path = m.group(1);
            if (loader.getResource("static" + path) == null) {
                checks.add(DraftCheck.blocking("그림 파일이 없습니다: " + path));
            }
        }
    }

    private static final Pattern SCENE_HEADING = Pattern.compile("(?m)^###\\s+장면\\s");
    private static final Pattern ITEM_HEADING = Pattern.compile("(?m)^####\\s+(?!~~)(.+)$");

    /**
     * 심화편 실패 사례가 장면 꼴인지(2026-11-13, docs/WRITING_STYLE.md 10절).
     * 「어떤 때 통하지 않는가」 아래에 {@code ### 장면}이 있어야 하고, 장면마다 첫 사례는
     * 꼬리표(`증상` 등) 없이 이야기로 쓴다. 꼬리표만 남으면 장면 없이 요약만 남는다.
     */
    private static void checkFailureScenes(String body, String structure, List<DraftCheck> checks) {
        Section section = sectionOf(body, structure, "## 어떤 때 통하지 않는가");
        if (section == null) {
            return; // 절이 없으면 필수 절 검사가 이미 차단으로 잡는다
        }
        String text = section.masked();
        Matcher scene = SCENE_HEADING.matcher(text);
        List<Integer> starts = new ArrayList<>();
        while (scene.find()) {
            starts.add(scene.start());
        }
        if (starts.isEmpty()) {
            checks.add(DraftCheck.warning(
                    "'## 어떤 때 통하지 않는가'에 '### 장면 ①' 소제목이 없습니다. 실패 사례를 장면 2~3개로 묶으세요."));
            return;
        }
        for (int i = 0; i < starts.size(); i++) {
            int end = i + 1 < starts.size() ? starts.get(i + 1) : text.length();
            String block = text.substring(starts.get(i), end);
            Matcher item = ITEM_HEADING.matcher(block);
            if (!item.find()) {
                continue;
            }
            int from = item.end();
            int to = item.find() ? item.start() : block.length();
            if (block.substring(from, to).contains("`증상`")) {
                String name = block.lines().findFirst().orElse("").replaceFirst("^###\\s+", "");
                checks.add(DraftCheck.warning(
                        "'%s'의 첫 사례가 꼬리표로 쓰였습니다. 장면마다 첫 사례는 상황→벌어진 일→원인→비유→대처 순서의 이야기로 쓰세요."
                                .formatted(name)));
            }
        }
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
     * 각 {@code ## } 절이 <b>문장 하나로 시작하는지</b> — 2026-09-16.
     *
     * <h2>왜 이걸 세는가</h2>
     *
     * <p>사용자 지적은 "절과 절 사이가 툭툭 끊긴다. 절 여섯 개가 한 글이 아니라 여섯 개의
     * 글처럼 읽힌다"였다. 원인은 문체가 아니라 <b>자리</b>다 — 심화편의 주력 절 둘과
     * 「면접에서 이렇게 물어본다」가 모두 굵은 항목으로 곧장 시작한다. 앞 절이 끝나자마자
     * 새 목록이 시작되니 두 절을 잇는 문장이 들어설 칸이 아예 없다.
     *
     * <p>더 고약한 것은 이 프롬프트가 흐름을 <b>일부러</b> 깎아 놨다는 점이다.
     * {@code [덜어낼 것]}이 연결어("정리하면", "즉")를 이름으로 금지한다 — 2026-08-15에
     * 분량 상한을 올리면서 넣은 규칙으로, 늘어난 지면이 부연으로 차는 것을 막으려고
     * "담백함"을 얻고 "이어짐"을 판 것이다. 그래서 "매끄럽게 써라"를 한 줄 더하는 것으로는
     * 안 된다. 두 지시가 정면으로 부딪쳐 모델이 한쪽을 조용히 무시한다.
     *
     * <h2>왜 "매끄러움"이 아니라 "첫 줄"을 보는가</h2>
     *
     * <p>매끄러움은 못 센다. 첫 줄이 문장인지는 원문에서 그대로 판정된다. 규칙을 셀 수 있는
     * 모양으로 바꿔 검증기에 넘기는 것이 이 저장소가 프롬프트 품질에 써 온 방법이다
     * (docs/17). 물론 칸이 있다고 다리가 놓인 것은 아니다 — 문장이 실제로 앞 절을 받는지는
     * 사람이 읽어야 한다. 여기서 막는 것은 <b>칸조차 없는 상태</b>다.
     *
     * <h2>경고이지 차단이 아니다</h2>
     *
     * <p>흐름은 재료와 달라서, 어긋나도 다음 날 문제 생성이 망가지지 않는다. 읽기가 나빠질
     * 뿐이다. 차단으로 두면 승인 버튼이 막혀 <b>멀쩡한 하루치를 버리게</b> 된다.
     *
     * <p>개수 검사와 달리 {@code structure}(코드블록을 지운 사본)만 본다. 원문을 보면
     * 코드블록 안의 {@code ## } 주석이 절로 잡힌다.
     */
    private static void checkSectionOpeners(String structure, List<DraftCheck> checks) {
        Matcher m = H2_PATTERN.matcher(structure);
        while (m.find()) {
            String heading = m.group(1).trim();
            if (BRIDGE_EXEMPT_SECTIONS.contains(heading)) {
                continue;
            }
            // 제목 줄 다음의 첫 <내용 있는> 줄. 빈 줄은 건너뛴다 — 마크다운에서 제목과 본문
            // 사이의 빈 줄은 관례라, 그걸 "첫 줄"로 보면 모든 절이 통과한다.
            String rest = structure.substring(m.end());
            String firstLine = rest.lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty())
                    .findFirst()
                    .orElse("");
            if (firstLine.isEmpty() || !NOT_A_SENTENCE_OPENER.matcher(firstLine).find()) {
                continue;
            }
            checks.add(DraftCheck.warning(
                    "'## %s'가 목록·표로 곧장 시작합니다. 문장으로 시작해 앞 절과 이어 주세요 — "
                            .formatted(heading)
                            + "앞에서 밝혀진 것 때문에 지금 무엇이 문제가 되는지를 한 문장으로."));
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
    private static void checkHtmlTags(String body, List<DraftCheck> checks) {
        // 코드 영역을 지운 사본에서만 찾는다. 길이가 달라져도 상관없다 — 위치가 아니라
        // "있는가 / 무엇이"만 알면 되기 때문. 펜스를 먼저 지워야 그 안의 백틱에 안 휘둘린다.
        String stripped = INLINE_CODE.matcher(FENCED_CODE.matcher(body).replaceAll("")).replaceAll("");

        Matcher m = HTML_TAG.matcher(stripped);
        if (m.find()) {
            checks.add(DraftCheck.blocking(
                    "코드블록 밖에 HTML 태그가 있습니다(문서 화면에서 그대로 실행됩니다): " + m.group()));
        }
    }

    /** 공백을 모두 제거한 비교용 문자열. */
    private static String squash(String s) {
        return s.replaceAll("\\s+", "");
    }
}
