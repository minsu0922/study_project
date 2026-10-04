package project.study.study_project.user.support;

import java.util.List;
import java.util.Locale;

/**
 * 닉네임으로 쓸 수 없는 말 — 가입과 닉네임 변경이 같은 목록을 본다.
 *
 * <p>누구나 "관리자"로 가입할 수 있으면 토론에서 운영진 행세를 할 수 있다. 글자가 들어 있기만
 * 해도 막는다("관리자1", "admin_kr") — 정확히 같은 말만 막으면 숫자 하나로 피해 간다.
 * 그 대가로 "badminton"처럼 우연히 들어 있는 이름도 막힌다. 사칭을 놓치는 쪽보다 낫다.
 */
public final class NicknameRule {

    private static final List<String> RESERVED =
            List.of("관리자", "운영자", "운영진", "어드민", "admin", "csquiz");

    private NicknameRule() {
    }

    public static boolean isReserved(String nickname) {
        String lowered = nickname.toLowerCase(Locale.ROOT);
        return RESERVED.stream().anyMatch(lowered::contains);
    }
}
