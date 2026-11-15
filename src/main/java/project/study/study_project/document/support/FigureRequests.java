package project.study.study_project.document.support;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 본문의 손그림 요청 — 장면 그림 자리의 mermaid 펜스 안 {@code %% 손그림: 설명} 줄(2026-11-15).
 *
 * <p>배치는 장면 그림을 mermaid로 먼저 그리고 이 줄을 남긴다. mermaid는 {@code %%} 줄을
 * 그리지 않으므로 화면은 그대로 두고, 사람이 손 SVG를 그리면 펜스째 이미지 한 줄로 바꾼다.
 * 이 표지를 바꾸면 프롬프트 두 편과 관리 목록의 "손그림 대기" 필터가 함께 깨진다.
 */
public final class FigureRequests {

    public static final String MARKER = "%% 손그림:";

    private static final Pattern MERMAID_BLOCK = Pattern.compile("(?s)```mermaid[ \\t]*\\R(.*?)```");

    private FigureRequests() {
    }

    /** mermaid 펜스 안의 요청 설명들. 설명이 비어 있으면 빈 문자열로 넣는다. */
    public static List<String> extract(String body) {
        List<String> found = new ArrayList<>();
        if (body == null) {
            return found;
        }
        Matcher m = MERMAID_BLOCK.matcher(body);
        while (m.find()) {
            m.group(1).lines().map(String::strip)
                    .filter(line -> line.startsWith(MARKER))
                    .map(line -> line.substring(MARKER.length()).strip())
                    .forEach(found::add);
        }
        return found;
    }

    /** 펜스 밖에 적힌 요청 줄 수 — 펜스 밖이면 화면에 글자로 그대로 나온다. */
    public static int countOutsideMermaid(String body) {
        if (body == null) {
            return 0;
        }
        String outside = MERMAID_BLOCK.matcher(body).replaceAll("");
        return (int) outside.lines().map(String::strip).filter(line -> line.startsWith(MARKER)).count();
    }
}
