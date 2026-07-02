package project.study.study_project.global.common;

/**
 * 문제 유형 — 문서 02-domain-enums 기준.
 * <p>MVP 자동채점 대상: {@link #MULTIPLE_CHOICE}, {@link #OX}, {@link #SHORT_ANSWER}.
 * {@link #ESSAY}는 enum으로만 유지하고 채점/시드에서는 제외한다.
 */
public enum ProblemType {
    MULTIPLE_CHOICE("객관식", true),
    OX("OX", true),
    SHORT_ANSWER("단답형", true),
    ESSAY("서술형", false);

    private final String displayName;
    private final boolean autoScored;

    ProblemType(String displayName, boolean autoScored) {
        this.displayName = displayName;
        this.autoScored = autoScored;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** MVP 자동채점 지원 여부. false면 채점 요청 시 QUIZ_002. */
    public boolean isAutoScored() {
        return autoScored;
    }
}
