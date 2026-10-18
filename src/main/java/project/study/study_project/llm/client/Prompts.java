package project.study.study_project.llm.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * {@code resources/prompts/}의 프롬프트 파일을 읽는다.
 *
 * <p>줄 끝은 {@code \n}으로 맞추고, 파일 끝 줄바꿈 하나를 뗀다. 파일은 늘 줄바꿈으로 끝나게 저장한다.
 * 이렇게 하면 Windows 체크아웃(CRLF)이나 에디터가 끝에 붙이는 줄바꿈 때문에 프롬프트 바이트가 바뀌지 않는다.
 * 바이트가 바뀌면 프롬프트 캐시가 깨진다.
 */
public final class Prompts {

    private Prompts() {
    }

    /** @param path {@code prompts/} 아래 경로. 예: {@code "problem/system.txt"} */
    public static String load(String path) {
        try (InputStream in = Prompts.class.getResourceAsStream("/prompts/" + path)) {
            if (in == null) {
                throw new IllegalStateException("프롬프트 파일이 없습니다: prompts/" + path);
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
            return text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        } catch (IOException e) {
            throw new IllegalStateException("프롬프트 파일을 읽지 못했습니다: prompts/" + path, e);
        }
    }
}
