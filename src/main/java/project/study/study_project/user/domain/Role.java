package project.study.study_project.user.domain;

/**
 * 사용자 권한 — 문서 01-data-model 기준.
 * <p>DB에는 이름(영문)을 그대로 저장한다({@code @Enumerated(EnumType.STRING)}).
 * 권한이 늘어나면(예: 문제 출제자) 여기에 상수를 추가한다.
 */
public enum Role {
    /** 일반 사용자. 회원가입 시 기본값. */
    USER("일반 사용자"),
    /** 관리자. 문서/문제 관리 등. */
    ADMIN("관리자");

    private final String displayName;

    Role(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
