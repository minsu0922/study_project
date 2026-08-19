package project.study.study_project.llm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import project.study.study_project.llm.domain.TopicQueueItem;
import project.study.study_project.llm.dto.TopicQueueFile;
import project.study.study_project.llm.repository.TopicQueueItemRepository;
import project.study.study_project.llm.support.TopicQueue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 주제 대기열을 {@code generated/_topics.json}으로 내보낸다 — DB를 클라우드 배치까지 나르는 다리.
 *
 * <p>{@link ExistingDocumentsExporter}·{@link RejectionNotesExporter}와 같은 구조이고 판단도 같다.
 * 앱이 직접 {@code git push}를 하게 만들지 않고(권한·인증·충돌이 줄줄이 따라온다), <b>바뀐 게
 * 있을 때만</b> 파일을 쓰고 info 로그로 알린다.
 *
 * <p><b>전부 내보낸다</b>(V11에서 바뀐 점). 주제 범위는 소진되지 않고 순환하므로, 다음 차례를
 * 정하려면 배치가 <b>모든 범위와 각각의 마지막 사용일</b>을 봐야 한다. 대기 중인 것만
 * 내보내던 옛 방식으로는 순환 자체가 성립하지 않는다.
 *
 * <p><b>id를 함께 싣는 것이 이 파일의 핵심</b>이다. 배치는 그 줄에 사용 날짜만 찍고 id는 그대로
 * 두므로, 앱이 켜질 때 어느 DB 행의 이야기인지 정확히 짚을 수 있다({@link TopicQueueService#syncFrom}).
 * id 없이 주제 글자로 짝지으면 화면에서 오타를 고친 순간 짝이 끊긴다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.import.enabled", havingValue = "true", matchIfMissing = true)
/*
 * 동기화(TopicQueueSyncRunner, @Order(5))보다 <b>뒤</b>에 돌아야 한다.
 * 순서가 뒤집히면 파일에 남아 있던 사용 표시를 DB에 반영하기도 전에 그 줄을 지운 파일을
 * 새로 써 버려, 배치가 이미 쓴 주제를 다음 주기에 또 쓴다.
 * (기존 내보내기들이 20·30·40을 쓰고 있어 그 뒤인 50에 둔다.)
 */
@Order(50)
@RequiredArgsConstructor
public class TopicQueueExporter implements ApplicationRunner {

    /** 배치({@code DraftGeneratorCli})와 같은 파일을 가리킨다 — 이름이 두 곳에 적히지 않게 상수를 빌려 쓴다. */
    static final String FILE_NAME = TopicQueue.FILE_NAME;

    private static final String NOTE =
            "관리자 화면의 '주제 범위' 탭에서 내보낸 파일입니다. 배치가 문서일마다 범위 하나를 골라 "
                    + "그 안에서 세부 주제를 정해 문서를 씁니다. 다음 차례는 '아직 안 쓴 범위 먼저, "
                    + "그다음은 가장 오래 안 쓴 범위'로 정해지고, 줄은 없어지지 않습니다. "
                    + "lastUsedAt·usedCount는 배치가 적는 칸입니다. "
                    + "id는 DB 행 번호이니 지우지 마세요 — 지우면 같은 범위가 한 벌 더 생깁니다. "
                    + "id 없이 손으로 적어 넣은 줄은 다음 기동에 DB로 흡수됩니다. "
                    + "이 파일이 갱신되면 커밋해야 다음 배치부터 반영됩니다.";

    private final TopicQueueItemRepository repository;
    private final ObjectMapper objectMapper;

    @Value("${llm.import.dir:generated}")
    private String exportDir;

    @Override
    public void run(ApplicationArguments args) {
        try {
            export(Path.of(exportDir));
        } catch (Exception e) {
            // 이 기능이 실패해도 앱은 정상이어야 한다 — 대기열은 부가 기능이고,
            // 이것 때문에 부팅이 막히면 퀴즈 풀이 같은 본 기능까지 죽는다.
            log.warn("주제 대기열 내보내기 실패(무시하고 계속): {}", e.getMessage());
        }
    }

    /**
     * 대기열이 바뀌면 <b>커밋된 뒤</b> 다시 내보낸다.
     *
     * <p>{@code AFTER_COMMIT}인 이유는 {@link ReviewCompleted} 주석 그대로다 — 커밋 전에 쓰면
     * 아직 DB에 없는 상태가 파일에 남고, 롤백되면 <b>일어나지 않은 추가가 파일에 남는다</b>.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTopicQueueChanged(TopicQueueChanged event) {
        try {
            export(Path.of(exportDir));
        } catch (Exception e) {
            log.warn("주제 대기열 내보내기 실패(대기열 변경은 정상 처리됨): {}", e.getMessage());
        }
    }

    /** 디렉터리를 받아 파일을 갱신한다. 실제로 썼으면 true. */
    boolean export(Path dir) throws Exception {
        List<TopicQueueFile.Entry> entries = repository.findAllByOrderBySortOrderAsc().stream()
                .map(TopicQueueExporter::toEntry)
                .toList();

        Path file = dir.resolve(FILE_NAME);
        if (entries.isEmpty() && !Files.exists(file)) {
            // 아직 범위를 하나도 안 넣었다 — 빈 파일을 만들어 두면 커밋할 것도 없는데
            // 새 파일이 생겨 혼란만 준다(배치는 파일이 없으면 자동 선택으로 돈다).
            log.debug("주제 범위가 하나도 없어 파일을 만들지 않습니다.");
            return false;
        }
        if (!hasChanged(file, entries)) {
            log.debug("주제 범위 변경 없음: {}건", entries.size());
            return false;
        }

        Files.createDirectories(dir);
        Files.writeString(file, objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(new TopicQueueFile(NOTE, entries)));

        // info로 남긴다 — 사용자가 "커밋해야 한다"는 것을 알아야 하는 유일한 순간이다.
        log.info("주제 범위 내보내기: {} ({}건) — 커밋하면 다음 배치부터 반영됩니다",
                file, entries.size());
        return true;
    }

    /**
     * 파일과 내용이 다른지 본다. 파일이 없으면 당연히 "바뀐 것".
     *
     * <p>{@code note}는 비교하지 않는다 — 안내 문구를 다듬었다고 사용자에게 커밋할 것이
     * 생기면 안 된다. 반대로 <b>사용 기록은 비교에 포함된다</b>(record 전체 비교):
     * 배치가 날짜를 적어 둔 파일은 DB 상태와 달라지므로, 동기화가 DB에 반영한 뒤
     * 여기서 자연히 다시 쓰인다(그때는 내용이 같아져 파일이 안정된다).
     */
    private boolean hasChanged(Path file, List<TopicQueueFile.Entry> entries) {
        if (!Files.exists(file)) {
            return true;
        }
        try {
            TopicQueueFile existing = objectMapper.readValue(file.toFile(), TopicQueueFile.class);
            return !entries.equals(existing.topics() == null ? List.of() : existing.topics());
        } catch (Exception e) {
            log.debug("주제 대기열 파일을 읽지 못해 새로 씁니다: {}", e.getMessage());
            return true;
        }
    }

    /**
     * 엔티티 → 파일 한 줄.
     *
     * <p>편수가 0이면 칸 자체를 빼서 내보낸다({@code null}). 아직 안 쓴 범위에 {@code "usedCount": 0}이
     * 붙어 있으면 사람이 손으로 고칠 때 <b>고쳐도 되는 값처럼</b> 보인다 — 이 파일에서 사람 몫은
     * 범위·분야·메모뿐이다. 안 쓴 범위는 {@code lastUsedAt}도 함께 비어 한눈에 구분된다.
     */
    private static TopicQueueFile.Entry toEntry(TopicQueueItem item) {
        return new TopicQueueFile.Entry(
                item.getId(), item.getDomain().name(), item.getTopic(), item.getMemo(),
                item.getLastUsedAt() == null ? null : item.getLastUsedAt().toString(),
                item.getUsedCount() == 0 ? null : item.getUsedCount());
    }
}
