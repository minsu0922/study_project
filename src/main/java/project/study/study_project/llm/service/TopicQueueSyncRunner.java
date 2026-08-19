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
import project.study.study_project.llm.dto.TopicQueueFile;
import project.study.study_project.llm.support.TopicQueue;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 기동 시 {@code generated/_topics.json}을 읽어 DB에 반영한다 — 파일에서 DB로 오는 <b>유일한</b> 길.
 *
 * <p>가져오는 것은 둘이다.
 * <ol>
 *   <li><b>배치가 찍은 사용 표시</b>. 배치는 클라우드에서 돌아 DB에 쓸 수 없으니 파일에 날짜를
 *       찍어 커밋한다. 이걸 안 읽으면 화면에서는 그 주제가 영원히 "대기 중"으로 보이고,
 *       내보내기가 매번 다시 실어 보내 <b>같은 주제로 문서가 반복해서</b> 나온다.
 *   <li><b>사람이 파일에 손으로 적어 넣은 줄</b>(id 없음). 이게 있어야 앱을 띄우지 않고도
 *       주제를 넣을 수 있다 — 배치는 앱과 무관하게 도는 물건이라 그 의존을 만들지 않는다.
 * </ol>
 *
 * <p><b>{@link DraftImportRunner}와 다른 점</b>: 흡수 이력({@code imported_draft_file})을 쓰지
 * 않는다. 그 이력은 "같은 파일을 두 번 흡수하면 초안이 복제된다"를 막는 장치인데, 여기서는
 * 같은 파일을 몇 번 읽어도 결과가 같다 — 사용 표시는 멱등이고
 * ({@code TopicQueueItem.markUsed}), 손으로 적은 줄은 흡수되는 순간 파일에서 사라진다
 * (다음 내보내기가 id 붙은 형태로 다시 쓴다). <b>이력이 필요 없게 설계한 것</b>이지 빠뜨린 것이 아니다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.import.enabled", havingValue = "true", matchIfMissing = true)
/*
 * 내보내기(TopicQueueExporter, @Order(50))보다 반드시 <b>먼저</b> 돌아야 한다.
 * 뒤집히면 파일의 사용 표시를 읽기 전에 그 줄이 빠진 파일을 새로 써서 도장이 사라진다.
 * 초안 흡수(@Order(10))보다도 앞에 두는 것은 순서상 의미는 없지만, "파일에서 DB로"가
 * 전부 앞쪽에 모여 있는 편이 러너 목록을 읽기 쉽다.
 */
@Order(5)
@RequiredArgsConstructor
public class TopicQueueSyncRunner implements ApplicationRunner {

    private final TopicQueueService topicQueueService;
    private final ObjectMapper objectMapper;

    @Value("${llm.import.dir:generated}")
    private String importDir;

    @Override
    public void run(ApplicationArguments args) {
        Path file = Path.of(importDir).resolve(TopicQueue.FILE_NAME);
        if (!Files.exists(file)) {
            // 아직 대기열을 한 번도 안 쓴 상태다. 정상이므로 조용히 넘어간다.
            log.debug("주제 대기열 파일 없음, 동기화 건너뜀: {}", file.toAbsolutePath());
            return;
        }
        try {
            TopicQueueService.SyncResult result =
                    topicQueueService.syncFrom(objectMapper.readValue(file.toFile(), TopicQueueFile.class));
            if (result.imported() > 0) {
                // 흡수한 줄은 다음 내보내기에서 id가 붙어 파일이 바뀐다 — 커밋이 필요하다는 뜻이라 info.
                log.info("주제 대기열: 파일에 적힌 주제 {}건을 DB로 가져왔습니다(관리자 화면에서 확인하세요)",
                        result.imported());
            }
        } catch (Exception e) {
            // 손으로 고치다 깨졌을 수 있다. 부팅을 실패시키지 않는 이유는 DraftImportRunner와 같다 —
            // 대기열은 부가 기능이고, 이것 때문에 앱이 안 뜨면 본 기능까지 죽는다.
            log.warn("주제 대기열 파일을 읽지 못해 동기화를 건너뜁니다(JSON 형식 확인): {}", e.getMessage());
        }
    }
}
