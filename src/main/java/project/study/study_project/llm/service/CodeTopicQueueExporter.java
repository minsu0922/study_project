package project.study.study_project.llm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import project.study.study_project.llm.domain.TopicKind;
import project.study.study_project.llm.dto.TopicQueueFile;
import project.study.study_project.llm.repository.TopicQueueItemRepository;
import project.study.study_project.llm.support.TopicQueue;

import java.util.List;

/**
 * 자바 코드 문제 주제를 {@code generated/_code-topics.json}으로 내보낸다.
 *
 * <p>개념 문서 주제({@link TopicQueueExporter})와 파일을 나눈 이유: 한 파일에 섞으면 문서 배치가
 * 주제를 고르는 자리마다 종류를 검사해야 하고, 하나만 빠져도 자바 문법 주제로 개념 문서가 생성된다.
 * 파일을 나누면 문서 배치는 코드 주제를 볼 길이 없다.
 *
 * <p>순서(51)는 {@link TopicQueueExporter} 바로 뒤다. 둘 다 파일의 사용 표시를 DB로 옮기는
 * {@code TopicQueueSyncRunner}(5)보다 뒤에 돌아야 한다는 사정이 같다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.import.enabled", havingValue = "true", matchIfMissing = true)
@Order(51)
public class CodeTopicQueueExporter extends SnapshotExporter {

    private static final String NOTE =
            "관리자 화면의 '코드 문제 주제' 탭에서 내보낸 파일입니다. 주 1회 코드 문제 배치가 주제 하나를 골라 "
                    + "자바 코드 문제를 냅니다. 다음 차례는 '아직 안 쓴 주제 먼저, 그다음은 가장 오래 안 쓴 주제'입니다. "
                    + "lastUsedAt·usedCount는 배치가 적는 칸이고, id는 DB 행 번호이니 지우지 마세요. "
                    + "이 파일이 갱신되면 커밋해야 다음 배치부터 반영됩니다.";

    private final TopicQueueItemRepository repository;

    public CodeTopicQueueExporter(TopicQueueItemRepository repository, ObjectMapper objectMapper) {
        super(objectMapper);
        this.repository = repository;
    }

    @Override
    protected String fileName() {
        return TopicQueue.CODE_FILE_NAME;
    }

    @Override
    protected String label() {
        return "코드 문제 주제";
    }

    @Override
    protected Snapshot build(boolean fileExists) {
        List<TopicQueueFile.Entry> entries = repository.findAllByKindOrderBySortOrderAsc(TopicKind.CODE).stream()
                .map(TopicQueueExporter::toEntry)
                .toList();
        // 코드 주제를 하나도 안 넣었으면 파일을 만들지 않는다. 빈 파일이 커밋되면 쓰는 기능처럼 보인다
        if (entries.isEmpty() && !fileExists) {
            return null;
        }
        return new Snapshot(new TopicQueueFile(NOTE, entries), entries.size() + "건");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTopicQueueChanged(TopicQueueChanged event) {
        exportQuietly("대기열 변경은 정상 처리됨");
    }
}
