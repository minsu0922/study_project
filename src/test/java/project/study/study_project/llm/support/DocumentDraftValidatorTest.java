package project.study.study_project.llm.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 문서 초안 자동 검증 테스트 — docs/15.
 *
 * <p><b>이 테스트가 진짜로 지키는 것은 "정상 문서를 막지 않는 것"이다.</b> 검증기가 너무 깐깐하면
 * 증상이 고약하다 — 매일 뽑히는 문서가 전부 승인 불가로 쌓이고, 검수자는 며칠 뒤에야
 * "왜 하나도 못 올리지?"를 알아차린다. 그래서 걸러야 할 것만큼이나 <b>걸리면 안 되는 것</b>에
 * 테스트를 들였다(코드블록 안의 {@code <script>}, 본문의 부등호).
 */
class DocumentDraftValidatorTest {

    /**
     * 도우미 제목 — <b>제목 형식 검사를 통과하는 값</b>이어야 한다(2026-09-17).
     *
     * <p>전에는 "캐시 전략 — 읽기는 빠르게, 쓰기는 정확하게"였다. 줄표 부연이 붙은 제목이
     * 경고 대상이 되면서, 이 값을 그대로 뒀다면 <b>이 파일의 테스트 대부분이 한꺼번에 빨간불</b>이
     * 된다 — 걸린 원인은 각자가 검사하려던 항목이 아니라 도우미 제목이다({@link #FOUNDATION}이
     * 분량 기준을 따라가야 하는 것과 같은 이유).
     */
    private static final String TITLE = "캐시 쓰기 전략의 무효화 처리 방식";

    /**
     * {@code ## 바탕이 되는 개념} 절을 채우는 더미 본문.
     *
     * <p>이 절만 <b>분량으로</b> 검사받는다({@code MIN_FOUNDATION_LENGTH} 2,000자). 자리를 만들어
     * 놓고 이름만 남기는 것이 2026-08-23에 실제로 막으려던 실패라, 도우미 문서도 그 기준을
     * 실제로 넘겨야 한다. 문장을 손으로 적는 대신 {@code repeat}으로 만든 이유는
     * 이 내용이 검사 대상이 아니어서다 — 길이만 의미가 있다.
     *
     * <p>반복 횟수는 기준을 올릴 때마다 함께 올려야 한다. 안 올리면 <b>이 파일의 테스트 대부분이
     * 한꺼번에 빨간불</b>이 되는데, 원인은 검사하려던 항목이 아니라 도우미 문서다.
     */
    private static final String FOUNDATION =
            "이 주제가 딛고 선 상위 개념을 처음부터 설명하는 문단이다. 배경 지식이 없어도 읽힌다. "
                    .repeat(50);

    @Test
    @DisplayName("정상 문서는 지적 사항이 없다")
    void passesValidDocument() {
        List<DraftCheck> checks =
                DocumentDraftValidator.validate(TITLE, "cache-strategy", body(TITLE, "본문"));

        assertThat(checks).isEmpty();
    }

    /* ── 실물에서 발견한 것들 ─────────────────────────────────── */

    /**
     * 2026-08-12 실물: {@code title}은 "XSS와 CSRF", 본문 H1은 "CSRF와 XSS"였다.
     * 두 값이 따로 생성되므로 상시 위험이고, 목록에서 클릭한 제목과 열었을 때 제목이 달라진다.
     */
    @Test
    @DisplayName("제목 필드와 본문 H1이 다르면 경고 — 실물 2편에서 실제로 어긋났다")
    void warnsWhenTitleDiffersFromHeading() {
        // 실물의 제목은 줄표 부제가 붙어 있었는데(「XSS와 CSRF — 브라우저를 믿으면 생기는 일」)
        // 여기서는 걷어냈다. 그대로 두면 제목 형식 경고가 함께 떠서, 이 테스트가 보려는
        // <필드와 본문의 어긋남>이 경고 둘 중 하나로 묻힌다.
        List<DraftCheck> checks = DocumentDraftValidator.validate(
                "XSS와 CSRF의 방어 지점", "xss-and-csrf",
                body("CSRF와 XSS의 방어 지점", "본문"));

        assertThat(checks).singleElement().satisfies(c -> {
            assertThat(c.severity()).as("어느 쪽이 맞는지는 사람만 정한다 — 막지 않고 알리기만").
                    isEqualTo(DraftCheck.Severity.WARNING);
            assertThat(c.message()).contains("XSS와 CSRF").contains("CSRF와 XSS");
        });
    }

    @Test
    @DisplayName("공백·줄바꿈만 다른 제목은 경고하지 않는다 — 사람 눈에 같은데 울리면 경고를 무시하게 된다")
    void ignoresWhitespaceOnlyTitleDifference() {
        List<DraftCheck> checks = DocumentDraftValidator.validate(
                "캐시  쓰기 전략의  무효화 처리 방식", "cache-strategy", body(TITLE, "본문"));

        assertThat(checks).isEmpty();
    }

    /**
     * <b>제목 형식 검사</b>(2026-09-17). 사용자가 Flyway 문서 제목을 손으로 고친 것이 계기다 —
     * 「데이터베이스가 지금 어느 단계까지 바뀌어 있는지를 무엇으로 아는가」에서
     * 「데이터베이스 마이그레이션 도구의 버전 관리 기법 (Flyway)」로.
     *
     * <p>옛 제목은 규칙을 어긴 것이 아니라 <b>규칙대로 나온 것</b>이었다(프롬프트의 (O) 예시가
     * 의문문이었다). 그래서 프롬프트를 뒤집었고, 프롬프트만 고친 규칙이 조용히 돌아가는 것을
     * 막으려고 검증기에도 걸었다.
     */
    @Test
    @DisplayName("의문문 제목은 경고 — 목록에서 제목만 훑는 독자는 끝까지 읽어야 주제를 안다")
    void warnsWhenTitleIsAQuestion() {
        String question = "데이터베이스가 지금 어느 단계까지 바뀌어 있는지를 무엇으로 아는가";

        assertThat(DocumentDraftValidator.validate(question, "flyway-versioning", body(question, "본문")))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.severity()).as("제목은 승인 뒤 한 줄 고치면 된다 — 다시 뽑게 할 값이 아니다")
                            .isEqualTo(DraftCheck.Severity.WARNING);
                    assertThat(c.message()).as("무엇으로 바꾸라는 것인지가 메시지에 있어야 고친다")
                            .contains("명사구");
                });
    }

    @Test
    @DisplayName("줄표 부제가 붙은 제목은 경고 — 줄표가 필요하다는 건 제목이 둘을 담았다는 뜻이다")
    void warnsWhenTitleHasDashSubtitle() {
        String dashed = "캐시 쓰기 전략 — TTL은 시간으로, 무효화는 사건으로";

        assertThat(DocumentDraftValidator.validate(dashed, "cache-strategy", body(dashed, "본문")))
                .singleElement()
                .satisfies(c -> assertThat(c.message()).contains("줄표"));
    }

    /**
     * <b>걸리면 안 되는 것.</b> 한국어 의문형 어미를 넓게 잡으면 멀쩡한 명사구가 걸린다 —
     * "…하나"를 잡으려다 "선택지 하나"가, "…는가"를 잡으려다 "평가"가 걸리는 식이다.
     * 오탐이 섞인 경고는 사람이 경고 전체를 무시하게 만들어 검사 자체를 무력하게 만든다.
     *
     * <p>낱말 안의 붙임표를 통과시키는 것도 같은 이유다. "3-way 핸드셰이크"는 부제가 아니다.
     */
    @Test
    @DisplayName("명사구 제목은 통과한다 — 어미를 넓게 잡으면 멀쩡한 제목이 걸려 경고 전체가 무력해진다")
    void acceptsNounPhraseTitles() {
        List<String> fine = List.of(
                "데이터베이스 마이그레이션 도구의 버전 관리 기법 (Flyway)",
                "TCP 3-way 핸드셰이크의 연결 수립 절차",
                "정렬 알고리즘의 안정성 평가",
                "장애 대응 절차에서 가장 먼저 보는 것 하나");

        for (String title : fine) {
            assertThat(DocumentDraftValidator.validate(title, "cache-strategy", body(title, "본문")))
                    .as("명사구 제목 \"%s\"이 걸리면 안 된다", title)
                    .isEmpty();
        }
    }

    /**
     * 이 테스트가 없었다면 XSS·SQL 인젝션 같은 보안 주제 문서를 <b>영영 승인하지 못했을</b> 것이다.
     * 실제 2026-08-12 문서가 코드블록 안에 {@code <script>}와 {@code <form action=...>}을 담고 있었다.
     */
    @Test
    @DisplayName("코드블록 안의 HTML 태그는 통과한다 — 보안 문서에는 <script>가 정상적으로 들어간다")
    void allowsHtmlTagsInsideCodeBlock() {
        String content = body(TITLE, """
                XSS는 이렇게 저장된다.

                ```
                [공격자] 게시글 본문에 <script>alert(1)</script> 저장
                [evil.com] <form action="https://bank.com/transfer"> 자동 제출
                ```

                인라인으로도 쓴다: `<img onerror="...">`
                """);

        assertThat(DocumentDraftValidator.validate(TITLE, "xss-and-csrf", content)).isEmpty();
    }

    @Test
    @DisplayName("코드블록 밖의 HTML 태그는 차단한다 — 문서 화면이 innerHTML로 렌더링해 실제로 실행된다")
    void blocksHtmlTagsOutsideCodeBlock() {
        String content = body(TITLE, "본문 중간에 <script>alert(1)</script> 가 그대로 있다.");

        assertThat(DocumentDraftValidator.validate(TITLE, "cache-strategy", content))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.isBlocking()).isTrue();
                    assertThat(c.message()).as("무엇이 걸렸는지 보여 줘야 검수자가 찾아본다")
                            .contains("<script>");
                });
    }

    @Test
    @DisplayName("본문의 부등호를 태그로 오인하지 않는다 — 'a < b'나 'if (a<b) return c>d'는 태그가 아니다")
    void doesNotMistakeComparisonForTag() {
        String content = body(TITLE, "지연이 a < b 이고, if (a<b) return c>d 같은 식도 본문에 나온다.");

        assertThat(DocumentDraftValidator.validate(TITLE, "cache-strategy", content)).isEmpty();
    }

    /* ── 필수 절 ─────────────────────────────────────────────── */

    @Test
    @DisplayName("필수 절이 빠지면 차단 — 빠진 절 이름을 그대로 알려 준다")
    void blocksMissingRequiredSection() {
        String content = "# " + TITLE + "\n\n## 왜 필요한가\n내용\n\n## 한 줄로 정리하면\n요약";

        assertThat(DocumentDraftValidator.validate(TITLE, "cache-strategy", content))
                .anySatisfy(c -> {
                    assertThat(c.isBlocking()).isTrue();
                    assertThat(c.message()).contains("## 자주 하는 오해");
                });
    }

    @Test
    @DisplayName("본문에 H1이 아예 없으면 차단 — 비교할 대상이 없다는 건 구조가 무너졌다는 뜻")
    void blocksMissingHeading() {
        assertThat(DocumentDraftValidator.validate(TITLE, "cache-strategy", "## 왜 필요한가\n내용"))
                .anySatisfy(c -> {
                    assertThat(c.isBlocking()).isTrue();
                    assertThat(c.message()).contains("최상위 제목");
                });
    }

    /* ── slug ────────────────────────────────────────────────── */

    @Test
    @DisplayName("slug 형식 위반은 차단 — 승인 경로는 @Valid를 안 타므로 여기서 안 보면 그냥 통과한다")
    void blocksInvalidSlug() {
        assertThat(DocumentDraftValidator.validate(TITLE, "캐시 전략", body(TITLE, "본문")))
                .anySatisfy(c -> {
                    assertThat(c.isBlocking()).isTrue();
                    assertThat(c.message()).contains("slug");
                });
    }

    /* ── 분량 ────────────────────────────────────────────────── */

    /* ── 여러 건 ─────────────────────────────────────────────── */

    @Test
    @DisplayName("문제가 여럿이면 첫 건에서 멈추지 않고 전부 모아 준다 — 고치고 다시 뽑는 왕복을 줄인다")
    void collectsAllProblemsAtOnce() {
        List<DraftCheck> checks = DocumentDraftValidator.validate(
                "다른 제목", "Bad_Slug", "# 본문 제목\n\n## 왜 필요한가\n내용");

        // 이 본문에는 '## 어떤 때 통하지 않는가'가 없으므로 <입문편>으로 판정된다(editionOf).
        //
        // slug 형식 1 + 제목 불일치 1
        // + 빠진 입문편 필수 절 6(핵심 요약·바탕이 되는 개념·무엇인가·실무에서는 이렇게 쓴다·
        //   자주 하는 오해·한 줄로 정리하면)
        // + 형식 5(설계 근거 소제목 없음·용어 표 없음·짝짓기 재료 0행·본론 0개·코드 예제 0개) = 13
        // ('## 무엇인가'·'## 바탕이 되는 개념'의 형식 검사와 용어 표 복습 검사는 절이 아예 없으면
        //  건너뛴다 — 없는 절을 두고 "하이픈이 없다"고 말하면 원인이 흐려진다)
        //
        // 2026-09-01에 짝짓기 재료 경고가 늘어 13 → 14가 됐고, 2026-09-03에 필수 절이 8개에서
        // 7개로 줄면서(셋이 심화편으로 감, 둘이 새로 옴) 다시 13이 됐다. 개수를 세는 이 단언을
        // 그대로 두는 이유는 <검사를 늘릴 때 한 번은 여기서 멈추게> 하기 위해서다. 경고가 는 것을
        // 아무도 모르는 채 지나가면, 검수 화면이 어느새 읽히지 않는 목록이 된다.
        // 2026-10-10에 그림 없음 경고가 늘어 14가 됐다.
        // 2026-11-10에 참고 자료 없음 경고가 늘어 15가 됐다.
        // 2026-11-13에 짝짓기 재료·본론 개수·코드 예제 경고를 빼서 12가 됐다(실측에서 지금 문서에 0건).
        // 같은 날 「글의 주제」 상자 없음(차단)이 늘어 13이 됐다.
        assertThat(checks).hasSize(13);
        assertThat(checks).extracting(DraftCheck::message)
                .anyMatch(m -> m.contains("slug"))
                .anyMatch(m -> m.contains("본문 제목"))
                .anyMatch(m -> m.contains("## 자주 하는 오해"))
                .noneMatch(m -> m.contains("짝짓기"));
    }

    /* ── 형식 규칙 ───────────────────────────────────────────────
     * 프롬프트가 "이렇게 써라"라고 시켰지만 검사하는 곳이 없던 것들.
     * 2026-08-15 문서 한 편에서 아래 둘이 어긋난 채 승인까지 통과했고, 그 대가는
     * 하루 뒤 중급 배치가 근거 문서를 못 쓰고 폴백으로 떨어지는 것으로 돌아왔다. */

    @Test
    @DisplayName("'### 왜 이렇게 설계됐는가'가 없으면 알린다 — 없으면 다음 날 중급이 근거를 잃는다")
    void warnsWhenDesignRationaleHeadingIsMissing() {
        String without = body(TITLE, "본문").replace("### 왜 이렇게 설계됐는가", "### 다른 소제목");

        List<DraftCheck> checks = DocumentDraftValidator.validate(TITLE, "cache-strategy", without);

        assertThat(checks).extracting(DraftCheck::message)
                .anyMatch(m -> m.contains("'### 왜 이렇게 설계됐는가' 소제목이 없습니다"));
        assertThat(DocumentDraftValidator.hasBlocking(checks))
                .as("형식이 어긋났다고 막으면 그 주기 나흘이 통째로 날아간다")
                .isFalse();
    }

    /**
     * "없습니다"만 알리면 검수자가 무엇을 해야 할지 모른다. 실물은 {@code **왜 이렇게 설계됐는가**}
     * 라는 <b>굵은 글씨</b>였으므로, 그 사실까지 세어 주면 고칠 방법이 메시지 안에 있다.
     */
    @Test
    @DisplayName("굵은 글씨로 썼으면 그 사실까지 알린다 — 고칠 방법이 메시지에 있어야 한다")
    void tellsWhenRationaleWasWrittenInBold() {
        String bold = body(TITLE, "본문")
                .replace("### 왜 이렇게 설계됐는가", "**왜 이렇게 설계됐는가**");

        assertThat(DocumentDraftValidator.validate(TITLE, "cache-strategy", bold))
                .extracting(DraftCheck::message)
                .anyMatch(m -> m.contains("굵은 글씨로 1개"));
    }

    @Test
    @DisplayName("소제목이 여럿이면 알린다 — 프롬프트는 문서 전체에 1개를 요구한다")
    void warnsWhenRationaleHeadingAppearsTwice() {
        String twice = body(TITLE, "본문") + "\n### 왜 이렇게 설계됐는가\n- 또 다른 근거.\n";

        assertThat(DocumentDraftValidator.validate(TITLE, "cache-strategy", twice))
                .extracting(DraftCheck::message)
                .anyMatch(m -> m.contains("소제목이 2개입니다"));
    }

    /* ── 2026-11-10: 참고 자료 ─────────────────────────────────
     * 배치 모델은 인터넷을 볼 수 없어 없는 주소를 지어낼 수 있다. 막지 않고, 허용 목록 밖의
     * 링크만 짚어 검수자가 눌러 볼 곳을 좁힌다. */

    @Test
    @DisplayName("참고 자료 절이 없으면 알린다")
    void warnsWhenReferencesMissing() {
        String none = body(TITLE, "본문").substring(0, body(TITLE, "본문").indexOf("\n## 참고 자료"));

        assertThat(DocumentDraftValidator.validate(TITLE, "cache-strategy", none))
                .extracting(DraftCheck::message)
                .anyMatch(m -> m.contains("'## 참고 자료' 절이 없습니다"));
    }

    @Test
    @DisplayName("허용 목록 밖이거나 https가 아닌 링크를 짚는다 — 지어낸 주소일 수 있다")
    void flagsUntrustedReferenceLinks() {
        String doc = body(TITLE, "본문").replace(
                "- [RFC 9293](https://www.rfc-editor.org/rfc/rfc9293) — 연결 순서.",
                "- [어느 블로그](https://some-blog.example.com/tcp) — 정리 글.\n"
                        + "- [옛 주소](http://www.rfc-editor.org/rfc/rfc793) — 암호화 안 된 주소.\n"
                        + "- [OWASP](https://cheatsheetseries.owasp.org/index.html) — 하위 도메인은 받는다.");

        assertThat(DocumentDraftValidator.validate(TITLE, "cache-strategy", doc))
                .extracting(DraftCheck::message)
                .anyMatch(m -> m.contains("링크 2개가 허용 목록 밖이거나 https가 아닙니다")
                        && m.contains("some-blog.example.com") && m.contains("http://www.rfc-editor.org")
                        && !m.contains("owasp"));
    }

    @Test
    @DisplayName("허용 목록은 그 도메인과 하위 도메인만 받는다 — 이름만 비슷한 곳은 막는다")
    void trustedReferenceMatchesDomainBoundary() {
        assertThat(DocumentDraftValidator.isTrustedReference("https://www.rfc-editor.org/rfc/rfc1918")).isTrue();
        assertThat(DocumentDraftValidator.isTrustedReference("https://evil-rfc-editor.org/x")).isFalse();
        assertThat(DocumentDraftValidator.isTrustedReference("not a url")).isFalse();
    }

    /**
     * <b>절이 문장으로 시작하는가</b>(2026-09-16).
     *
     * <p>사용자 지적: "절과 절 사이가 툭툭 끊긴다. 절 여섯 개가 한 글이 아니라 여섯 개의
     * 글처럼 읽힌다." 원인은 문체가 아니라 <b>자리</b>였다 — 심화편의 주력 절 둘과
     * 「면접에서 이렇게 물어본다」가 모두 굵은 항목으로 곧장 시작한다. 앞 절이 끝나자마자
     * 새 목록이 시작되니 두 절을 잇는 문장이 들어설 자리가 아예 없다.
     *
     * <p>"매끄럽게 써라"는 셀 수 없어서 검증기에 넘길 수 없다. 대신 <b>절의 첫 줄이
     * 문장인가</b>는 원문에서 그대로 판정된다. 규칙을 셀 수 있는 모양으로 바꿔 검증기에
     * 넘기는 것이 이 저장소가 프롬프트 품질에 써 온 방법이다(docs/17).
     *
     * <p>예외 넷은 <b>원래 목록·표인 절</b>이다. 「핵심 요약」과 그다음 절은 글의 첫머리라
     * 받을 앞 절이 없고, 「용어 한눈에」는 표 자체가 내용이다. 여기에 문장을 요구하면
     * 잘 쓴 문서에 경고가 뜬다 — 오탐이 미탐보다 비싸다는 이 검증기의 오랜 방향이다.
     */
    @Test
    @DisplayName("절이 목록으로 곧장 시작하면 알린다 — 앞 절과 잇는 문장이 들어설 자리가 없다")
    void warnsWhenSectionStartsWithAList() {
        String abrupt = advancedBody(TITLE)
                .replace("지금까지는 조건이 맞을 때의 이야기다. 조건이 어긋나는 자리가 이만큼 있다.\n\n", "");

        assertThat(DocumentDraftValidator.validate(TITLE, "cache-strategy", abrupt))
                .extracting(DraftCheck::message)
                .as("다리 문장을 걷어내면 절이 **1. 첫째 조건**으로 곧장 시작한다")
                .anyMatch(m -> m.contains("## 어떤 때 통하지 않는가") && m.contains("문장으로 시작"));
    }

    @Test
    @DisplayName("절이 문장으로 시작하면 조용하다")
    void silentWhenSectionStartsWithASentence() {
        assertThat(DocumentDraftValidator.validate(TITLE, "cache-strategy", advancedBody(TITLE)))
                .extracting(DraftCheck::message)
                .as("fixture의 실패 조건 절은 다리 문장으로 시작한다")
                .noneMatch(m -> m.contains("## 어떤 때 통하지 않는가") && m.contains("문장으로 시작"));
    }

    /**
     * 예외 절에까지 문장을 요구하면 <b>모든 문서에 경고가 뜬다</b>. 늘 떠 있는 경고는
     * 진짜 차단이 왔을 때도 그러려니 하게 만든다 — 경고의 값어치가 0이 되는 길이다.
     */
    @Test
    @DisplayName("원래 목록·표인 절은 예외다 — 요약·머리말·용어표에 문장을 요구하면 모든 문서가 걸린다")
    void listBySectionDesignIsExempt() {
        assertThat(DocumentDraftValidator.validate(TITLE, "cache-strategy", advancedBody(TITLE)))
                .extracting(DraftCheck::message)
                .as("이 셋은 fixture에서 하이픈 목록·표로 시작하는데, 그게 프롬프트가 시킨 형식이다")
                .noneMatch(m -> m.contains("## 핵심 요약") && m.contains("문장으로 시작"))
                .noneMatch(m -> m.contains("## 이 글을 읽기 전에") && m.contains("문장으로 시작"))
                .noneMatch(m -> m.contains("용어 한눈에") && m.contains("문장으로 시작"));
    }

    /* ── 2026-08-18: 어려운 용어가 정의 없이 지나갔다 ─────────────
     * 사용자가 8/18 고급 문제를 읽고 "모르는 용어가 설명 없이 나온다"고 지적했다.
     * 8/15 실물을 세어 보니 정의된 용어는 '## 무엇인가'의 5개뿐이고, 뒤쪽 절에서 처음 나온
     * 낙관적 잠금·공유 락·배타 락·갭 락·SELECT ... FOR UPDATE 등 아홉 개가 그냥 지나갔다.
     *
     * 프롬프트에는 이미 "정의 없이 지나가는 용어가 하나도 없어야 한다"가 있었다.
     * 문구로는 안 되니 세는 쪽으로 넘긴 것인데, 8/15의 하이픈 사고와 정확히 같은 수순이다. */

    /**
     * <b>{@code ###}로 쓴 용어 표는 입문편의 필수 절을 채우지 못한다</b> — 부분 문자열 함정.
     *
     * <p>{@code "## 용어 한눈에"}는 {@code "### 용어 한눈에"} 안에 그대로 들어 있다. 절 존재를
     * {@code contains}로 보던 코드는 본론 안 소제목으로 잘못 넣은 표를 <b>정상으로 통과</b>시켰다.
     * 프롬프트가 "본론 절 안의 소제목으로 넣지 마라"라고 못 박은 바로 그 실수다.
     *
     * <p>두 수준이 함께 쓰이는 제목은 이것 하나뿐이라, 이 테스트가 없으면 다음에 절 검사를
     * 손볼 때 다시 {@code contains}로 돌아가도 아무도 모른다.
     */
    @Test
    @DisplayName("용어 표를 소제목(###)으로 넣으면 필수 절로 치지 않는다 — 부분 문자열로 통과하면 안 된다")
    void doesNotAcceptGlossaryAsSubheading() {
        String demoted = body(TITLE, "본문").replace("## 용어 한눈에", "### 용어 한눈에");

        assertThat(DocumentDraftValidator.validate(TITLE, "cache-strategy", demoted))
                .anySatisfy(c -> {
                    assertThat(c.isBlocking()).isTrue();
                    assertThat(c.message()).contains("## 용어 한눈에");
                });
    }

    /**
     * 심화편은 표가 없으면 <b>경고</b>다. 본론 끝의 소제목이라 빠져도 글이 성립하기 때문이다.
     */
    @Test
    @DisplayName("심화편에 용어 표가 없으면 알린다 — 뒤쪽 절 용어를 둘 자리가 사라진다")
    void warnsWhenGlossaryTableIsMissing() {
        String noGlossary = advancedBody(TITLE).replace("### 용어 한눈에", "### 다른 소제목");

        assertThat(DocumentDraftValidator.validate(TITLE, "cache-strategy", noGlossary))
                .extracting(DraftCheck::message)
                .anyMatch(m -> m.contains("용어 한눈에' 표가 없습니다"));
    }

    /**
     * 입문편은 <b>차단</b>이다 — 2026-09-03 후속 개정에서 이 표가 최상위 필수 절로 올라왔다.
     *
     * <p>등급을 올린 이유: 이 표가 초급 재료의 집합소다. 없으면 다음 날 초급 출제가 그만큼 빈다.
     * 등급이 갈리므로 <b>경고가 두 번 뜨지 않는지</b>도 함께 본다 — 필수 절 검사와 표 존재 검사가
     * 같은 결함을 두 번 말하면 검수자는 무엇을 고쳐야 하는지 헷갈린다.
     */
    @Test
    @DisplayName("입문편에 용어 표가 없으면 차단한다 — 초급 재료의 집합소라 경고로는 부족하다")
    void blocksWhenBeginnerGlossaryIsMissing() {
        String noGlossary = body(TITLE, "본문").replace("## 용어 한눈에", "## 다른 절");

        List<DraftCheck> checks = DocumentDraftValidator.validate(TITLE, "cache-strategy", noGlossary);

        assertThat(checks).anySatisfy(c -> {
            assertThat(c.isBlocking()).isTrue();
            assertThat(c.message()).contains("## 용어 한눈에");
        });
        assertThat(checks).extracting(DraftCheck::message)
                .as("한 결함에 메시지가 둘 뜨면 안 된다")
                .noneMatch(m -> m.contains("표가 없습니다"));
    }

    /* ── 2026-08-23: 기본 개념과 코드 예제 ────────────────────────
     * 사용자가 8/23 실물(「연결이 안 될 때 어느 계층에서 끊겼는가」)을 읽고 두 가지를 지적했다.
     * ① OSI 7계층 자체를 설명하지 않는다 — 실무 상황만 있고 기본 개념이 없다.
     * ② 블로그에 그대로 올릴 만한 글이 되려면 코드 예제가 필요하다.
     *
     * 둘 다 프롬프트로 풀었지만, 프롬프트만 고치면 몇 주 뒤 조용히 돌아온다는 것을
     * 이 저장소는 세 번 겪었다(8/15 하이픈, 8/18 용어 정의, 8/18 절 형식). 그래서 센다. */

    /* ── 2026-10-10: 그림(```mermaid) ─────────────────────────────
     * 그날까지 나온 문서 30편에 그림이 한 장도 없었다. 프롬프트가 입문편에 그림 1개를 요구하게
     * 되면서 세는 곳을 함께 둔다 — 코드 예제 때와 같이, 허용만으로는 나오지 않는다. */

    /** 도우미 문서의 그림 블록 — 테스트마다 이 조각을 지우거나 바꿔 쓴다. */
    private static final String DIAGRAM =
            "```mermaid\nsequenceDiagram\n  participant C as 클라이언트\n  participant S as 서버\n  C->>S: 요청한다\n```";

    @Test
    @DisplayName("입문편에 그림이 없으면 알린다 — 30편에 그림이 한 장도 없었다")
    void warnsWhenBeginnerHasNoDiagram() {
        String noDiagram = body(TITLE, "본문").replace(DIAGRAM, "그림 없이 글로 설명한다.");

        List<DraftCheck> checks = DocumentDraftValidator.validate(TITLE, "cache-strategy", noDiagram);

        assertThat(checks).extracting(DraftCheck::message)
                .anyMatch(m -> m.contains("그림이 없습니다"));
        assertThat(DocumentDraftValidator.hasBlocking(checks))
                .as("그림 하나 때문에 나흘치 문서를 버리지 않는다")
                .isFalse();
    }

    @Test
    @DisplayName("손으로 그린 그림 파일도 그림으로 센다 — 네트워크 입문편은 SVG만 쓴 편이 있다")
    void handDrawnImageCountsAsDiagram() {
        String svgOnly = body(TITLE, "본문").replace(DIAGRAM, "![요청이 서버로 가는 길](/images/docs/cache-flow.svg)");

        assertThat(DocumentDraftValidator.validate(TITLE, "cache-strategy", svgOnly))
                .extracting(DraftCheck::message)
                .noneMatch(m -> m.contains("그림이 없습니다"));
    }

    /** 심화편의 그림은 프롬프트가 선택으로 뒀다 — 없다고 울리면 매번 헛울린다. */
    @Test
    @DisplayName("심화편은 그림이 없어도 조용하다 — 프롬프트가 선택으로 뒀다")
    void advancedNeedsNoDiagram() {
        assertThat(DocumentDraftValidator.validate(TITLE, "cache-strategy-advanced", advancedBody(TITLE)))
                .extracting(DraftCheck::message)
                .noneMatch(m -> m.contains("그림"));
    }

    /** 종류 선언이 틀리면 그림이 통째로 안 그려지고, 읽는 사람은 원문을 코드로 본다. */
    @Test
    @DisplayName("화면이 못 그리는 종류면 알린다 — 그림 대신 원문이 코드로 보인다")
    void warnsOnUnsupportedDiagramType() {
        String pie = body(TITLE, "본문").replace("sequenceDiagram", "pie title 비율");

        assertThat(DocumentDraftValidator.validate(TITLE, "cache-strategy", pie))
                .extracting(DraftCheck::message)
                .anyMatch(m -> m.contains("1번째 그림의 첫 줄이 \"pie title 비율\"입니다"));
    }

    /**
     * 2026-11-08에 gantt·xychart-beta·block-beta·packet-beta를 더했다. 문서 화면의 mermaid 11.4.1이
     * 이 넷을 그리는 것을 패킷 교환 두 편으로 확인했는데, 검증기만 "원문이 코드로 보인다"고 헛울렸다.
     */
    @Test
    @DisplayName("허용한 일곱 종류는 조용하다 — 화면이 그리는 종류에 헛울리면 경고를 안 보게 된다")
    void acceptsAllowedDiagramTypes() {
        for (String header : List.of("flowchart TD", "flowchart LR", "sequenceDiagram", "stateDiagram-v2",
                "gantt", "xychart-beta", "block-beta", "packet-beta")) {
            String doc = body(TITLE, "본문").replace("sequenceDiagram", header);

            assertThat(DocumentDraftValidator.validate(TITLE, "cache-strategy", doc))
                    .as(header)
                    .isEmpty();
        }
    }

    /* ── 2026-08-23: 셸 주석이 마크다운 제목과 똑같이 생겼다 ────────
     * 코드 예제를 허용한 그날 첫 실물(TIME_WAIT 문서)에 이런 줄이 들어 있었다.
     *
     *   ```bash
     *   # 상태별 소켓 개수를 많은 순으로 센다
     *   ```
     *
     * H1_PATTERN에게 이 줄은 문서 제목이고, "## "로 시작하는 주석은 H2_PATTERN에게 절 제목이다.
     * 코드 예제가 금지돼 있던 동안에는 없던 문제라, 금지를 푼 개정과 짝으로 고쳐야 했다. */

    /**
     * H1이 없는 문서에서 셸 주석이 <b>제목 행세</b>를 하는지.
     *
     * <p>이게 이 계열에서 가장 나쁜 경우다. 구조가 무너진 문서(제목 없음 = 차단)가
     * <b>제목이 다르다는 경고</b>로 내려앉아 승인이 통과한다. 실패가 아니라 등급이
     * 조용히 낮아지는 종류라, 사람이 경고를 한 번 넘기면 그대로 나간다.
     */
    @Test
    @DisplayName("코드블록 안의 '# 주석'을 제목으로 세지 않는다 — 차단이 경고로 내려앉는다")
    void doesNotMistakeShellCommentForHeading() {
        // replace(String, String)를 쓴다 — replaceFirst는 치환 문자열의 $1을 그룹 참조로 읽어
        // 셸 예제가 든 이 테스트에서 IndexOutOfBounds로 터진다(실제로 한 번 겪었다).
        String noHeading = body(TITLE, "본문").replace("# " + TITLE, """
                ```bash
                # 상태별 소켓 개수를 많은 순으로 센다
                ss -ant | awk 'NR>1 {print $1}' | sort | uniq -c
                ```""");

        assertThat(DocumentDraftValidator.validate(TITLE, "cache-strategy", noHeading))
                .as("셸 주석을 제목으로 세면 '최상위 제목이 없습니다'가 아예 안 뜬다")
                .extracting(DraftCheck::message)
                .anyMatch(m -> m.contains("최상위 제목"));
    }

    /**
     * 코드블록 안의 {@code ## } 주석에서 절이 <b>먼저 끊기는지</b>.
     *
     * <p>이쪽은 오탐이라 더 자주 터진다 — 절이 실제보다 짧아 보여 멀쩡한 문서에
     * "바탕이 되는 개념이 얇다"·"깨지는 조건이 모자라다"가 뜬다. 이 클래스가 오래
     * 경계해 온 실패 방식 그대로다(헛울리는 경고는 경고 전체를 무력하게 만든다).
     */
    @Test
    @DisplayName("코드블록 안의 '## 주석'에서 절이 끊기지 않는다 — 멀쩡한 문서에 헛경고가 뜬다")
    void doesNotSplitSectionOnShellComment() {
        // 깨지는 조건 일곱 개 <앞>에 '## '로 시작하는 주석이 든 코드블록을 끼운다.
        // 절이 여기서 끊기면 뒤따르는 일곱 항목이 통째로 다른 절로 밀려 0개로 세어진다.
        String withComment = advancedBody(TITLE).replace("**1. 첫째 조건**", """
                ```bash
                ## 커널 파라미터를 확인한다
                sysctl net.ipv4.ip_local_port_range
                ```

                **1. 첫째 조건**""");

        assertThat(DocumentDraftValidator.validate(TITLE, "cache-strategy", withComment))
                .extracting(DraftCheck::message)
                .as("주석에서 끊기면 항목이 0개로 세어져 헛경고가 뜬다")
                .noneMatch(m -> m.contains("어떤 때 통하지 않는가"))
                .as("본론 섹션 수도 주석 때문에 부풀면 안 된다")
                .noneMatch(m -> m.contains("본론 섹션이"));
    }

    /**
     * 필수 절이 <b>코드 주석으로만</b> 있으면 없는 것으로 봐야 한다.
     * 원문에서 {@code contains}로 찾으면 빈껍데기 문서가 통과한다.
     */
    @Test
    @DisplayName("코드 주석에 적힌 절 이름은 필수 절로 치지 않는다 — 빈껍데기가 통과한다")
    void doesNotCountSectionNamesInsideCode() {
        String faked = body(TITLE, "본문").replace("## 한 줄로 정리하면\n\"한 줄 요약.\"", """
                ```bash
                ## 한 줄로 정리하면
                ```""");

        assertThat(DocumentDraftValidator.validate(TITLE, "cache-strategy", faked))
                .anySatisfy(c -> {
                    assertThat(c.isBlocking()).isTrue();
                    assertThat(c.message()).contains("## 한 줄로 정리하면");
                });
    }

    /**
     * 오탐이 나면 경고가 매번 뜨고, 그러면 사람이 경고 자체를 안 보게 된다.
     * 이 저장소가 이미 겪은 실패 방식이라 <b>정상 문서에 조용한지</b>를 따로 못 박는다.
     */
    @Test
    @DisplayName("규칙을 다 지킨 문서에는 아무 소리도 안 난다 — 오탐이 경고를 무력화한다")
    void staysQuietOnAWellFormedDocument() {
        assertThat(DocumentDraftValidator.validate(TITLE, "cache-strategy", body(TITLE, "본문")))
                .isEmpty();
    }

    /* ── 도우미 ──────────────────────────────────────────────── */

    /* ── 2026-09-03: 입문편·심화편으로 갈렸다 ─────────────────────
     * 한 편이 초급·중급·고급 재료를 모두 담느라 입문자가 못 읽는 밀도로 굳었고,
     * 09-03 실물에서 레지스터·시그널이 각각 12·13번 나오면서 정의는 한 번도 없었다.
     * 편에 따라 도는 검사가 갈리므로, 아래 셋이 그 분업을 지킨다. */

    /**
     * 심화편도 <b>조용히 통과</b>해야 한다. 입문편 전용 검사(바탕이 되는 개념 분량,
     * '## 무엇인가'의 하이픈 목록, 설계 근거 소제목, 복습표)가 심화편에서 돌면
     * 지시대로 쓴 문서에 경고가 넷 붙는다 — 이 클래스가 가장 경계하는 오탐이다.
     */
    @Test
    @DisplayName("심화편도 지적 사항이 없다 — 입문편 전용 검사가 심화편에서 돌면 헛경고가 넷 붙는다")
    void passesValidAdvancedDocument() {
        assertThat(DocumentDraftValidator.validate(TITLE, "cache-strategy", advancedBody(TITLE)))
                .isEmpty();
    }

    /**
     * <b>본문이 정의 줄 말고 다른 방식으로 푼 용어에는 조용해야 한다</b> — 2026-09-07 실물이 가르쳐 준 것.
     *
     * <p>이 검사의 첫 판은 "표보다 앞에 {@code - **용어** — 뜻} 꼴 정의 줄이 있는가"였고,
     * 첫 실물 입문편에서 <b>여섯 건이 헛울렸다</b>. 여섯 다 본문에서 멀쩡히 설명돼 있었지만
     * 형식이 달랐다 — 절 제목으로 설명한 것, 비교 표의 칸으로 설명한 것, 줄표 대신 조사로 이은 것.
     *
     * <p>좋은 글은 용어를 여러 방식으로 푼다. 그러니 <b>정의의 형식을 세는 검사는 반드시 헛울린다</b>.
     * 여기서 지키는 것은 규칙의 문구가 아니라 그 교훈이다 — 다음에 이 검사를 조이려는 사람이
     * 형식 쪽으로 되돌아가면 이 테스트가 먼저 빨간불이 된다.
     */
    @Test
    @DisplayName("정의 줄이 아닌 방식으로 푼 용어에는 조용하다 — 형식을 세는 검사는 반드시 헛울린다")
    void staysQuietWhenTermsAreExplainedWithoutDefinitionLines() {
        // 실물이 쓴 세 가지 방식을 그대로 흉내 낸다: 절 제목, 표 칸, 조사로 이은 문장.
        String otherForms = body(TITLE, "본문")
                .replace("- **다섯째 용어** — 한 문장 정의.", "**다섯째 용어(fifth)** 는 이런 것이다.")
                .replace("- **여섯째 용어** — 한 문장 정의.", "| 여섯째 용어 | 이런 것이다 |")
                .replace("- **일곱째 용어** — 한 문장 정의.", "### 일곱째 용어를 쓰는 자리");

        assertThat(DocumentDraftValidator.validate(TITLE, "cache-strategy", otherForms))
                .extracting(DraftCheck::message)
                .noneMatch(m -> m.contains("용어 한눈에"));
    }

    /**
     * <b>모든 검사를 통과하는</b> 최소 문서. 검사하려는 항목만 골라 망가뜨려 쓴다.
     *
     * <p>2026-08-17에 형식 규칙 넷을 추가하면서 이 문서도 함께 채웠다 — 전에는 본론이 0개이고
     * '어떤 때 통하지 않는가' 항목이 1개인, <b>새 기준으로는 경고가 셋 붙는</b> 문서였다.
     * 그대로 두면 "이 항목만 검사한다"던 테스트들이 딴 경고까지 세게 되고,
     * 그러면 무엇을 재고 있었는지 알 수 없어진다.
     */
    private String body(String heading, String content) {
        return """
                # %s

                > **글의 주제**
                >
                > 확인용 글의 주제.

                ## 핵심 요약
                - **첫째 요점** — 결론 한 문장.
                - **둘째 요점** — 결론 한 문장.

                ## 바탕이 되는 개념
                %s

                ## 무엇인가
                이것은 이러이러한 것이다.

                - **첫째 용어(first)** — 한 문장 정의.
                - **둘째 용어(second)** — 한 문장 정의.

                ## 왜 필요한가
                %s

                ## 첫째 갈래
                본론 설명. 아래 예제가 그 동작을 보여 준다.

                - **셋째 용어** — 한 문장 정의.
                - **넷째 용어** — 한 문장 정의.

                ```java
                var result = compute();
                ```

                그래서 결과가 이렇게 나온다. 두 쪽이 주고받는 순서는 아래 그림과 같다.

                ```mermaid
                sequenceDiagram
                  participant C as 클라이언트
                  participant S as 서버
                  C->>S: 요청한다
                ```

                그림에서 화살표의 방향을 본다.

                ### 왜 이렇게 설계됐는가
                - 다른 선택지를 두고 이렇게 판단했다.

                ## 둘째 갈래
                본론 설명. 설정은 이렇게 준다.

                - **다섯째 용어** — 한 문장 정의.
                - **여섯째 용어** — 한 문장 정의.
                - **일곱째 용어** — 한 문장 정의.

                ```sql
                SELECT 1
                ```

                그래서 이렇게 동작한다.

                ```bash
                echo done
                ```

                그래서 이렇게 확인한다.

                ## 용어 한눈에

                | 용어 | 한 줄 뜻 | 언제 쓰나 |
                |---|---|---|
                | 셋째 용어 | 한 줄 뜻. | 이럴 때. |
                | 넷째 용어 | 한 줄 뜻. | 이럴 때. |
                | 다섯째 용어 | 한 줄 뜻. | 이럴 때. |
                | 여섯째 용어 | 한 줄 뜻. | 이럴 때. |
                | 일곱째 용어 | 한 줄 뜻. | 이럴 때. |

                ## 실무에서는 이렇게 쓴다
                여기까지가 원리다. 원리를 알아도 언제 꺼내 쓸지는 따로 익혀야 한다.

                - 이런 상황에서 이렇게 쓴다.

                ## 자주 하는 오해
                쓰는 자리를 알고 나면 그 다음에 걸리는 것은 잘못 외운 사실이다.

                **"믿기 쉬운 문장이다"**
                그렇지 않다. 맞는 사실은 이것이다.

                ## 한 줄로 정리하면
                "한 줄 요약."

                ## 참고 자료
                이 글의 동작은 아래 문서에서 확인할 수 있다.

                - [RFC 9293](https://www.rfc-editor.org/rfc/rfc9293) — 연결 순서.
                """.formatted(heading, FOUNDATION, content);
    }

    /**
     * <b>모든 검사를 통과하는 심화편</b>(2026-09-03). 편에 따라 도는 검사가 갈리므로 짝이 필요하다.
     *
     * <p>{@code ## 어떤 때 통하지 않는가}가 곧 편의 표지다({@code ClaudeDocumentGenerator.editionOf}).
     * 이 절을 입문편 fixture에 남겨 두면 <b>입문편 검사가 하나도 안 도는</b> 상태로 스무 개
     * 넘는 테스트가 통과해 버린다 — 초록불인데 아무것도 안 재는, 가장 나쁜 종류의 통과다.
     */
    /**
     * 심화편 「어떤 때 통하지 않는가」의 항목 부분을 갈아 끼운다. 첫머리 문장은 남기고,
     * 장면 소제목 하나 아래에 주어진 항목을 둔다(장면 꼴 검사가 절 이름으로 헛울리지 않게).
     */
    private String withFailureItems(String doc, String items) {
        int start = doc.indexOf("## 어떤 때 통하지 않는가\n");
        int intro = doc.indexOf("\n\n", start) + 2;
        int end = doc.indexOf("## 면접에서 이렇게 물어본다");
        return doc.substring(0, intro) + "### 장면 ① 갈래\n\n갈래를 연다.\n\n" + items + "\n\n" + doc.substring(end);
    }

    private String advancedBody(String heading) {
        return """
                # %s

                > **글의 주제**
                >
                > 확인용 글의 주제.

                ## 핵심 요약
                - **첫째 요점** — 결론 한 문장.
                - **둘째 요점** — 결론 한 문장.

                ## 이 글을 읽기 전에
                - 입문편에서 이것을 다뤘다.
                - 그리고 저것도 다뤘다.

                ## 실무에서 어디에 나타나는가
                입문편이 세운 개념은 실제 코드에서 몇 군데로만 드러난다.

                **첫째 자리**
                어느 자리에 나타나는가.
                거기서 이 개념이 무엇을 정하는가.

                ## 깊은 동작
                본론 설명. 아래 예제가 그 동작을 보여 준다.

                ```java
                var result = compute();
                ```

                그래서 결과가 이렇게 나온다.

                ```sql
                SELECT 1
                ```

                그래서 이렇게 동작한다.

                ### 용어 한눈에

                | 용어 | 한 줄 뜻 | 언제 쓰나 |
                |---|---|---|
                | 셋째 용어 | 한 줄 뜻. | 이럴 때. |
                | 넷째 용어 | 한 줄 뜻. | 이럴 때. |
                | 다섯째 용어 | 한 줄 뜻. | 이럴 때. |
                | 여섯째 용어 | 한 줄 뜻. | 이럴 때. |
                | 일곱째 용어 | 한 줄 뜻. | 이럴 때. |

                ## 어떤 때 통하지 않는가
                지금까지는 조건이 맞을 때의 이야기다. 조건이 어긋나는 자리가 이만큼 있다.

                ### 장면 ① 첫째 갈래

                첫째 갈래는 이렇게 시작된다.

                #### 첫째 조건 이야기

                상황과 벌어진 일을 이야기로 쓴다.

                #### 둘째 조건

                - `증상` 언제 터지는가.
                - `원인` 왜 그런가.
                - `대처` 어떻게 막는가.

                #### 셋째 조건

                - `증상` 언제 터지는가.
                - `원인` 왜 그런가.
                - `대처` 어떻게 막는가.

                ### 장면 ② 둘째 갈래

                둘째 갈래는 이렇게 시작된다.

                #### 넷째 조건 이야기

                상황과 벌어진 일을 이야기로 쓴다.

                #### 다섯째 조건

                - `증상` 언제 터지는가.
                - `원인` 왜 그런가.
                - `대처` 어떻게 막는가.

                #### 여섯째 조건

                - `증상` 언제 터지는가.
                - `원인` 왜 그런가.
                - `대처` 어떻게 막는가.

                #### 일곱째 조건

                - `증상` 언제 터지는가.
                - `원인` 왜 그런가.
                - `대처` 어떻게 막는가.

                ## 면접에서 이렇게 물어본다
                위의 조건들은 면접에서 이런 형태의 질문으로 나온다.

                **Q. 무엇인가?**
                답변 요점.

                ## 면접 한 줄 요약
                한 줄 요약.

                ## 참고 자료
                이 글의 동작은 아래 문서에서 확인할 수 있다.

                - [MySQL 문서](https://dev.mysql.com/doc/) — 잠금 동작.
                """.formatted(heading);
    }
}
