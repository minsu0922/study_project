package project.study.study_project.llm.domain;

/**
 * 문제 초안을 거절한 유형(V47, REVIEW 2026-11-01 2장). 거절할 때 하나를 반드시 고른다.
 *
 * <p>사유 글만 있을 때는 같은 실수도 문장이 다르면 따로 셌고, 글을 비운 거절은 다음 생성에 되먹지 못했다.
 * 목록은 실제 거절 사유와 AI 검수 지적 종류에서 뽑았다. 코드 문제도 같은 목록을 쓴다.
 * 화면(admin/llm.html)의 고르기 목록과 이름이 같아야 한다.
 */
public enum RejectType {
    ANSWER_LEAK("정답 단서 누설"),
    WRONG_ANSWER("정답 오류"),
    OUT_OF_SOURCE("근거 문서 밖 내용"),
    FORMAT("형식 위반"),
    DIFFICULTY_OR_FORM("난이도·출제 형태 안 맞음"),
    WEAK_EXPLANATION("해설·오답 설명 부실"),
    DUPLICATE("이미 있는 문제와 중복"),
    OTHER("기타");

    private final String label;

    RejectType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
