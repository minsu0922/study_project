package project.study.study_project.llm.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 근거 편 판정 — 2026-09-17에 생긴 어긋남을 막는 자리다.
 *
 * <p>중급이 읽는 본문을 두 편으로 합쳐 놓고({@code DocumentEditionRule.bodyFor}) 초안에 적는
 * slug는 심화편 하나로 뒀더니, <b>입문편에서 캔 문제의 근거 링크가 심화편으로 갔다.</b>
 * 눌러도 해설이 가리킨 절이 없다 — 링크는 200으로 열리고 문서도 멀쩡해서 조용한 결함이다.
 */
class SourceEditionRuleTest {

    private static final String ADVANCED = "flyway-advanced";
    private static final String BEGINNER = "flyway";
    private static final String ADVANCED_BODY = """
            # 제목
            ## 실무에서 어디에 나타나는가
            인스턴스가 한 대이고 변경이 짧으면 기동 시 실행이 간단하다.
            """;
    private static final String BEGINNER_BODY = """
            # 제목
            ### 왜 이렇게 설계됐는가
            순서를 파일 이름에 박은 이유는 모든 환경에서 같아야 하기 때문이다.
            """;

    @Test
    @DisplayName("인용이 짝 편에서만 나오면 그 편을 근거로 적는다 — 링크 한 번에 맞는 글로 간다")
    void movesToCounterpartWhenQuoteLivesThere() {
        String slug = SourceEditionRule.slugFor(ADVANCED, BEGINNER,
                "순서를 파일 이름에 박은 이유는 모든 환경에서 같아야 하기 때문이다.",
                ADVANCED_BODY, BEGINNER_BODY);

        assertThat(slug).isEqualTo(BEGINNER);
    }

    @Test
    @DisplayName("인용이 기준 편에 있으면 그대로 둔다")
    void keepsBaseWhenQuoteIsThere() {
        String slug = SourceEditionRule.slugFor(ADVANCED, BEGINNER,
                "인스턴스가 한 대이고 변경이 짧으면 기동 시 실행이 간단하다.",
                ADVANCED_BODY, BEGINNER_BODY);

        assertThat(slug).isEqualTo(ADVANCED);
    }

    /**
     * 두 편이 같은 표를 싣는 일이 실제로 있다({@code ## 용어 한눈에}). 그때 옮기면 같은 배치의
     * 문제들이 <b>이유 없이</b> 서로 다른 편을 가리킨다 — 근거가 흔들려 보인다.
     */
    @Test
    @DisplayName("양쪽에 다 있는 문장이면 기준 편을 지킨다 — 두 편이 같은 표를 싣는 일이 있다")
    void keepsBaseWhenQuoteIsInBoth() {
        String shared = "체크섬은 파일 내용이 바뀌었는지 가려내는 값이다.";

        assertThat(SourceEditionRule.slugFor(ADVANCED, BEGINNER, shared,
                ADVANCED_BODY + shared, BEGINNER_BODY + shared))
                .isEqualTo(ADVANCED);
    }

    /**
     * 어느 쪽에서도 못 찾는 것은 <b>편의 문제가 아니라 인용의 문제</b>다. 그건 SourceQuoteRule이
     * 따로 경고한다. 여기서 편을 옮기면 없는 근거를 엉뚱한 글에 걸어 두는 셈이 된다.
     */
    @Test
    @DisplayName("어느 편에도 없는 인용은 기준 편을 지킨다 — 그건 인용이 잘못된 것이다")
    void keepsBaseWhenQuoteIsNowhere() {
        assertThat(SourceEditionRule.slugFor(ADVANCED, BEGINNER, "어디에도 없는 문장이다.",
                ADVANCED_BODY, BEGINNER_BODY))
                .isEqualTo(ADVANCED);
    }

    @Test
    @DisplayName("공백 차이는 흘린다 — 줄바꿈 하나로 판정이 갈리면 검사가 못 미덥다")
    void ignoresWhitespaceDifferences() {
        String slug = SourceEditionRule.slugFor(ADVANCED, BEGINNER,
                "순서를 파일 이름에 박은 이유는   모든 환경에서\n같아야 하기 때문이다.",
                ADVANCED_BODY, BEGINNER_BODY);

        assertThat(slug).isEqualTo(BEGINNER);
    }

    /**
     * 판정할 재료가 없는 경우들 — 근거 없이 만든 문제, 짝이 없는 옛 문서, 아직 승인되지 않아
     * DB에 없는 편, 인용이 빈 초안. 전부 <b>기준을 그대로</b> 돌려줘야 한다.
     * 여기서 예외를 던지거나 {@code null}을 내면 요금을 낸 초안이 통째로 사라진다.
     */
    @Test
    @DisplayName("판정할 재료가 없으면 기준을 그대로 돌려준다 — 근거 링크 하나 때문에 초안을 잃지 않는다")
    void fallsBackWhenMaterialIsMissing() {
        assertThat(SourceEditionRule.slugFor(null, BEGINNER, "인용", ADVANCED_BODY, BEGINNER_BODY)).isNull();
        assertThat(SourceEditionRule.slugFor(ADVANCED, null, "인용", ADVANCED_BODY, BEGINNER_BODY))
                .isEqualTo(ADVANCED);
        assertThat(SourceEditionRule.slugFor(ADVANCED, BEGINNER, "  ", ADVANCED_BODY, BEGINNER_BODY))
                .isEqualTo(ADVANCED);
        assertThat(SourceEditionRule.slugFor(ADVANCED, BEGINNER, "인용", ADVANCED_BODY, null))
                .isEqualTo(ADVANCED);
    }
}
