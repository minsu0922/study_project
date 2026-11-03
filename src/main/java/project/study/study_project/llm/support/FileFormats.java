package project.study.study_project.llm.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Path;

/**
 * {@code generated/} 파일의 형식 버전(docs/14 "형식 버전", REVIEW 2026-11-01 2장).
 *
 * <p>앱(내 PC)과 배치(GitHub Actions)는 서로 다른 시점의 코드로 같은 파일을 쓰고 읽는다. 쓰는 쪽이 파일 맨 앞에
 * {@code formatVersion}을 찍고, 읽는 쪽은 자기가 아는 버전보다 새 파일이면 읽지 않고 이유를 남긴다.
 * 버전 칸은 DTO에 두지 않는다. 쓸 때 찍고 읽을 때 걷어 내므로 파일 형식 DTO와 생성자는 그대로다.
 */
public final class FileFormats {

    /** 파일에 찍는 칸 이름. 버전이 없는 옛 파일은 1로 본다 */
    public static final String FIELD = "formatVersion";

    /**
     * 파일 종류와 이 코드가 쓰고 읽는 버전.
     *
     * <p><b>읽는 쪽이 깨질 때만 올린다.</b> 칸을 지우거나 이름·뜻을 바꿀 때, 읽는 쪽이 반드시 알아야 하는 칸을 더할 때다.
     * 없어도 되는 칸을 더할 때는 그대로 둔다(읽는 쪽이 모르는 칸을 무시하거나 엄격하게 읽어도 버전 확인이 먼저다).
     */
    public enum Kind {
        /** 문제 결과 파일 {@code <날짜>.json}, {@code <날짜>-code-*.json} */
        BATCH_PROBLEMS(1),
        /** 원문 문서 파일 {@code documents/<날짜>.json} */
        DOCUMENTS(1),
        EXISTING_QUESTIONS(1),
        EXISTING_DOCUMENTS(1),
        REJECTION_NOTES(1),
        DOMAIN_SETTINGS(1),
        /** {@code _topics.json}, {@code _code-topics.json} */
        TOPIC_QUEUE(1),
        /** {@code _topic-usage.json}, {@code _code-topic-usage.json} */
        TOPIC_USAGE(1);

        private final int current;

        Kind(int current) {
            this.current = current;
        }

        public int current() {
            return current;
        }
    }

    /** 이 코드보다 새 형식의 파일. IllegalArgumentException이라 들여오기는 이력을 남기지 않고 건너뛴다 */
    public static class NewerFormatException extends IllegalArgumentException {
        public NewerFormatException(String fileName, int version, int supported) {
            super("`" + fileName + "`은 형식 버전 " + version + "이라 이 코드(버전 " + supported
                    + "까지)가 읽을 수 없습니다. 읽는 쪽 코드를 최신으로 맞추세요(앱이면 pull한 뒤 다시 띄우기).");
        }
    }

    private FileFormats() {
    }

    /** 파일에 적힌 버전. 없으면 1 */
    public static int versionOf(JsonNode tree) {
        JsonNode v = tree == null ? null : tree.get(FIELD);
        return v == null || !v.canConvertToInt() ? 1 : v.asInt();
    }

    /** 이 코드가 읽을 수 있는 버전인지 본다. 더 새것이면 던진다 */
    public static void check(JsonNode tree, Kind kind, String fileName) {
        int version = versionOf(tree);
        if (version > kind.current()) {
            throw new NewerFormatException(fileName, version, kind.current());
        }
    }

    /** 버전을 확인하고 칸을 걷어 낸 뒤 읽는다. 엄격한 매퍼로 읽어도 버전 칸 때문에 실패하지 않는다 */
    public static <T> T read(ObjectMapper mapper, Path file, Kind kind, Class<T> type) throws IOException {
        return fromTree(mapper, mapper.readTree(file.toFile()), kind, type, file.getFileName().toString());
    }

    /** 이미 읽은 글에서 읽는다 */
    public static <T> T read(ObjectMapper mapper, String json, Kind kind, Class<T> type, String fileName)
            throws IOException {
        return fromTree(mapper, mapper.readTree(json), kind, type, fileName);
    }

    private static <T> T fromTree(ObjectMapper mapper, JsonNode tree, Kind kind, Class<T> type, String fileName)
            throws IOException {
        check(tree, kind, fileName);
        if (tree instanceof ObjectNode object) {
            object.remove(FIELD);
        }
        return mapper.treeToValue(tree, type);
    }

    /** 파일에 쓸 글. {@code formatVersion}을 맨 앞에 찍고 보기 좋게 줄을 나눈다 */
    public static String toJson(ObjectMapper mapper, Kind kind, Object payload) throws IOException {
        JsonNode body = mapper.valueToTree(payload);
        ObjectNode stamped = mapper.createObjectNode();
        stamped.put(FIELD, kind.current());
        if (body instanceof ObjectNode object) {
            object.remove(FIELD);
            stamped.setAll(object);
        }
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(stamped);
    }
}
