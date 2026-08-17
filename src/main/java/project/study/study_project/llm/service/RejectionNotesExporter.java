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
import project.study.study_project.llm.client.RejectionNote;
import project.study.study_project.llm.dto.RejectionNotesFile;

import java.nio.file.Files;
import java.nio.file.Path;
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
 * <p><b>커밋이 수동인 것을 감수한 이유.</b> 앱이 직접 {@code git push}를 하게 만드는 대안도
 * 있었지만, 애플리케이션이 자기 소스 저장소에 쓰기를 시작하면 권한·인증·충돌 처리가 줄줄이
 * 따라온다 — 하루 한 줄 커밋을 아끼자고 앱에 git 클라이언트를 심는 것은 손해다.
 * 대신 <b>바뀐 게 있을 때만</b> 파일을 쓰고 로그로 알려, 커밋할 것이 있는지 헷갈리지 않게 한다.
 *
 * <p><b>내용이 같으면 파일을 다시 쓰지 않는다</b>가 이 클래스의 핵심 규칙이다. 매 부팅마다
 * "내보낸 시각"만 바뀐 파일을 새로 쓰면 <b>앱을 켤 때마다 git이 변경으로 인식</b>해서,
 * 진짜 바뀐 날을 알아볼 수 없게 되고 "커밋할 게 항상 있는" 상태가 된다. 그래서 시각은
 * 날짜 단위로만 기록하고, 비교는 사례 목록으로만 한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.import.enabled", havingValue = "true", matchIfMissing = true)
@Order(20) // 흡수(DraftImportRunner)보다 뒤에 — 흡수로 들어온 초안은 아직 검수 전이라 결과가 달라지진 않지만, 로그 순서가 "받고 → 내보내고"로 읽힌다
@RequiredArgsConstructor
public class RejectionNotesExporter implements ApplicationRunner {

    /** 배치가 읽는 파일명. {@code _} 접두사라 흡수 대상(날짜 파일)과 섞이지 않는다. */
    static final String FILE_NAME = "_rejection-notes.json";

    private final LlmProblemService llmProblemService;
    private final ObjectMapper objectMapper;

    /** 생성 결과 파일과 같은 디렉터리를 쓴다 — Actions가 보는 곳이 한 군데여야 한다. */
    @Value("${llm.import.dir:generated}")
    private String exportDir;

    @Override
    public void run(ApplicationArguments args) {
        try {
            export(Path.of(exportDir));
        } catch (Exception e) {
            // 이 기능이 실패해도 앱은 정상이어야 한다 — 프롬프트 품질 개선은 부가 기능이고,
            // 이것 때문에 부팅이 막히면 퀴즈 풀이 같은 본 기능까지 죽는다.
            log.warn("거절 사유 스냅샷 내보내기 실패(무시하고 계속): {}", e.getMessage());
        }
    }

    /**
     * 검수가 <b>커밋된 뒤</b> 스냅샷을 다시 찍는다 — 자세한 배경은 {@link ReviewCompleted}.
     *
     * <p>PROBLEM 외의 신호는 흘려보낸다. 바뀔 리 없는 파일을 다시 읽고 비교하는 비용은
     * 작지만, 로그에 "변경 없음"이 두 배로 쌓여 진짜 갱신이 묻힌다.
     *
     * <p>실패해도 검수는 성공으로 남는다({@code run}과 같은 판단). 커밋이 이미 끝난 뒤라
     * 되돌릴 것이 없고, 스냅샷은 부가 기능이라 이것 때문에 검수를 막으면 안 된다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReviewCompleted(ReviewCompleted event) {
        if (event.target() != ReviewCompleted.Target.PROBLEM) {
            return;
        }
        try {
            export(Path.of(exportDir));
        } catch (Exception e) {
            log.warn("거절 사유 스냅샷 내보내기 실패(검수는 정상 처리됨): {}", e.getMessage());
        }
    }

    /** 디렉터리를 받아 스냅샷을 갱신한다. 실제로 파일을 썼으면 true. */
    boolean export(Path dir) throws Exception {
        List<RejectionNote> notes = llmProblemService.findRecentRejectionNotes();
        if (notes.isEmpty()) {
            // 거절 이력이 아직 없다 — 빈 파일을 만들면 배치가 빈 블록을 프롬프트에 넣게 되고,
            // 커밋할 것도 없는데 새 파일이 생겨 혼란만 준다.
            log.debug("거절 이력이 없어 스냅샷을 만들지 않습니다.");
            return false;
        }

        Path file = dir.resolve(FILE_NAME);
        if (!hasChanged(file, notes)) {
            log.debug("거절 사유 스냅샷 변경 없음: {}건", notes.size());
            return false;
        }

        RejectionNotesFile snapshot = new RejectionNotesFile(
                "검수자가 거절한 사례입니다. GitHub Actions 배치가 프롬프트에 넣어 같은 실수를 줄입니다(docs/14). "
                        + "이 파일이 갱신되면 커밋해야 다음 배치부터 반영됩니다.",
                LocalDate.now().toString(), notes);

        Files.createDirectories(dir);
        Files.writeString(file, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot));

        // info로 남긴다 — 사용자가 "커밋해야 한다"는 것을 알아야 하는 유일한 순간이라
        // debug로 묻으면 기능이 조용히 무력해진다.
        log.info("거절 사유 스냅샷 갱신: {} ({}건) — 커밋하면 다음 배치부터 프롬프트에 반영됩니다",
                file, notes.size());
        return true;
    }

    /**
     * 기존 파일과 내용(사례 목록)이 다른지 본다. 파일이 없으면 당연히 "바뀐 것".
     *
     * <p>파일 전체가 아니라 {@code notes}만 비교하는 이유는 위 클래스 주석대로 — 내보낸 날짜까지
     * 비교하면 날이 바뀔 때마다 내용이 같아도 파일을 새로 쓰게 된다.
     */
    private boolean hasChanged(Path file, List<RejectionNote> notes) throws Exception {
        if (!Files.exists(file)) {
            return true;
        }
        try {
            RejectionNotesFile existing = objectMapper.readValue(file.toFile(), RejectionNotesFile.class);
            return !notes.equals(existing.notes());
        } catch (Exception e) {
            // 파일이 깨졌거나 형식이 바뀐 경우 — 새로 쓰는 쪽이 안전하다
            log.debug("기존 스냅샷을 읽지 못해 새로 씁니다: {}", e.getMessage());
            return true;
        }
    }
}
