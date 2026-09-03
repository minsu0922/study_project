package project.study.study_project.llm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import project.study.study_project.document.repository.DocumentRepository;
import project.study.study_project.llm.dto.ExistingDocumentsFile;
import project.study.study_project.llm.dto.DomainTitle;
import project.study.study_project.llm.repository.GeneratedDocumentDraftRepository;
import project.study.study_project.tag.domain.Tag;
import project.study.study_project.tag.repository.TagRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 기존 문서의 제목·태그를 스냅샷 파일로 내보낸다 — 로컬 DB를 클라우드 배치까지 실어 나르는 다리(docs/15).
 *
 * <p><b>왜 필요한가.</b> 문서를 만드는 곳은 GitHub Actions인데 "이미 어떤 문서가 있는지"는
 * 로컬 DB에만 있다. 이게 없으면 배치는 매번 백지 상태에서 주제를 골라
 * <b>같은 주제를 몇 번이고 다시 쓴다</b>. 태그도 마찬가지여서 tcp / TCP / tcp-handshake처럼
 * 뜻이 겹치는 태그가 계속 늘어난다.
 *
 * <p><b>검수 대기 초안의 제목까지 넣는다</b>가 문제 쪽과 다른 점 하나다. 승인을 며칠 미루는 동안
 * 정식 문서 목록에는 그 주제가 없으므로, 초안을 빼면 배치가 <b>대기함에 이미 있는 주제를
 * 또 만든다</b>. 문서는 하루 한 편이라 그 낭비가 곧 그날치 전부를 버리는 것과 같다.
 *
 * <p>파일을 언제 쓰고 언제 안 쓰는지 같은 공통 규칙은 {@link SnapshotExporter}에 있다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.import.enabled", havingValue = "true", matchIfMissing = true)
@Order(30) // 흡수(DraftImportRunner)보다 뒤 — 방금 들어온 초안의 제목까지 스냅샷에 포함되게 한다
public class ExistingDocumentsExporter extends SnapshotExporter {

    /** 배치({@code DraftGeneratorCli})가 읽는 파일명. {@code _} 접두사라 흡수 대상과 섞이지 않는다. */
    static final String FILE_NAME = "_existing-documents.json";

    private static final String NOTE =
            "정식 document 테이블 + 검수 대기 초안의 제목·태그 스냅샷입니다. "
                    + "GitHub Actions 배치가 주제 중복 회피와 태그 재사용에 쓰고, "
                    + "rejectedSlugs는 '이 문서로는 문제를 만들지 마라'는 목록입니다"
                    + "(클라우드에는 DB가 없으므로). docs/15 참고. "
                    + "이 파일이 갱신되면 커밋해야 다음 배치부터 반영됩니다.";

    private final DocumentRepository documentRepository;
    private final GeneratedDocumentDraftRepository draftRepository;
    private final TagRepository tagRepository;

    public ExistingDocumentsExporter(DocumentRepository documentRepository,
                                     GeneratedDocumentDraftRepository draftRepository,
                                     TagRepository tagRepository,
                                     ObjectMapper objectMapper) {
        super(objectMapper);
        this.documentRepository = documentRepository;
        this.draftRepository = draftRepository;
        this.tagRepository = tagRepository;
    }

    @Override
    protected String fileName() {
        return FILE_NAME;
    }

    @Override
    protected String label() {
        return "기존 문서 스냅샷";
    }

    /**
     * <p>제목과 태그가 <b>둘 다</b> 비었을 때만 건너뛴다. 거절 slug는 판단에 넣지 않는다 —
     * 문서가 하나도 없는데 거절 slug만 있는 상태는 없기 때문이다.
     */
    @Override
    protected Snapshot build(boolean fileExists) {
        // 정식 문서 + 아직 검수 안 된 초안. 순서를 고정해야 "내용이 같은데 파일이 바뀐" 것으로
        // 보이지 않는다.
        // 제목 앞에 [분야]를 붙인다(2026-09-03). 새 user 메시지가 "같은 분야에 이미 문서가
        // 있으면 그 문서가 다룬 메커니즘과 겹치는 것도 고르지 마라"를 요구하는데,
        // 제목만으로는 모델이 그 판단을 할 수 없다.
        //
        // 파일 형식(List<String>)은 그대로 둔다. 구조를 바꾸면 이미 커밋된 스냅샷을 읽는
        // 배치가 깨지는데, 라벨을 문자열에 녹이면 옛 파일은 라벨 없이 그대로 읽힌다.
        List<String> titles = new ArrayList<>(
                documentRepository.findAllDomainTitles().stream().map(DomainTitle::labeled).toList());
        draftRepository.findPendingDomainTitles().stream().map(DomainTitle::labeled).forEach(titles::add);

        List<String> tags = tagRepository.findAll().stream()
                .map(Tag::getName)
                .sorted() // 태그는 DB 순서가 보장되지 않으므로 이름순으로 못 박는다
                .toList();

        // 거절된 문서 slug(2단계) — 배치가 이 문서로는 문제를 만들지 않게 한다
        List<String> rejectedSlugs = draftRepository.findRejectedSlugs();

        if (titles.isEmpty() && tags.isEmpty()) {
            return null;
        }
        return new Snapshot(
                new ExistingDocumentsFile(NOTE, LocalDate.now().toString(), titles, tags, rejectedSlugs),
                "제목 %d건, 태그 %d건, 거절 %d건".formatted(titles.size(), tags.size(), rejectedSlugs.size()));
    }

    /**
     * 검수가 <b>커밋된 뒤</b> 스냅샷을 다시 찍는다 — 자세한 배경은 {@link ReviewCompleted}.
     *
     * <p>DOCUMENT 외의 신호는 흘려보낸다. 바뀔 리 없는 파일을 다시 읽고 비교하는 비용은
     * 작지만, 로그에 "변경 없음"이 두 배로 쌓여 진짜 갱신이 묻힌다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReviewCompleted(ReviewCompleted event) {
        if (event.target() != ReviewCompleted.Target.DOCUMENT) {
            return;
        }
        exportQuietly("검수는 정상 처리됨");
    }
}
