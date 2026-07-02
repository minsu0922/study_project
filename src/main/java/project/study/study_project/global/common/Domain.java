package project.study.study_project.global.common;

/**
 * CS 학습 도메인(대분류) — 문서 02-domain-enums 기준.
 * <p>상수명(영문)은 DB/API 표기, {@link #getDisplayName()}은 화면 표기(한글).
 * DB 저장은 {@code @Enumerated(EnumType.STRING)}으로 상수명을 그대로 쓴다(ORDINAL 금지).
 */
public enum Domain {
    NETWORK("네트워크"),
    OS("운영체제"),
    DATABASE("데이터베이스"),
    DS_ALGORITHM("자료구조·알고리즘"),
    SYSTEM_DESIGN("시스템설계"),
    SECURITY("보안"),
    LANGUAGE_RUNTIME("언어·런타임"),
    CLOUD_INFRA("클라우드·인프라"),
    FRONTEND_CS("프론트엔드CS"),
    INTEGRATED("통합시나리오");

    private final String displayName;

    Domain(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
