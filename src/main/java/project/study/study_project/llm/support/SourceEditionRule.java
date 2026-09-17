package project.study.study_project.llm.support;

/**
 * 이 문제의 <b>근거 문서가 어느 편인지</b> — 인용이 실제로 있는 쪽을 고른다(2026-09-17 신설).
 *
 * <h2>왜 필요해졌나</h2>
 *
 * <p>2026-09-17에 중급이 읽는 본문을 <b>심화편 + 입문편의 중급 절 둘</b>로 합쳤다
 * ({@link DocumentEditionRule#bodyFor}). 재료는 908 → 2,383자가 됐는데, 초안에 적는
 * {@code documentSlug}는 <b>심화편 하나로 남아 있었다</b>. 그날 실물 5문제 중 하나가
 * 입문편의 {@code ### 왜 이렇게 설계됐는가}에서 나왔는데, 화면의 "근거 문서" 링크는
 * 심화편으로 갔다 — <b>눌러도 그 절이 없다.</b>
 *
 * <p>증상이 조용한 종류다. 링크는 200으로 열리고 문서도 멀쩡하며, 해설이 가리킨 절만 없다.
 * 틀린 학습자가 돌아갈 곳을 잃는 것이 이 링크의 존재 이유인데 정확히 그 자리가 깨진다.
 *
 * <h2>인용으로 판정하는 이유</h2>
 *
 * <p>고를 수 있는 근거는 셋이었다 — 문제의 형태(questionKind), 해설이 가리킨 절 이름,
 * 그리고 <b>근거 인용</b>({@code sourceQuote}). 앞의 둘은 추정이다. 형태는 한 편에만 매이지
 * 않고("비교"는 두 편 어디서나 나온다), 절 이름은 두 편에 같은 것이 있다({@code ## 용어 한눈에}).
 * 인용만이 <b>그 문장이 어느 본문에 실제로 있는가</b>라는 사실이다.
 *
 * <p>게다가 그 판정 재료는 이미 있다. {@code SourceQuoteRule}이 "인용이 본문에 실제로
 * 있는가"를 같은 방식으로 재고 있고, 공백만 흘리는 정규화도 거기 것을 그대로 쓴다 —
 * 두 곳이 다르게 맞추면 한쪽은 찾았다 하고 다른 쪽은 못 찾았다 하는 상태가 된다.
 *
 * <h2>한쪽에서만 찾았을 때만 옮긴다</h2>
 *
 * <p>양쪽 본문에 다 있는 문장이면 기준 편을 그대로 둔다. 두 편이 같은 표를 싣는 일이
 * 실제로 있고({@code ## 용어 한눈에}), 그때 옮기면 <b>근거가 흔들린다</b> —
 * 같은 배치의 문제들이 이유 없이 서로 다른 편을 가리키게 된다.
 * 어느 쪽에서도 못 찾으면 그것도 그대로 둔다. 그건 편의 문제가 아니라 인용이 잘못된
 * 것이고, {@code SourceQuoteRule}이 따로 경고한다.
 */
public final class SourceEditionRule {

    private SourceEditionRule() {
    }

    /**
     * 이 문제에 적을 근거 slug.
     *
     * @param baseSlug          기준 slug(배치가 읽은 편). 이 값이 없으면 판정하지 않는다
     * @param counterpartSlug   짝 편의 slug. 없으면(짝이 없는 옛 문서) 기준을 그대로 쓴다
     * @param sourceQuote       문제가 옮겨 적은 근거 한 줄. 비면 판정할 것이 없다
     * @param baseBody          기준 편 본문
     * @param counterpartBody   짝 편 본문
     * @return 인용이 짝 편에서만 발견되면 {@code counterpartSlug}, 그 밖에는 {@code baseSlug}
     */
    public static String slugFor(String baseSlug, String counterpartSlug, String sourceQuote,
                                 String baseBody, String counterpartBody) {
        if (isBlank(baseSlug) || isBlank(counterpartSlug) || isBlank(sourceQuote)
                || isBlank(baseBody) || isBlank(counterpartBody)) {
            return baseSlug;
        }
        String needle = SourceQuoteRule.normalize(sourceQuote);
        boolean inBase = SourceQuoteRule.normalize(baseBody).contains(needle);
        boolean inCounterpart = SourceQuoteRule.normalize(counterpartBody).contains(needle);
        return !inBase && inCounterpart ? counterpartSlug : baseSlug;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
