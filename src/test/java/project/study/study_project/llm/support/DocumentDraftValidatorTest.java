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

    /**
     * {@code ## 바탕이 되는 개념} 절을 채우는 더미 본문.
     *
     * <p>이 절만 <b>분량으로</b> 검사받는다({@code MIN_FOUNDATION_LENGTH} 1,000자). 자리를 만들어
     * 놓고 이름만 남기는 것이 2026-08-23에 실제로 막으려던 실패라, 도우미 문서도 그 기준을
     * 실제로 넘겨야 한다. 문장을 손으로 1,000자 적는 대신 {@code repeat}으로 만든 이유는
     * 이 내용이 검사 대상이 아니어서다 — 길이만 의미가 있다.
     */
    private static final String FOUNDATION =
            "이 주제가 딛고 선 상위 개념을 처음부터 설명하는 문단이다. 배경 지식이 없어도 읽힌다. "
                    .repeat(25);

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
        List<DraftCheck> checks = DocumentDraftValidator.validate(
                "XSS와 CSRF — 브라우저를 믿으면 생기는 일", "xss-and-csrf",
                body("CSRF와 XSS — 브라우저를 믿으면 생기는 일", "본문"));

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
                    assertThat(c.severity()).isEqualTo(DraftCheck.Severity.WARNING);
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
        List<DraftCheck> checks = DocumentDraftValidator.validate(
                "다른 제목", "Bad_Slug", "# 본문 제목\n\n## 왜 필요한가\n내용");

        // slug 형식 1 + 제목 불일치 1
        // + 빠진 필수 절 7(핵심 요약·바탕이 되는 개념·무엇인가·실무에서는 이렇게 쓴다·
        //   언제 깨지는가·면접 질문·한 줄 요약)
        // + 형식 4(설계 근거 소제목 없음·용어 표 없음·본론 0개·코드 예제 0개) = 13
        // ('## 무엇인가'·'## 언제 깨지는가'·'## 바탕이 되는 개념'의 형식 검사는 절이 아예 없으면
        //  건너뛴다 — 없는 절을 두고 "하이픈이 없다"고 말하면 원인이 흐려진다)
        assertThat(checks).hasSize(13);
        assertThat(checks).extracting(DraftCheck::message)
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
                .extracting(DraftCheck::message)
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
                .extracting(DraftCheck::message)
                .anyMatch(m -> m.contains("본론 섹션이 0개"));
    }

    /**
     * 고급 문제의 재료. 2026-08-14에 고급 날 재료가 말라 5개를 요청해 3개만 받았다.
     * 그 뒤 프롬프트에 "5가지 이상"을 박았지만 세는 곳은 없었다.
     *
     * <p>2026-08-23에 분량을 12,000자로 올리면서 기준을 7가지로 올렸다 — 늘린 지면은
     * 갈 곳을 지정해야 부연으로 차지 않는다는 것이 이 프롬프트의 오랜 결론이다.
     */
    @Test
    @DisplayName("'언제 깨지는가' 항목이 모자라면 알린다 — 고급 날 재료가 마른다")
    void warnsWhenFailureModesAreTooFew() {
        String few = body(TITLE, "본문")
                .replace("**4. 넷째 조건**\n", "")
                .replace("**5. 다섯째 조건**\n", "")
                .replace("**6. 여섯째 조건**\n", "")
                .replace("**7. 일곱째 조건**\n", "");

        assertThat(DocumentDraftValidator.validate(TITLE, "cache-strategy", few))
                .extracting(DraftCheck::message)
                .anyMatch(m -> m.contains("'## 언제 깨지는가'의 항목이 3개"));
    }

    /**
     * 항목 형식이 프롬프트에 지정돼 있지 않다. 실물은 {@code **1. 제목**}으로 왔지만 다음 문서는
     * 다른 형식일 수 있어, 형식 하나만 인정하면 <b>멀쩡한 문서에 경고가 뜬다</b>.
     */
    @Test
    @DisplayName("항목 형식이 달라도 센다 — 형식 하나만 인정하면 멀쩡한 문서에 경고가 뜬다")
    void countsFailureModesInAnyFormat() {
        // 일곱 항목을 통째로 다른 형식으로 갈아 끼운다. 하나만 바꿔서는 나머지 여섯이
        // 받쳐 줘서 통과하므로, 그 형식을 정말 세는지 알 수 없다.
        String base = body(TITLE, "본문");
        String written = """
                **1. 첫째 조건**
                **2. 둘째 조건**
                **3. 셋째 조건**
                **4. 넷째 조건**
                **5. 다섯째 조건**
                **6. 여섯째 조건**
                **7. 일곱째 조건**""";

        for (String form : List.of(
                // 소제목
                "### 하나\n### 둘\n### 셋\n### 넷\n### 다섯\n### 여섯\n### 일곱",
                // 하이픈+굵게
                "- **하나**\n- **둘**\n- **셋**\n- **넷**\n- **다섯**\n- **여섯**\n- **일곱**",
                // 맨 하이픈 ← 처음엔 못 셌다
                "- 하나\n- 둘\n- 셋\n- 넷\n- 다섯\n- 여섯\n- 일곱",
                // 맨 번호
                "1. 하나\n2. 둘\n3. 셋\n4. 넷\n5. 다섯\n6. 여섯\n7. 일곱",
                // 번호 없는 굵은 글씨 ← 2026-08-18 재생성 문서가 이 형식이었고, 항목 7개를
                // 1개로 세어 잘 쓴 문서에 "5개 이상" 경고가 떴다. 프롬프트는 번호를 요구한
                // 적이 없으므로 번호를 세던 쪽이 처음부터 틀렸다.
                "**하나**\n**둘**\n**셋**\n**넷**\n**다섯**\n**여섯**\n**일곱**")) {
            assertThat(DocumentDraftValidator.validate(TITLE, "cache-strategy",
                    base.replace(written, form)))
                    .as("이 형식을 못 세면 멀쩡한 문서에 경고가 뜬다:%n%s", form)
                    .extracting(DraftCheck::message)
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
                .extracting(DraftCheck::message)
                .anyMatch(m -> m.contains("'### 용어 한눈에' 표가 없습니다"));
    }

    @Test
    @DisplayName("잠금 계열 용어와 SQL 구문이 정의 없이 쓰이면 알린다 — 8/15 실물이 그대로 통과했다")
    void warnsWhenHardTermsAreUsedWithoutDefinition() {
        String leaky = body(TITLE, "본문").replace("## 첫째 갈래\n본론 설명.", """
                ## 첫째 갈래
                낙관적 잠금을 쓰거나 판단 근거가 된 행을 SELECT ... FOR UPDATE로 잠근다.""");

        assertThat(DocumentDraftValidator.validate(TITLE, "cache-strategy", leaky))
                .extracting(DraftCheck::message)
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
                    .extracting(DraftCheck::message)
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
                .extracting(DraftCheck::message)
                .noneMatch(m -> m.contains("정의 없이 쓰인 용어"));
    }

    /* ── 2026-08-23: 기본 개념과 코드 예제 ────────────────────────
     * 사용자가 8/23 실물(「연결이 안 될 때 어느 계층에서 끊겼는가」)을 읽고 두 가지를 지적했다.
     * ① OSI 7계층 자체를 설명하지 않는다 — 실무 상황만 있고 기본 개념이 없다.
     * ② 블로그에 그대로 올릴 만한 글이 되려면 코드 예제가 필요하다.
     *
     * 둘 다 프롬프트로 풀었지만, 프롬프트만 고치면 몇 주 뒤 조용히 돌아온다는 것을
     * 이 저장소는 세 번 겪었다(8/15 하이픈, 8/18 용어 정의, 8/18 절 형식). 그래서 센다. */

    /**
     * {@code ## 바탕이 되는 개념}이 <b>이름만 남고 비지</b> 않았는지.
     *
     * <p>자리를 만들어 주면 이름만 남는다는 것을 {@code ### 용어 한눈에}에서 이미 겪었다.
     * 게다가 이 절은 문서의 주제와 가장 먼 절이라, 모델이 분량 압박을 받을 때
     * <b>가장 먼저 줄이고 싶어 하는 자리</b>다. 존재가 아니라 분량을 세는 이유다.
     */
    @Test
    @DisplayName("'바탕이 되는 개념'이 얇으면 알린다 — 자리만 만들면 이름만 남는다")
    void warnsWhenFoundationSectionIsThin() {
        String thin = body(TITLE, "본문").replace(FOUNDATION, "상위 개념을 한 줄로 스쳐 지나간다.");

        List<DraftCheck> checks = DocumentDraftValidator.validate(TITLE, "cache-strategy", thin);

        assertThat(checks).extracting(DraftCheck::message)
                .anyMatch(m -> m.contains("'## 바탕이 되는 개념'이"));
        assertThat(DocumentDraftValidator.hasBlocking(checks))
                .as("얇다고 막으면 그 주기 나흘이 날아간다 — 검수자가 채워 넣는 편이 싸다")
                .isFalse();
    }

    /**
     * 코드 예제가 <b>2개 이상</b>인지. 2026-08-23에 "긴 예제는 쓰지 마라"라는 금지를 풀었는데,
     * <b>푸는 것만으로는 나오지 않는다</b> — 모델에게 코드를 빼는 쪽이 언제나 안전하다.
     * 개수를 박은 지시만 지켜지고, 그 개수는 세는 곳이 있어야 유지된다.
     */
    @Test
    @DisplayName("코드 예제가 모자라면 알린다 — 금지를 푸는 것만으로는 코드가 나오지 않는다")
    void warnsWhenCodeExamplesAreTooFew() {
        String noCode = body(TITLE, "본문").replace("```sql\nSELECT 1\n```", "설명으로 대신한다.");

        List<DraftCheck> checks = DocumentDraftValidator.validate(TITLE, "cache-strategy", noCode);

        assertThat(checks).extracting(DraftCheck::message)
                .anyMatch(m -> m.contains("코드 예제가 1개입니다"));
        assertThat(DocumentDraftValidator.hasBlocking(checks))
                .as("코드가 억지가 되는 주제(설계 원칙·방법론)도 있어 차단으로 두면 그런 날 길이 막힌다")
                .isFalse();
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
        String withComment = body(TITLE, "본문").replace("**1. 첫째 조건**", """
                ```bash
                ## 커널 파라미터를 확인한다
                sysctl net.ipv4.ip_local_port_range
                ```

                **1. 첫째 조건**""");

        assertThat(DocumentDraftValidator.validate(TITLE, "cache-strategy", withComment))
                .extracting(DraftCheck::message)
                .as("주석에서 끊기면 항목이 0개로 세어져 헛경고가 뜬다")
                .noneMatch(m -> m.contains("언제 깨지는가"))
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
        String faked = body(TITLE, "본문").replace("## 면접 한 줄 요약\n한 줄 요약.", """
                ```bash
                ## 면접 한 줄 요약
                ```""");

        assertThat(DocumentDraftValidator.validate(TITLE, "cache-strategy", faked))
                .anySatisfy(c -> {
                    assertThat(c.isBlocking()).isTrue();
                    assertThat(c.message()).contains("## 면접 한 줄 요약");
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

                ## 핵심 요약
                - **첫째 요점** — 결론 한 문장.
                - **둘째 요점** — 결론 한 문장.

                ## 바탕이 되는 개념
                %s

                ## 무엇인가
                - **첫째 용어(first)** — 한 문장 정의.
                - **둘째 용어(second)** — 한 문장 정의.

                ## 왜 필요한가
                %s

                ## 첫째 갈래
                본론 설명. 아래 예제가 그 동작을 보여 준다.

                ```java
                var result = compute();
                ```

                그래서 결과가 이렇게 나온다.

                ### 왜 이렇게 설계됐는가
                - 다른 선택지를 두고 이렇게 판단했다.

                ## 둘째 갈래
                본론 설명. 설정은 이렇게 준다.

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

                ## 실무에서는 이렇게 쓴다
                - 이런 상황에서 이렇게 쓴다.

                ## 언제 깨지는가
                **1. 첫째 조건**
                **2. 둘째 조건**
                **3. 셋째 조건**
                **4. 넷째 조건**
                **5. 다섯째 조건**
                **6. 여섯째 조건**
                **7. 일곱째 조건**

                ## 면접에서 이렇게 물어본다
                **Q. 무엇인가?**
                답변 요점.

                ## 면접 한 줄 요약
                한 줄 요약.
                """.formatted(heading, FOUNDATION, content);
    }
}
