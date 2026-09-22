package project.study.study_project.global.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.regex.Pattern;

/**
 * 분야 코드 — 예전 {@code Domain} enum의 자리를 잇는 값 타입(docs/superpowers/specs/2026-09-22-domain-registry-design.md).
 *
 * <p><b>왜 그냥 String이 아닌가.</b> 분야 코드와 주제 문자열·제목이 모두 String이면 자리를 바꿔
 * 넘겨도 컴파일된다. enum이 막아 주던 그 실수를 이 타입이 계속 막는다.
 *
 * <p><b>형식만 본다, 존재는 안 본다.</b> "그런 분야가 있나"는 DB(domain_setting)의 사실이라
 * 값 타입이 알 수 없다. 존재는 외래키가 최종적으로 막고, 쓰는 경로가 먼저 확인한다.
 *
 * <p><b>대문자만 받는 이유.</b> 컬럼의 정렬 규칙 utf8mb4_0900_ai_ci가 대소문자를 구별하지 않는다.
 * network와 NETWORK를 둘 다 받으면 기본키 충돌이 알 수 없는 오류로 나온다 — 형식에서 막으면
 * 그 경우가 아예 생기지 않는다.
 */
public record DomainCode(String value) implements Comparable<DomainCode> {

    /** 30은 컬럼 길이 VARCHAR(30)에서 온다. 2자 이상인 것은 한 글자 코드가 무엇인지 읽히지 않아서다. */
    private static final Pattern FORMAT = Pattern.compile("^[A-Z][A-Z0-9_]{1,29}$");

    public DomainCode {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "분야 코드는 영문 대문자로 시작하는 대문자·숫자·밑줄 2~30자여야 합니다: " + value);
        }
    }

    public static DomainCode of(String raw) {
        return new DomainCode(raw);
    }

    /** JSON에는 "NETWORK" 문자열 하나로 — enum 시절 API 모양 그대로. */
    @JsonValue
    @Override
    public String value() {
        return value;
    }

    @JsonCreator
    static DomainCode fromJson(String raw) {
        return of(raw);
    }

    @Override
    public int compareTo(DomainCode other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
