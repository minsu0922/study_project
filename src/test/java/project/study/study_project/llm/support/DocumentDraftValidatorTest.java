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

    private static final String TITLE = "캐시 전략 — 읽기는 빠르게, 쓰기는 정확하게";

    @Test
    @DisplayName("정상 문서는 지적 사항이 없다")
    void passesValidDocument() {
        List<DocumentCheck> checks =
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
        List<DocumentCheck> checks = DocumentDraftValidator.validate(
                "XSS와 CSRF — 브라우저를 믿으면 생기는 일", "xss-and-csrf",
                body("CSRF와 XSS — 브라우저를 믿으면 생기는 일", "본문"));

        assertThat(checks).singleElement().satisfies(c -> {
            assertThat(c.severity()).as("어느 쪽이 맞는지는 사람만 정한다 — 막지 않고 알리기만").
                    isEqualTo(DocumentCheck.Severity.WARNING);
            assertThat(c.message()).contains("XSS와 CSRF").contains("CSRF와 XSS");
        });
    }

    @Test
    @DisplayName("공백·줄바꿈만 다른 제목은 경고하지 않는다 — 사람 눈에 같은데 울리면 경고를 무시하게 된다")
    void ignoresWhitespaceOnlyTitleDifference() {
        List<DocumentCheck> checks = DocumentDraftValidator.validate(
                "캐시  전략 — 읽기는 빠르게,  쓰기는 정확하게", "cache-strategy", body(TITLE, "본문"));

        assertThat(checks).isEmpty();
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
        String content = "# " + TITLE + "\n\n## 왜 필요한가\n내용\n\n## 면접 한 줄 요약\n요약";

        assertThat(DocumentDraftValidator.validate(TITLE, "cache-strategy", content))
                .anySatisfy(c -> {
                    assertThat(c.isBlocking()).isTrue();
                    assertThat(c.message()).contains("## 면접에서 이렇게 물어본다");
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

    @Test
    @DisplayName("권장 분량 초과는 경고에 그친다 — 실측이 매번 넘겼고, 억지로 줄이면 좋은 내용이 잘린다")
    void warnsOnlyWhenOverRecommendedLength() {
        String content = body(TITLE, "가".repeat(DocumentDraftValidator.WARN_LENGTH));

        assertThat(DocumentDraftValidator.validate(TITLE, "cache-strategy", content))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.severity()).isEqualTo(DocumentCheck.Severity.WARNING);
                    assertThat(c.message()).contains("권장 분량");
                });
    }

    @Test
    @DisplayName("하드 상한을 넘으면 차단 — 여기까지 오면 '길게 잘 쓴 것'이 아니라 지시를 무시한 것")
    void blocksOverHardLimit() {
        String content = body(TITLE, "가".repeat(DocumentDraftValidator.MAX_LENGTH));

        assertThat(DocumentDraftValidator.validate(TITLE, "cache-strategy", content))
                .singleElement()
                .satisfies(c -> assertThat(c.isBlocking()).isTrue());
    }

    /* ── 여러 건 ─────────────────────────────────────────────── */

    @Test
    @DisplayName("문제가 여럿이면 첫 건에서 멈추지 않고 전부 모아 준다 — 고치고 다시 뽑는 왕복을 줄인다")
    void collectsAllProblemsAtOnce() {
        List<DocumentCheck> checks = DocumentDraftValidator.validate(
                "다른 제목", "Bad_Slug", "# 본문 제목\n\n## 왜 필요한가\n내용");

        // slug 형식 1 + 제목 불일치 1
        // + 빠진 필수 절 5(무엇인가·실무에서는 이렇게 쓴다·언제 깨지는가·면접 질문·한 줄 요약)
        // + 형식 3(설계 근거 소제목 없음·용어 표 없음·본론 0개) = 10
        // ('## 무엇인가'와 '## 언제 깨지는가'의 형식 검사는 절이 아예 없으면 건너뛴다 —
        //  없는 절을 두고 "하이픈이 없다"고 말하면 원인이 흐려진다)
        assertThat(checks).hasSize(10);
        assertThat(checks).extracting(DocumentCheck::message)
                .anyMatch(m -> m.contains("slug"))
                .anyMatch(m -> m.contains("본문 제목"))
                .anyMatch(m -> m.contains("## 면접에서 이렇게 물어본다"));
    }

    /* ── 형식 규칙 ───────────────────────────────────────────────
     * 프롬프트가 "이렇게 써라"라고 시켰지만 검사하는 곳이 없던 것들.
     * 2026-08-15 문서 한 편에서 아래 둘이 어긋난 채 승인까지 통과했고, 그 대가는
     * 하루 뒤 중급 배치가 근거 문서를 못 쓰고 폴백으로 떨어지는 것으로 돌아왔다. */

    @Test
    @DisplayName("'### 왜 이렇게 설계됐는가'가 없으면 알린다 — 없으면 다음 날 중급이 근거를 잃는다")
    void warnsWhenDesignRationaleHeadingIsMissing() {
        String without = body(TITLE, "본문").replace("### 왜 이렇게 설계됐는가", "### 다른 소제목");

        List<DocumentCheck> checks = DocumentDraftValidator.validate(TITLE, "cache-strategy", without);

        assertThat(checks).extracting(DocumentCheck::message)
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
                .extracting(DocumentCheck::message)
                .anyMatch(m -> m.contains("굵은 글씨로 1개"));
    }

    @Test
    @DisplayName("소제목이 여럿이면 알린다 — 프롬프트는 문서 전체에 1개를 요구한다")
    void warnsWhenRationaleHeadingAppearsTwice() {
        String twice = body(TITLE, "본문") + "\n### 왜 이렇게 설계됐는가\n- 또 다른 근거.\n";

        assertThat(DocumentDraftValidator.validate(TITLE, "cache-strategy", twice))
                .extracting(DocumentCheck::message)
                .anyMatch(m -> m.contains("소제목이 2개입니다"));
    }

    /**
     * 마크다운의 홑줄바꿈은 문단을 끊지 않아, 하이픈 없이 쓴 용어 정의가 화면에서 한 문단으로
     * 뭉친다. 프롬프트에 두 번 적었는데도 안 지켜졌다(커밋 bafb2e9가 고치려다 실패) —
     * 규칙 옆 예시에 하이픈이 없어서였다. 세는 쪽이 확실하다.
     */
    @Test
    @DisplayName("용어 정의에 하이픈이 없으면 알린다 — 화면에서 한 문단으로 뭉친다")
    void warnsWhenTermListHasNoBullets() {
        String noBullets = body(TITLE, "본문")
                .replace("- **첫째 용어(first)**", "**첫째 용어(first)**")
                .replace("- **둘째 용어(second)**", "**둘째 용어(second)**");

        assertThat(DocumentDraftValidator.validate(TITLE, "cache-strategy", noBullets))
                .extracting(DocumentCheck::message)
                .anyMatch(m -> m.contains("하이픈 목록이 아닙니다"));
    }

    /**
     * 본론은 제목이 주제마다 달라 {@code REQUIRED_SECTIONS}로 못 박을 수 없다. 그래서
     * <b>본론이 통째로 비어도 통과하는</b> 구멍이 있었다. 이름을 못 박으면 개수를 센다.
     */
    @Test
    @DisplayName("본론이 얇으면 알린다 — 필수 절 목록으로는 본론의 부재를 못 잡는다")
    void warnsWhenBodySectionsAreThin() {
        String thin = body(TITLE, "본문")
                .replace("## 첫째 갈래", "### 첫째 갈래")
                .replace("## 둘째 갈래", "### 둘째 갈래");

        assertThat(DocumentDraftValidator.validate(TITLE, "cache-strategy", thin))
                .extracting(DocumentCheck::message)
                .anyMatch(m -> m.contains("본론 섹션이 0개"));
    }

    /**
     * 고급 문제의 재료. 2026-08-14에 고급 날 재료가 말라 5개를 요청해 3개만 받았다.
     * 그 뒤 프롬프트에 "5가지 이상"을 박았지만 세는 곳은 없었다.
     */
    @Test
    @DisplayName("'언제 깨지는가' 항목이 모자라면 알린다 — 고급 날 재료가 마른다")
    void warnsWhenFailureModesAreTooFew() {
        String few = body(TITLE, "본문")
                .replace("**4. 넷째 조건**\n", "")
                .replace("**5. 다섯째 조건**\n", "");

        assertThat(DocumentDraftValidator.validate(TITLE, "cache-strategy", few))
                .extracting(DocumentCheck::message)
                .anyMatch(m -> m.contains("'## 언제 깨지는가'의 항목이 3개"));
    }

    /**
     * 항목 형식이 프롬프트에 지정돼 있지 않다. 실물은 {@code **1. 제목**}으로 왔지만 다음 문서는
     * 다른 형식일 수 있어, 형식 하나만 인정하면 <b>멀쩡한 문서에 경고가 뜬다</b>.
     */
    @Test
    @DisplayName("항목 형식이 달라도 센다 — 형식 하나만 인정하면 멀쩡한 문서에 경고가 뜬다")
    void countsFailureModesInAnyFormat() {
        // 다섯 항목을 통째로 다른 형식으로 갈아 끼운다. 하나만 바꿔서는 나머지 넷이
        // 받쳐 줘서 통과하므로, 그 형식을 정말 세는지 알 수 없다.
        String base = body(TITLE, "본문");
        String written = """
                **1. 첫째 조건**
                **2. 둘째 조건**
                **3. 셋째 조건**
                **4. 넷째 조건**
                **5. 다섯째 조건**""";

        for (String form : List.of(
                "### 하나\n### 둘\n### 셋\n### 넷\n### 다섯",       // 소제목
                "- **하나**\n- **둘**\n- **셋**\n- **넷**\n- **다섯**", // 하이픈+굵게
                "- 하나\n- 둘\n- 셋\n- 넷\n- 다섯",                  // 맨 하이픈 ← 처음엔 못 셌다
                "1. 하나\n2. 둘\n3. 셋\n4. 넷\n5. 다섯")) {          // 맨 번호
            assertThat(DocumentDraftValidator.validate(TITLE, "cache-strategy",
                    base.replace(written, form)))
                    .as("이 형식을 못 세면 멀쩡한 문서에 경고가 뜬다:%n%s", form)
                    .extracting(DocumentCheck::message)
                    .noneMatch(m -> m.contains("언제 깨지는가"));
        }
    }

    /* ── 2026-08-18: 어려운 용어가 정의 없이 지나갔다 ─────────────
     * 사용자가 8/18 고급 문제를 읽고 "모르는 용어가 설명 없이 나온다"고 지적했다.
     * 8/15 실물을 세어 보니 정의된 용어는 '## 무엇인가'의 5개뿐이고, 뒤쪽 절에서 처음 나온
     * 낙관적 잠금·공유 락·배타 락·갭 락·SELECT ... FOR UPDATE 등 아홉 개가 그냥 지나갔다.
     *
     * 프롬프트에는 이미 "정의 없이 지나가는 용어가 하나도 없어야 한다"가 있었다.
     * 문구로는 안 되니 세는 쪽으로 넘긴 것인데, 8/15의 하이픈 사고와 정확히 같은 수순이다. */

    @Test
    @DisplayName("'### 용어 한눈에' 표가 없으면 알린다 — 뒤쪽 절 용어를 둘 자리가 사라진다")
    void warnsWhenGlossaryTableIsMissing() {
        String noGlossary = body(TITLE, "본문").replace("### 용어 한눈에", "### 다른 소제목");

        assertThat(DocumentDraftValidator.validate(TITLE, "cache-strategy", noGlossary))
                .extracting(DocumentCheck::message)
                .anyMatch(m -> m.contains("'### 용어 한눈에' 표가 없습니다"));
    }

    @Test
    @DisplayName("잠금 계열 용어와 SQL 구문이 정의 없이 쓰이면 알린다 — 8/15 실물이 그대로 통과했다")
    void warnsWhenHardTermsAreUsedWithoutDefinition() {
        String leaky = body(TITLE, "본문").replace("## 첫째 갈래\n본론 설명.", """
                ## 첫째 갈래
                낙관적 잠금을 쓰거나 판단 근거가 된 행을 SELECT ... FOR UPDATE로 잠근다.""");

        assertThat(DocumentDraftValidator.validate(TITLE, "cache-strategy", leaky))
                .extracting(DocumentCheck::message)
                .anyMatch(m -> m.contains("낙관적 잠금") && m.contains("SELECT ... FOR UPDATE"));
    }

    /**
     * 정의만 있으면 조용해야 한다. 형태 셋(표의 행·하이픈 정의 목록·굵은 글씨 정의 줄)을
     * 모두 인정하는지 함께 본다 — 하나만 인정하면 <b>제대로 정의한 문서에 경고가 뜬다</b>.
     * 굵은 글씨 형태를 인정하는 이유는 {@code checkTermList}가 이미 그 형태를 경고하되
     * 허용하기 때문이다. 한쪽이 봐준 형식을 다른 쪽이 막으면 같은 결함에 경고가 두 번 뜬다.
     */
    @Test
    @DisplayName("정의가 있으면 조용하다 — 표·하이픈 목록·굵은 글씨 셋 다 정의로 본다")
    void acceptsEveryDefinitionForm() {
        for (String definition : List.of(
                "| 낙관적 잠금 | 저장할 때 번호로 확인해 남이 고쳤으면 실패시키는 방식. | 경합이 드물 때. |",
                "- **낙관적 잠금(optimistic lock)** — 저장할 때 번호로 확인해 실패시키는 방식이다.",
                "**낙관적 잠금(optimistic lock)** — 저장할 때 번호로 확인해 실패시키는 방식이다.")) {
            String defined = body(TITLE, "본문").replace("## 첫째 갈래\n본론 설명.", """
                    ## 첫째 갈래
                    낙관적 잠금으로 막는다.

                    %s""".formatted(definition));

            assertThat(DocumentDraftValidator.validate(TITLE, "cache-strategy", defined))
                    .as("이 형태를 정의로 못 보면 제대로 쓴 문서에 경고가 뜬다:%n%s", definition)
                    .extracting(DocumentCheck::message)
                    .noneMatch(m -> m.contains("정의 없이 쓰인 용어"));
        }
    }

    /**
     * <b>오탐을 내지 않는지가 이 검사의 생사다.</b> 후보를 넓게 잡아
     * {@code ([가-힣]{2,6})\s*락}처럼 쓰면 "그래서 따로 락을 건다"에서 "따로 락"이 걸린다.
     * 수식어를 목록으로 못 박은 이유이고, 그 판단이 유지되는지 여기서 지킨다.
     */
    @Test
    @DisplayName("평범한 문장의 '락'은 용어로 잡지 않는다 — 오탐이 경고 전체를 무력화한다")
    void doesNotFlagOrdinaryProseAsTerm() {
        String prose = body(TITLE, "본문").replace("## 첫째 갈래\n본론 설명.", """
                ## 첫째 갈래
                그래서 따로 락을 건다. 이때 걸리는 락은 하나뿐이다.""");

        assertThat(DocumentDraftValidator.validate(TITLE, "cache-strategy", prose))
                .extracting(DocumentCheck::message)
                .noneMatch(m -> m.contains("정의 없이 쓰인 용어"));
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

    /**
     * <b>모든 검사를 통과하는</b> 최소 문서. 검사하려는 항목만 골라 망가뜨려 쓴다.
     *
     * <p>2026-08-17에 형식 규칙 넷을 추가하면서 이 문서도 함께 채웠다 — 전에는 본론이 0개이고
     * '언제 깨지는가' 항목이 1개인, <b>새 기준으로는 경고가 셋 붙는</b> 문서였다.
     * 그대로 두면 "이 항목만 검사한다"던 테스트들이 딴 경고까지 세게 되고,
     * 그러면 무엇을 재고 있었는지 알 수 없어진다.
     */
    private String body(String heading, String content) {
        return """
                # %s

                ## 무엇인가
                - **첫째 용어(first)** — 한 문장 정의.
                - **둘째 용어(second)** — 한 문장 정의.

                ## 왜 필요한가
                %s

                ## 첫째 갈래
                본론 설명.

                ### 왜 이렇게 설계됐는가
                - 다른 선택지를 두고 이렇게 판단했다.

                ## 둘째 갈래
                본론 설명.

                ### 용어 한눈에

                | 용어 | 한 줄 뜻 | 언제 쓰나 |
                |---|---|---|
                | 셋째 용어 | 한 줄 뜻. | 이럴 때. |
                | 넷째 용어 | 한 줄 뜻. | 이럴 때. |
                | 다섯째 용어 | 한 줄 뜻. | 이럴 때. |
                | 여섯째 용어 | 한 줄 뜻. | 이럴 때. |
                | 일곱째 용어 | 한 줄 뜻. | 이럴 때. |

                ## 실무에서는 이렇게 쓴다
                - 이런 상황에서 이렇게 쓴다.

                ## 언제 깨지는가
                **1. 첫째 조건**
                **2. 둘째 조건**
                **3. 셋째 조건**
                **4. 넷째 조건**
                **5. 다섯째 조건**

                ## 면접에서 이렇게 물어본다
                **Q. 무엇인가?**
                답변 요점.

                ## 면접 한 줄 요약
                한 줄 요약.
                """.formatted(heading, content);
    }
}
