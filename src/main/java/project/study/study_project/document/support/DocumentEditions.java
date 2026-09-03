package project.study.study_project.document.support;

/**
 * 한 주제의 두 편(입문편·심화편)을 <b>slug만으로</b> 짝지어 주는 규칙 — 2026-09-03, docs/15.
 *
 * <h2>왜 칸을 만들지 않았나</h2>
 *
 * <p>{@code document} 테이블에 "편"이나 "짝 문서 id" 칸을 두는 방법이 먼저 떠오른다.
 * 두지 않은 이유는 이 저장소의 오랜 규칙 때문이다 — <b>파생 가능한 값은 저장하지 않는다.</b>
 * 두 편의 slug는 생성 시점에 규칙으로 정해진다({@code X}와 {@code X-advanced}).
 * 칸을 두면 그 칸과 slug가 어긋나는 경로가 새로 생기는데, 어긋나도 화면은 멀쩡히 뜨고
 * 링크만 엉뚱한 곳으로 간다. 조용히 틀리는 것이 이 프로젝트가 가장 경계하는 실패다.
 *
 * <p>대가는 <b>검수자가 승인 화면에서 slug를 손으로 고치면 짝이 끊긴다</b>는 것이다.
 * 받아들인 이유: 그때 벌어지는 일이 "배지와 링크가 사라진다"뿐이고, 문서 자체는 멀쩡히 읽힌다.
 * 칸을 뒀을 때 벌어지는 일(엉뚱한 문서로 가는 링크)보다 눈에 잘 띄고 덜 해롭다.
 *
 * <h2>짝이 없으면 편도 없다</h2>
 *
 * <p>2026-09-03 이전 문서는 한 편짜리다. 그런 문서에 "입문편" 배지를 달면 거짓말이 된다 —
 * 읽는 사람은 어딘가에 심화편이 있다고 믿고 찾아 나선다. 그래서 <b>짝이 실제로 있을 때만</b>
 * 편을 붙인다. 판정은 이 클래스가 아니라 호출부의 몫인데, 존재 확인에는 DB가 필요하고
 * 이 클래스는 문자열만 아는 순수 함수로 두는 편이 테스트하기 쉽기 때문이다.
 */
public final class DocumentEditions {

    /**
     * 심화편 slug의 꼬리 — {@code ClaudeDocumentGenerator.ADVANCED_SYSTEM_PROMPT}가 모델에게
     * 시키는 값과 <b>같아야 한다</b>. 어긋나면 두 편이 만들어지긴 하는데 서로를 못 찾는다.
     */
    public static final String ADVANCED_SUFFIX = "-advanced";

    private static final String BEGINNER_LABEL = "입문편";
    private static final String ADVANCED_LABEL = "심화편";

    private DocumentEditions() {
    }

    /** 이 slug가 심화편인가. */
    public static boolean isAdvanced(String slug) {
        return slug != null && slug.endsWith(ADVANCED_SUFFIX);
    }

    /**
     * 짝이 되는 편의 slug — 입문편이면 심화편, 심화편이면 입문편.
     *
     * <p>{@code null}이나 빈 문자열에는 {@code null}을 돌려준다. 꼬리만 남는 이상한 slug
     * ({@code "-advanced"})도 마찬가지다 — 그걸 짝으로 조회하면 엉뚱한 문서를 집을 수 있다.
     */
    public static String counterpartSlugOf(String slug) {
        if (slug == null || slug.isBlank()) {
            return null;
        }
        if (!isAdvanced(slug)) {
            return slug + ADVANCED_SUFFIX;
        }
        String head = slug.substring(0, slug.length() - ADVANCED_SUFFIX.length());
        return head.isBlank() ? null : head;
    }

    /** 화면에 붙일 편 이름. 짝이 있는지는 보지 않는다 — 그 판단은 호출부가 한다. */
    public static String labelOf(String slug) {
        return isAdvanced(slug) ? ADVANCED_LABEL : BEGINNER_LABEL;
    }
}
