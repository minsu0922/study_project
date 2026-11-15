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

    /**
     * 요청 줄을 지운 본문. 요청은 사람에게 남긴 메모라 학습자 화면에 안 보인다 —
     * 문제 생성 모델이 읽으면 화면에 없는 내용으로 문제를 낸다.
     */
    public static String strip(String body) {
        if (body == null || !body.contains(MARKER)) {
            return body;
        }
        return REQUEST_LINE.matcher(body).replaceAll("");
    }

    /** 요청 한 줄과 그 줄바꿈. 앞 들여쓰기도 함께 지운다. */
    private static final Pattern REQUEST_LINE = Pattern.compile("(?m)^[ \\t]*" + Pattern.quote(MARKER) + ".*(\\R|\\z)");

    /**
     * 문제 자료 칸의 손그림 — {@code ![설명](/images/problems/x.svg)} 한 줄. 화면(api.js
     * {@code PROBLEM_FIGURE})과 같은 규칙이다. 파일 이름은 영문·숫자·점·하이픈·밑줄만 받는다.
     */
    private static final Pattern PROBLEM_IMAGE =
            Pattern.compile("^!\\[[^\\]\\n]*\\]\\((/images/problems/[A-Za-z0-9._-]+\\.svg)\\)$");

    /** 자료 칸이 손그림 한 줄이면 그 경로, 아니면 {@code null}. */
    public static String problemImagePathOf(String code) {
        if (code == null) {
            return null;
        }
        Matcher m = PROBLEM_IMAGE.matcher(code.strip());
        return m.matches() ? m.group(1) : null;
    }

    private static final Pattern SVG_TEXT = Pattern.compile("(?s)<text\\b[^>]*>(.*?)</text>");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern IMAGE_ALT = Pattern.compile("^!\\[([^\\]\\n]*)\\]");

    /**
     * 손그림 자료를 글로 옮긴 것 — 설명과 그림 속 글자(SVG {@code <text>})를 위에서부터 한 줄씩.
     * AI 검수는 그림을 못 보므로 이것으로 "그림이 답을 흘리는가, 문제와 맞는가"를 본다.
     * 손그림이 아니면 {@code null}, 파일을 못 읽으면 설명만 돌려준다.
     */
    public static String problemImageTextOf(String code) {
        String path = problemImagePathOf(code);
        if (path == null) {
            return null;
        }
        Matcher alt = IMAGE_ALT.matcher(code.strip());
        StringBuilder out = new StringBuilder("설명: ").append(alt.find() ? alt.group(1) : "");
        try (var in = FigureRequests.class.getClassLoader().getResourceAsStream("static" + path)) {
            if (in == null) {
                return out.toString();
            }
            Matcher text = SVG_TEXT.matcher(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            while (text.find()) {
                String line = TAG.matcher(text.group(1)).replaceAll(" ").replace("&lt;", "<").replace("&gt;", ">")
                        .replace("&quot;", "\"").replace("&amp;", "&").replaceAll("\\s+", " ").strip();
                if (!line.isEmpty()) {
                    out.append('\n').append(line);
                }
            }
        } catch (java.io.IOException e) {
            // 글자를 못 뽑아도 설명은 남긴다. 검수가 통째로 멈추는 것보다 낫다
        }
        return out.toString();
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
