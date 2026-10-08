package project.study.study_project.user.support;

import java.security.SecureRandom;

/**
 * 복구 코드(V32) — 비밀번호를 잊었을 때 본인임을 증명하는 값.
 *
 * <p>20글자를 31가지 문자에서 뽑아 약 99비트다. 요청 제한(분당 5회)이 없어도 맞힐 수 없는 길이다.
 */
public final class RecoveryCode {

    /** 헷갈리는 글자(0·O, 1·I·L)를 뺐다 — 사람이 종이에 적었다가 다시 치는 값이다. */
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int GROUPS = 5;
    private static final int GROUP_SIZE = 4;
    private static final SecureRandom RANDOM = new SecureRandom();

    private RecoveryCode() {
    }

    /** 사람에게 보여 줄 모양(XXXX-XXXX-XXXX-XXXX-XXXX). */
    public static String generate() {
        StringBuilder sb = new StringBuilder();
        for (int g = 0; g < GROUPS; g++) {
            if (g > 0) {
                sb.append('-');
            }
            for (int i = 0; i < GROUP_SIZE; i++) {
                sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            }
        }
        return sb.toString();
    }

    /**
     * 해시하거나 대조하기 전의 모양 — 줄표·공백을 빼고 대문자로.
     * 사람이 소문자로 치거나 줄표를 빼먹어도 같은 코드로 본다.
     */
    public static String normalize(String raw) {
        return raw == null ? "" : raw.replaceAll("[\\s-]", "").toUpperCase();
    }
}
