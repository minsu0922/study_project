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
        // + 빠진 필수 절 5(무엇인가·어디서 만나는가·언제 깨지는가·면접 질문·한 줄 요약) = 7
        assertThat(checks).hasSize(7);
        assertThat(checks).extracting(DocumentCheck::message)
                .anyMatch(m -> m.contains("slug"))
                .anyMatch(m -> m.contains("본문 제목"))
                .anyMatch(m -> m.contains("## 면접에서 이렇게 물어본다"));
    }

    /* ── 도우미 ──────────────────────────────────────────────── */

    /** 필수 절을 모두 갖춘 최소 문서. 검사하려는 항목 외에는 전부 정상이어야 결과가 읽힌다. */
    private String body(String heading, String content) {
        return """
                # %s

                ## 무엇인가
                한 문장 정의.

                ## 왜 필요한가
                %s

                ## 어디서 만나는가
                - 설정하는 자리 하나.

                ## 언제 깨지는가
                - 깨지는 조건 하나.

                ## 면접에서 이렇게 물어본다
                **Q. 무엇인가?**
                답변 요점.

                ## 면접 한 줄 요약
                한 줄 요약.
                """.formatted(heading, content);
    }
}
