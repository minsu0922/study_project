package project.study.study_project.global.common;

/**
 * 검색어를 LIKE 패턴으로 바꾸는 한 곳. 검색 칸이 있는 목록은 모두 여기를 거친다.
 *
 * <p>쿼리 쪽은 {@code like :q escape '!'} 꼴로 받는다. 이스케이프 글자({@link #ESCAPE})를 바꾸면
 * 그 쿼리들도 함께 바꿔야 한다(PostRepository, ProblemRepository, UserRepository).
 */
public final class SearchKeyword {

    /** 이보다 긴 검색어는 찾으려는 말이 아니라 붙여 넣은 글이다. 화면의 검색 칸도 50자까지 받는다. */
    public static final int MAX_LENGTH = 50;
    public static final char ESCAPE = '!';

    private SearchKeyword() {
    }

    /**
     * 앞뒤에 %를 붙인 "포함" 패턴. 비었거나 공백뿐이면 {@code null}(조건을 걸지 않는다).
     *
     * <p>사용자가 친 %와 _는 글자 그대로 찾는다. 그대로 넘기면 "%" 한 글자로 모든 줄이 나오고
     * "_"는 아무 글자 하나에 맞는다. 쿼리에서 %를 붙이지 않는 이유도 같다 — 거기서 붙이면
     * 사용자가 친 %와 우리가 붙인 %를 가를 수 없다.
     */
    public static String likePattern(String raw) {
        String word = Texts.trimToNull(raw);
        if (word == null) {
            return null;
        }
        if (word.length() > MAX_LENGTH) {
            word = word.substring(0, MAX_LENGTH);
        }
        return "%" + word.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
}
