package project.study.study_project.discussion.domain;

/**
 * 글의 말머리 — 그 글이 무엇을 하려는 글인지(V28).
 *
 * <p>셋으로 좁게 잡았다. 종류가 많으면 쓰는 사람이 고르다 멈추고, 목록의 표시도 구별이 안 된다.
 * 화면에 보이는 이름의 주인은 이 enum 하나다 — 화면은 응답의 label을 그대로 쓴다.
 */
public enum PostCategory {
    /** 모르는 것을 묻는다. "답변 기다리는 글"이 주로 이쪽이다. */
    QUESTION("질문"),
    /** 이해한 것을 정리해 나눈다. */
    SUMMARY("정리"),
    /** 문제의 정답·해설·보기가 틀렸다고 짚는다. 관리자가 눈여겨볼 글이다. */
    ERRATA("오류 지적");

    private final String label;

    PostCategory(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
