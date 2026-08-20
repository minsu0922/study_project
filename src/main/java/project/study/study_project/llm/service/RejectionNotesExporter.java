package project.study.study_project.llm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import project.study.study_project.llm.client.RejectionNote;
import project.study.study_project.llm.dto.RejectionNotesFile;

import java.time.LocalDate;
import java.util.List;

/**
 * 검수자의 거절 사례를 스냅샷 파일로 내보낸다 — 사람의 판단을 클라우드 배치까지 실어 나르는 다리(docs/14).
 *
 * <p><b>왜 필요한가.</b> 거절 사유는 로컬 DB에 쌓이는데 문제를 만드는 곳은 GitHub Actions다.
 * 관리자 수동 생성은 DB를 직접 읽으면 되지만, 배치는 DB를 볼 수 없다. 그래서 앱이 뜰 때마다
 * 최신 거절 사례를 {@code generated/_rejection-notes.json}으로 내보내고, 사용자가 이 파일을
 * 커밋하면 다음 배치부터 반영된다.
 *
 * <p><b>커밋이 수동인 것을 감수한 이유</b>와 <b>바뀐 게 있을 때만 쓰는 규칙</b>은 형제들과 공유하므로
 * {@link SnapshotExporter}에 적혀 있다. 여기 남은 것은 무엇을 모아 어떤 모양으로 쓰는가뿐이다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.import.enabled", havingValue = "true", matchIfMissing = true)
@Order(20) // 흡수(DraftImportRunner)보다 뒤에 — 흡수로 들어온 초안은 아직 검수 전이라 결과가 달라지진 않지만, 로그 순서가 "받고 → 내보내고"로 읽힌다
public class RejectionNotesExporter extends SnapshotExporter {

    /** 배치가 읽는 파일명. {@code _} 접두사라 흡수 대상(날짜 파일)과 섞이지 않는다. */
    static final String FILE_NAME = "_rejection-notes.json";

    private static final String NOTE =
            "검수자가 거절한 사례입니다. GitHub Actions 배치가 프롬프트에 넣어 같은 실수를 줄입니다(docs/14). "
                    + "이 파일이 갱신되면 커밋해야 다음 배치부터 반영됩니다.";

    private final LlmProblemService llmProblemService;

    public RejectionNotesExporter(LlmProblemService llmProblemService, ObjectMapper objectMapper) {
        super(objectMapper);
        this.llmProblemService = llmProblemService;
    }

    @Override
    protected String fileName() {
        return FILE_NAME;
    }

    @Override
    protected String label() {
        return "거절 사유 스냅샷";
    }

    /**
     * <p>거절 이력이 하나도 없으면 <b>파일이 있든 없든</b> 만들지 않는다. 지문 스냅샷과 다른 점인데,
     * 빈 파일을 두면 배치가 빈 블록을 프롬프트에 넣게 되기 때문이다. 거절 사례는 "없으면 안 넣는다"가
     * 자연스럽지만, 지문 목록은 "비었다"는 사실 자체가 회피 목록에 의미를 갖는다.
     */
    @Override
    protected Snapshot build(boolean fileExists) {
        List<RejectionNote> notes = llmProblemService.findRecentRejectionNotes();
        if (notes.isEmpty()) {
            return null;
        }
        return new Snapshot(
                new RejectionNotesFile(NOTE, LocalDate.now().toString(), notes),
                notes.size() + "건");
    }

    /**
     * 검수가 <b>커밋된 뒤</b> 스냅샷을 다시 찍는다 — 자세한 배경은 {@link ReviewCompleted}.
     *
     * <p>PROBLEM 외의 신호는 흘려보낸다. 바뀔 리 없는 파일을 다시 읽고 비교하는 비용은
     * 작지만, 로그에 "변경 없음"이 두 배로 쌓여 진짜 갱신이 묻힌다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReviewCompleted(ReviewCompleted event) {
        if (event.target() != ReviewCompleted.Target.PROBLEM) {
            return;
        }
        exportQuietly("검수는 정상 처리됨");
    }
}
