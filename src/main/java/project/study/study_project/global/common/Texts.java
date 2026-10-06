package project.study.study_project.global.common;

/** 입력 문자열을 다듬는 공통 규칙. 서비스마다 같은 한 줄을 따로 두면 한 곳만 고쳐진다. */
public final class Texts {

    private Texts() {
    }

    /** 앞뒤 공백을 떼고, 비었거나 공백뿐이면 {@code null}로 돌려준다 — "안 적었다"를 한 가지로 다룬다. */
    public static String trimToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
