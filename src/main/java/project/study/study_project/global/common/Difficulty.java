package project.study.study_project.global.common;

/**
 * 문제 난이도 — 문서 02-domain-enums 기준.
 */
public enum Difficulty {
    BEGINNER("초급"),
    INTERMEDIATE("중급"),
    ADVANCED("고급");

    private final String displayName;

    Difficulty(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
