package project.study.study_project.learningpath.domain;

import project.study.study_project.global.common.DomainCode;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 코스 키 — 자동 코스와 관리자 코스를 한 칸으로 가리킨다(V43 user_learning_path.path_key).
 *
 * <pre>
 *   domain:NETWORK   분야별 코스(분야 코드)
 *   code:java        코드 문제 코스(언어)
 *   custom:12        관리자 코스(learning_path.id)
 * </pre>
 *
 * 주소창(/path.html?key=...)에도 그대로 실리므로 형식 밖의 글자는 {@link #parse}가 거른다.
 */
public record PathKey(Kind kind, String value) {

    public enum Kind { DOMAIN, CODE, CUSTOM }

    /* 분야 코드는 DomainCode가 직접 검사한다(isDomainCode). 여기서 정규식을 따로 두면 두 규칙이
       어긋난다 — 한 글자 코드를 이쪽만 통과시켜 DomainCode.of가 500을 냈다(2026-10-21). */
    private static final Pattern CODE_VALUE = Pattern.compile("[a-z]{1,20}");
    private static final Pattern CUSTOM_VALUE = Pattern.compile("[1-9][0-9]{0,17}");

    public static PathKey domain(String domainCode) {
        return new PathKey(Kind.DOMAIN, domainCode);
    }

    public static PathKey code(String language) {
        return new PathKey(Kind.CODE, language);
    }

    public static PathKey custom(long pathId) {
        return new PathKey(Kind.CUSTOM, Long.toString(pathId));
    }

    /** 형식이 맞지 않으면 빈 값. 없는 코스인지는 여기서 따지지 않는다(부르는 쪽 몫) */
    public static Optional<PathKey> parse(String raw) {
        if (raw == null) return Optional.empty();
        int colon = raw.indexOf(':');
        if (colon < 0) return Optional.empty();
        String head = raw.substring(0, colon);
        String value = raw.substring(colon + 1);
        return switch (head) {
            case "domain" -> isDomainCode(value) ? Optional.of(domain(value)) : Optional.empty();
            case "code" -> CODE_VALUE.matcher(value).matches() ? Optional.of(code(value)) : Optional.empty();
            case "custom" -> CUSTOM_VALUE.matcher(value).matches() ? Optional.of(new PathKey(Kind.CUSTOM, value)) : Optional.empty();
            default -> Optional.empty();
        };
    }

    private static boolean isDomainCode(String value) {
        try {
            DomainCode.of(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public long customId() {
        if (kind != Kind.CUSTOM) throw new IllegalStateException("관리자 코스 키가 아니다: " + this);
        return Long.parseLong(value);
    }

    @Override
    public String toString() {
        return kind.name().toLowerCase() + ":" + value;
    }
}
