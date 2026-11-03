package project.study.study_project.llm.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import project.study.study_project.document.repository.DocumentRepository;
import project.study.study_project.global.event.DocumentContentChanged;

import java.util.List;

/**
 * 정식 문서(DB {@code document})가 원본 배치 파일의 최신본이 되게 한다.
 *
 * <p>둘이 어긋나면 문제 배치가 승인 뒤 고친 내용을 모른 채 옛 본문으로 문제를 만든다
 * (docs/REVIEW_2026-11-01-flow.md 순위 1).
 *
 * <ul>
 *   <li>문서 관리에서 고치거나 되돌리면 커밋 직후 그 편을 맞춘다.</li>
 *   <li>앱이 뜰 때 전체를 한 번 맞춘다. SQL로 고친 것도 여기서 따라간다.</li>
 * </ul>
 *
 * <p>DB가 이긴다. 승인된 문서의 파일을 손으로 고쳐도 다음 기동에 DB 본문으로 돌아간다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.import.enabled", havingValue = "true", matchIfMissing = true)
// 들여오기(@Order 10)보다 뒤 — 들여오기가 초안과 파일 본문을 견줘 사실 지적을 채우므로, 그 전에 파일을 바꾸면 못 찾는다
@Order(15)
@RequiredArgsConstructor
public class ApprovedDocumentFileSync implements ApplicationRunner {

    private final DocumentRepository documentRepository;
    private final DocumentFileSync documentFileSync;

    @Override
    @Transactional(readOnly = true)
    public void run(ApplicationArguments args) {
        try {
            documentFileSync.syncLatest(documentRepository.findAll().stream()
                    .map(d -> new DocumentFileSync.Latest(d.getSlug(), d.getSlug(), d.getTitle(), d.getContentMd()))
                    .toList());
        } catch (RuntimeException e) {
            // 파일을 못 맞춰도 앱은 떠야 한다. 다음 기동이나 다음 수정 때 다시 맞춘다
            log.warn("정식 문서를 배치 파일에 맞추지 못했습니다: {}", e.getMessage());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDocumentContentChanged(DocumentContentChanged event) {
        try {
            documentFileSync.syncLatest(List.of(new DocumentFileSync.Latest(
                    event.oldSlug(), event.slug(), event.title(), event.contentMd())));
        } catch (RuntimeException e) {
            log.warn("고친 문서({})를 배치 파일에 맞추지 못했습니다(문서 수정은 정상 처리됨): {}",
                    event.slug(), e.getMessage());
        }
    }
}
