package project.study.study_project.llm.support;

import java.util.Optional;

/**
 * 모델이 인용한 문장을 마크다운 원문에서 찾는다. 대조는 {@code ClaudeDocumentFactChecker.normalize}와 같은
 * 규칙(강조 {@code *}·백틱을 걷고 공백을 한 칸으로)으로 하고, 찾은 자리는 원문 위치로 되돌려 준다.
 * 모델은 인용하면서 강조 기호를 자주 빼먹어, 원문 글자 그대로 찾으면 고칠 자리를 놓친다.
 */
public final class TextSpans {

    private TextSpans() {
    }

    /**
     * 걷어 낸 글 기준으로 {@code target}이 정확히 한 번 있으면 원문의 {@code [start, end)}를 준다.
     * 두 번 이상이면 어디를 고칠지 몰라 비운다.
     */
    public static Optional<int[]> locateOnce(String contentMd, String target) {
        if (contentMd == null || target == null) {
            return Optional.empty();
        }
        String needle = normalizedOnly(target).trim();
        if (needle.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder norm = new StringBuilder(contentMd.length());
        int[] origin = new int[contentMd.length() + 1];
        boolean lastSpace = false;
        for (int i = 0; i < contentMd.length(); i++) {
            char c = contentMd.charAt(i);
            if (c == '*' || c == '`') {
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (lastSpace) {
                    continue;
                }
                origin[norm.length()] = i;
                norm.append(' ');
                lastSpace = true;
                continue;
            }
            origin[norm.length()] = i;
            norm.append(c);
            lastSpace = false;
        }
        int first = norm.indexOf(needle);
        if (first < 0 || first != norm.lastIndexOf(needle)) {
            return Optional.empty();
        }
        int start = origin[first];
        int end = origin[first + needle.length() - 1] + 1;
        // 끝 글자 뒤에 붙은 강조 기호(** 닫기)까지 품어야 고친 뒤 강조가 반쯤 남지 않는다
        while (end < contentMd.length() && (contentMd.charAt(end) == '*' || contentMd.charAt(end) == '`')) {
            end++;
        }
        while (start > 0 && (contentMd.charAt(start - 1) == '*' || contentMd.charAt(start - 1) == '`')) {
            start--;
        }
        return Optional.of(new int[]{start, end});
    }

    private static String normalizedOnly(String s) {
        return s.replace("*", "").replace("`", "").replaceAll("\\s+", " ");
    }
}
