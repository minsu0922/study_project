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
import project.study.study_project.document.repository.DocumentRepository;
import project.study.study_project.llm.dto.ExistingDocumentsFile;
import project.study.study_project.llm.repository.GeneratedDocumentDraftRepository;
import project.study.study_project.tag.domain.Tag;
import project.study.study_project.tag.repository.TagRepository;

import java.nio.file.Files;
import java.nio.file.Path;
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
 * <p>{@link RejectionNotesExporter}와 같은 구조이고 판단도 같다 — 앱이 직접 {@code git push}를
 * 하게 만들지 않는다(권한·인증·충돌 처리가 줄줄이 따라온다). 대신 <b>바뀐 게 있을 때만</b>
 * 파일을 쓰고 info 로그로 알려, 커밋할 것이 있는지 헷갈리지 않게 한다.
 *
 * <p><b>검수 대기 초안의 제목까지 넣는다</b>가 문제 쪽과 다른 점 하나다. 승인을 며칠 미루는 동안
 * 정식 문서 목록에는 그 주제가 없으므로, 초안을 빼면 배치가 <b>대기함에 이미 있는 주제를
 * 또 만든다</b>. 문서는 하루 한 편이라 그 낭비가 곧 그날치 전부를 버리는 것과 같다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.import.enabled", havingValue = "true", matchIfMissing = true)
@Order(30) // 흡수(DraftImportRunner)보다 뒤 — 방금 들어온 초안의 제목까지 스냅샷에 포함되게 한다
@RequiredArgsConstructor
public class ExistingDocumentsExporter implements ApplicationRunner {

    /** 배치({@code DraftGeneratorCli})가 읽는 파일명. {@code _} 접두사라 흡수 대상과 섞이지 않는다. */
    static final String FILE_NAME = "_existing-documents.json";

    private final DocumentRepository documentRepository;
    private final GeneratedDocumentDraftRepository draftRepository;
    private final TagRepository tagRepository;
    private final ObjectMapper objectMapper;

    /** 생성 결과 파일과 같은 디렉터리를 쓴다 — Actions가 보는 곳이 한 군데여야 한다. */
    @Value("${llm.import.dir:generated}")
    private String exportDir;

    @Override
    public void run(ApplicationArguments args) {
        try {
            export(Path.of(exportDir));
        } catch (Exception e) {
            // 이 기능이 실패해도 앱은 정상이어야 한다 — 중복 회피는 부가 기능이고,
            // 이것 때문에 부팅이 막히면 퀴즈 풀이 같은 본 기능까지 죽는다.
            log.warn("기존 문서 스냅샷 내보내기 실패(무시하고 계속): {}", e.getMessage());
        }
    }

    /**
     * 검수가 <b>커밋된 뒤</b> 스냅샷을 다시 찍는다 — 자세한 배경은 {@link ReviewCompleted}.
     *
     * <p>DOCUMENT 외의 신호는 흘려보낸다. 바뀔 리 없는 파일을 다시 읽고 비교하는 비용은
     * 작지만, 로그에 "변경 없음"이 두 배로 쌓여 진짜 갱신이 묻힌다.
     *
     * <p>실패해도 검수는 성공으로 남는다({@code run}과 같은 판단). 커밋이 이미 끝난 뒤라
     * 되돌릴 것이 없고, 스냅샷은 부가 기능이라 이것 때문에 검수를 막으면 안 된다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReviewCompleted(ReviewCompleted event) {
        if (event.target() != ReviewCompleted.Target.DOCUMENT) {
            return;
        }
        try {
            export(Path.of(exportDir));
        } catch (Exception e) {
            log.warn("기존 문서 스냅샷 내보내기 실패(검수는 정상 처리됨): {}", e.getMessage());
        }
    }

    /** 디렉터리를 받아 스냅샷을 갱신한다. 실제로 파일을 썼으면 true. */
    boolean export(Path dir) throws Exception {
        // 정식 문서 + 아직 검수 안 된 초안. 순서를 고정해야 "내용이 같은데 파일이 바뀐" 것으로
        // 보이지 않는다(아래 hasChanged 참고).
        List<String> titles = new ArrayList<>(documentRepository.findAllTitles());
        titles.addAll(draftRepository.findPendingTitles());

        List<String> tags = tagRepository.findAll().stream()
                .map(Tag::getName)
                .sorted() // 태그는 DB 순서가 보장되지 않으므로 이름순으로 못 박는다
                .toList();

        // 거절된 문서 slug(2단계) — 배치가 이 문서로는 문제를 만들지 않게 한다
        List<String> rejectedSlugs = draftRepository.findRejectedSlugs();

        if (titles.isEmpty() && tags.isEmpty()) {
            // 아직 문서가 하나도 없다 — 빈 파일을 만들면 배치가 빈 블록을 프롬프트에 넣게 되고,
            // 커밋할 것도 없는데 새 파일이 생겨 혼란만 준다.
            log.debug("문서·태그가 없어 스냅샷을 만들지 않습니다.");
            return false;
        }

        Path file = dir.resolve(FILE_NAME);
        if (!hasChanged(file, titles, tags, rejectedSlugs)) {
            log.debug("기존 문서 스냅샷 변경 없음: 제목 {}건, 태그 {}건", titles.size(), tags.size());
            return false;
        }

        ExistingDocumentsFile snapshot = new ExistingDocumentsFile(
                "정식 document 테이블 + 검수 대기 초안의 제목·태그 스냅샷입니다. "
                        + "GitHub Actions 배치가 주제 중복 회피와 태그 재사용에 쓰고, "
                        + "rejectedSlugs는 '이 문서로는 문제를 만들지 마라'는 목록입니다"
                        + "(클라우드에는 DB가 없으므로). docs/15 참고. "
                        + "이 파일이 갱신되면 커밋해야 다음 배치부터 반영됩니다.",
                LocalDate.now().toString(), titles, tags, rejectedSlugs);

        Files.createDirectories(dir);
        Files.writeString(file, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot));

        // info로 남긴다 — 사용자가 "커밋해야 한다"는 것을 알아야 하는 유일한 순간이라
        // debug로 묻으면 기능이 조용히 무력해진다.
        log.info("기존 문서 스냅샷 갱신: {} (제목 {}건, 태그 {}건, 거절 {}건) — 커밋하면 다음 배치부터 반영됩니다",
                file, titles.size(), tags.size(), rejectedSlugs.size());
        return true;
    }

    /**
     * 기존 파일과 내용이 다른지 본다. 파일이 없으면 당연히 "바뀐 것".
     *
     * <p>{@code exportedAt}은 비교하지 않는다. 날짜까지 비교하면 내용이 그대로여도 날이 바뀔 때마다
     * 파일을 새로 쓰게 되고, 그러면 <b>앱을 켤 때마다 git이 변경으로 인식</b>해서 진짜 바뀐 날을
     * 알아볼 수 없게 된다({@link RejectionNotesExporter}에서 이미 겪은 문제).
     */
    private boolean hasChanged(Path file, List<String> titles, List<String> tags,
                               List<String> rejectedSlugs) {
        if (!Files.exists(file)) {
            return true;
        }
        try {
            ExistingDocumentsFile existing = objectMapper.readValue(file.toFile(), ExistingDocumentsFile.class);
            // rejectedSlugs는 2단계에서 추가된 필드라 옛 파일에는 없다(null).
            // 그 경우 "빈 목록과 다른가"로 비교되어, 거절이 하나라도 있으면 파일이 갱신된다.
            return !titles.equals(existing.titles())
                    || !tags.equals(existing.tags())
                    || !rejectedSlugs.equals(existing.rejectedSlugs() == null ? List.of() : existing.rejectedSlugs());
        } catch (Exception e) {
            // 파일이 깨졌거나 형식이 바뀐 경우 — 새로 쓰는 쪽이 안전하다
            log.debug("기존 스냅샷을 읽지 못해 새로 씁니다: {}", e.getMessage());
            return true;
        }
    }
}
