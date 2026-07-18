package project.study.study_project.llm.domain;

/**
 * LLM 생성 초안의 검수 상태 — 문서 13-llm-problem-generation.
 * <p>흐름은 단방향: {@code PENDING → APPROVED | REJECTED}. 되돌리기(재검수)는 없다 —
 * 거절한 문제를 살리고 싶으면 관리자 등록 화면에서 직접 입력하는 게 맞고,
 * 상태를 오가게 하면 "승인된 초안이 다시 대기로 돌아가는" 이상한 이력이 생긴다.
 */
public enum DraftStatus {
    PENDING("검수 대기"),
    APPROVED("승인됨"),
    REJECTED("거절됨");

    private final String displayName;

    DraftStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
