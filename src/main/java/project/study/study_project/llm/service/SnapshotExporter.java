package project.study.study_project.llm.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * DB의 한 조각을 {@code generated/} 아래 JSON 파일로 내보내는 공통 뼈대.
 *
 * <h2>왜 있나 — 같은 코드가 네 벌이었다</h2>
 *
 * <p>{@link ExistingQuestionsExporter}, {@link ExistingDocumentsExporter},
 * {@link RejectionNotesExporter}, {@link TopicQueueExporter}는 import 위치와 메서드 순서까지
 * 거의 같았다. 다른 것은 <b>무엇을 조회해 어떤 모양으로 쓰느냐</b>뿐인데, 그 주위를 감싸는
 * "언제 도는가 · 어디에 쓰는가 · 바뀌었을 때만 쓴다 · 실패해도 앱은 살린다"가 네 번 복사돼 있었다.
 * 스냅샷이 하나 늘 때마다 그 150줄이 통째로 또 복사되고, 규칙이 바뀌면 네 곳을 같이 고쳐야 했다.
 *
 * <h2>이 뼈대가 지키는 규칙 넷</h2>
 *
 * <ol>
 *   <li><b>실패해도 앱은 정상이어야 한다.</b> 스냅샷은 부가 기능이라, 이것 때문에 부팅이 막히면
 *       퀴즈 풀이 같은 본 기능까지 죽는다. 그래서 모든 실패를 삼키고 warn 로그만 남긴다.
 *   <li><b>바뀐 게 있을 때만 쓴다.</b> 앱이 직접 {@code git push}하지 않기 때문에(권한·인증·충돌이
 *       줄줄이 따라온다) 사람이 커밋해야 하는데, 매번 파일을 새로 쓰면 <b>켤 때마다 git이 변경으로
 *       인식</b>해 진짜 바뀐 날을 알아볼 수 없게 된다.
 *   <li><b>쓸 때는 info로 알린다.</b> "커밋해야 한다"를 사용자가 알아야 하는 유일한 순간이라
 *       debug로 묻으면 기능이 조용히 무력해진다.
 *   <li><b>파일이 깨졌으면 새로 쓴다.</b> 읽지 못하는 파일을 붙들고 있는 것보다 낫다.
 * </ol>
 *
 * <h2>남겨 둔 것 — 깨우는 신호</h2>
 *
 * <p>{@code @TransactionalEventListener}는 각 하위 클래스에 그대로 둔다. 넷이 서로 다른 신호를
 * 듣기 때문이다({@code ReviewCompleted}의 PROBLEM 둘 · DOCUMENT 하나, {@code TopicQueueChanged} 하나).
 * 억지로 한 곳에 모으면 "누가 어떤 신호에 반응하는가"를 알려고 분기표를 읽어야 한다 —
 * 지금처럼 각자 자기 리스너를 갖고 있는 편이 짧고 정직하다. 대신 리스너 본문은
 * {@link #exportQuietly(String)} 한 줄로 끝난다.
 */
@Slf4j
public abstract class SnapshotExporter implements ApplicationRunner {

    /**
     * 비교에서 제외하는 필드들.
     *
     * <p>{@code exportedAt}은 날이 바뀔 때마다 달라지고 {@code note}는 안내 문구라, 이 둘을 비교에
     * 넣으면 <b>내용이 그대로인데도 파일이 바뀐 것</b>이 되어 사용자에게 커밋할 것이 계속 생긴다.
     * 예전에는 클래스마다 "이 필드만 비교한다"를 손으로 적었는데, 넷 다 결국 같은 말이라
     * 여기 한 곳에 모았다 — 새 스냅샷을 만들 때 이 규칙을 깜빡할 자리가 사라진다.
     */
    private static final String[] VOLATILE_FIELDS = {"exportedAt", "note"};

    protected final ObjectMapper objectMapper;

    /**
     * 생성 결과 파일과 같은 디렉터리를 쓴다 — Actions가 보는 곳이 한 군데여야 한다.
     *
     * <p>필드 주입인 것은 의도다. 이 값은 협력 객체가 아니라 설정값이고, 하위 클래스 네 곳의
     * 생성자에 같은 파라미터를 하나씩 더 얹는 것보다 여기 한 번 두는 쪽이 낫다.
     * (테스트는 이 값을 쓰지 않는다 — {@link #export(Path)}에 디렉터리를 직접 넘긴다.)
     */
    @Value("${llm.import.dir:generated}")
    private String exportDir;

    protected SnapshotExporter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 내보낼 내용 한 벌.
     *
     * @param payload 파일에 그대로 직렬화될 객체
     * @param detail  info 로그 끝에 붙일 설명(예: {@code "12건"}, {@code "제목 3건, 태그 5건"}).
     *                건수 표기가 스냅샷마다 달라 문자열로 받는다 — 숫자 하나로 못 박으면
     *                문서 스냅샷처럼 셀 것이 여럿인 경우를 표현할 수 없다
     */
    protected record Snapshot(Object payload, String detail) {
    }

    /* ── 하위 클래스가 채우는 것 ─────────────────────────────── */

    /** 파일 이름. {@code _} 접두사는 흡수 대상(날짜 파일)과 섞이지 않게 하는 관례다. */
    protected abstract String fileName();

    /** 로그에 쓰는 사람 말 이름(예: {@code "기존 지문 스냅샷"}). */
    protected abstract String label();

    /**
     * 내보낼 내용을 만든다. <b>{@code null}을 돌려주면 이번에는 쓰지 않는다.</b>
     *
     * <p>"쓸 것이 없다"의 뜻이 스냅샷마다 달라서 이 판단을 하위 클래스에 맡긴다 —
     * 거절 사례는 하나도 없으면 아예 안 만들지만, 지문 목록은 <b>이미 파일이 있다면</b>
     * 빈 목록으로라도 갱신해야 지워진 문제가 회피 목록에 남지 않는다. 그 구분을 위해
     * 파일 존재 여부를 넘겨 준다.
     *
     * @param fileExists 지금 그 파일이 이미 있는지
     */
    protected abstract Snapshot build(boolean fileExists);

    /* ── 뼈대 ────────────────────────────────────────────────── */

    /**
     * 앱이 뜰 때 한 번. {@code final}인 이유: 하위 클래스가 이 자리를 덮어쓰면 위 규칙 넷 중
     * "실패해도 앱은 살린다"가 조용히 깨진다.
     */
    @Override
    public final void run(ApplicationArguments args) {
        exportQuietly("무시하고 계속");
    }

    /**
     * 실패를 삼키고 내보낸다 — 하위 클래스의 이벤트 리스너가 부르는 자리.
     *
     * @param onFailure 실패했을 때 로그에 덧붙일 말(예: {@code "검수는 정상 처리됨"}).
     *                  같은 실패라도 부팅 중인지 검수 직후인지에 따라 사람이 할 일이 달라서,
     *                  그 맥락을 부르는 쪽이 넘긴다
     */
    protected final void exportQuietly(String onFailure) {
        try {
            export(Path.of(exportDir));
        } catch (Exception e) {
            log.warn("{} 내보내기 실패({}): {}", label(), onFailure, e.getMessage());
        }
    }

    /**
     * 디렉터리를 받아 스냅샷을 갱신한다. 실제로 파일을 썼으면 {@code true}.
     *
     * <p>패키지 전용이고 디렉터리를 인자로 받는 것은 <b>테스트 때문</b>이다 — 임시 폴더를 넘겨
     * 설정값과 무관하게 검증한다. 이 시그니처는 네 스냅샷의 기존 테스트가 그대로 쓰고 있으므로
     * 바꾸지 않는다.
     */
    final boolean export(Path dir) throws Exception {
        Path file = dir.resolve(fileName());

        Snapshot snapshot = build(Files.exists(file));
        if (snapshot == null) {
            log.debug("{}: 내보낼 내용이 없어 파일을 만들지 않습니다.", label());
            return false;
        }

        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot.payload());
        if (!hasChanged(file, json)) {
            log.debug("{} 변경 없음: {}", label(), snapshot.detail());
            return false;
        }

        Files.createDirectories(dir);
        Files.writeString(file, json);

        log.info("{} 갱신: {} ({}) — 커밋하면 다음 배치부터 반영됩니다", label(), file, snapshot.detail());
        return true;
    }

    /**
     * 새로 쓸 내용이 지금 파일과 다른지 본다. 파일이 없거나 읽을 수 없으면 "바뀐 것"으로 본다.
     *
     * <p><b>객체가 아니라 JSON을 비교한다.</b> 예전에는 스냅샷마다 "questions만 비교" "titles·tags·
     * rejectedSlugs를 비교"처럼 손으로 필드를 골랐는데, 결국 넷 다 {@link #VOLATILE_FIELDS}만
     * 빼고 나머지 전부를 비교하는 것이었다. JSON 트리에서 그 둘을 지우고 견주면 같은 일을
     * 타입과 무관하게 할 수 있고, 스냅샷에 필드가 하나 늘어도 비교 코드를 따라 고칠 일이 없다.
     *
     * <p><b>알려진 차이 하나</b>: 옛 파일에 아예 없던 필드가 새 형식에서 빈 배열로 생기면
     * (문서 스냅샷의 {@code rejectedSlugs}가 그랬다) "없음"과 "빈 배열"이 달라 파일을 한 번 더 쓴다.
     * 예전 코드는 그 경우를 {@code null → List.of()}로 맞춰 넘겼다. 한 번 쓰고 나면 같아져
     * 안정되므로 그대로 둔다 — 형식이 바뀐 첫 기동에 파일이 갱신되는 것은 오히려 맞는 동작이다.
     */
    private boolean hasChanged(Path file, String newJson) {
        if (!Files.exists(file)) {
            return true;
        }
        try {
            JsonNode before = stripVolatile(objectMapper.readTree(file.toFile()));
            JsonNode after = stripVolatile(objectMapper.readTree(newJson));
            return !before.equals(after);
        } catch (Exception e) {
            log.debug("{}: 기존 파일을 읽지 못해 새로 씁니다: {}", label(), e.getMessage());
            return true;
        }
    }

    /** 비교 전에 흔들리는 필드를 걷어낸다. 최상위만 본다 — 그 필드들이 사는 곳이 거기뿐이다. */
    private JsonNode stripVolatile(JsonNode node) {
        if (node instanceof ObjectNode object) {
            object.remove(java.util.Arrays.asList(VOLATILE_FIELDS));
        }
        return node;
    }
}
