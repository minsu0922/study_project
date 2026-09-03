package project.study.study_project.llm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.llm.client.ClaudeDocumentGenerator;
import project.study.study_project.llm.client.GeneratedDocumentItem;
import project.study.study_project.llm.domain.GeneratedDocumentDraft;
import project.study.study_project.llm.domain.ImportedDraftFile;
import project.study.study_project.llm.dto.GeneratedDocumentFile;
import project.study.study_project.llm.repository.ImportedDraftFileRepository;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 문서 생성 결과 파일 한 개를 검수 대기 초안으로 흡수한다 — docs/15.
 *
 * <p>{@link DraftImportService}(문제)의 문서판이다. 구조와 판단이 같다: <b>파일 하나가 곧
 * 트랜잭션 하나</b>이고(초안 저장과 흡수 도장이 갈라지면 중복되거나 증발한다),
 * 커밋된 JSON은 사람이 손댈 수 있으므로 신뢰하지 않고 {@link LlmDocumentService#saveDraft}라는
 * 같은 입구를 통과시킨다.
 *
 * <p><b>2026-09-03부터 파일 하나에 문서가 둘</b>이다(입문편·심화편). 전에는 "문서는 나눌 대상이
 * 없다"는 이유로 부분 성공 개념을 두지 않았는데, 이제는 둘 중 하나만 있는 파일이 정상적으로
 * 존재한다 — 옛 파일 15개에는 심화편 칸이 없고, 심화편 생성만 실패한 날도 생길 수 있다.
 * 그래서 반환값이 "몇 편을 저장했는가"(1 또는 2)가 됐다.
 *
 * <p><b>두 편을 한 트랜잭션에서 저장한다.</b> 나누면 입문편만 들어오고 심화편은 없는 상태로
 * 흡수 도장이 찍히는 경로가 생기는데, 도장이 파일 단위라 <b>다시 시도할 방법이 없다</b>.
 * 파일 하나가 곧 트랜잭션 하나라는 이 클래스의 원칙이 여기서도 그대로 맞는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentImportService {

    /**
     * 문서 파일이 놓이는 하위 디렉터리 — {@code DraftGeneratorCli.DOCUMENT_SUBDIR}과 같아야 한다.
     * (CLI는 스프링 밖에서 도는 독립 실행 클래스라 상수를 공유할 수 없어 값이 두 곳에 있다.
     * 어긋나면 생성은 되는데 흡수가 안 되므로, 아래 흡수 테스트가 실제 경로로 이를 지킨다.)
     */
    static final String DOCUMENT_SUBDIR = "documents";

    private final LlmDocumentService llmDocumentService;
    private final ImportedDraftFileRepository importedFileRepository;
    private final ObjectMapper objectMapper;

    /**
     * 흡수 이력의 열쇠 — <b>폴더 이름을 반드시 포함한다</b>.
     *
     * <p><b>이게 없으면 문서가 조용히 사라진다.</b> 문제 파일과 문서 파일은 이름이 똑같다:
     * <pre>
     *   generated/2026-08-12.json            ← 문제
     *   generated/documents/2026-08-12.json  ← 문서
     * </pre>
     * {@code imported_draft_file}의 PK는 파일명 하나뿐이라, 파일명만 열쇠로 쓰면 문제 파일을
     * 먼저 흡수한 순간 도장이 찍히고 <b>문서 파일은 "이미 처리함"으로 건너뛰어진다</b>.
     * 예외도 로그도 없이 그냥 안 들어온다 — 이 프로젝트가 경계하는 "조용한 실패"의 전형이다.
     *
     * <p>구분자를 {@code /}로 못 박은 이유: {@code Path}를 그대로 문자열로 만들면 윈도우에서는
     * {@code documents\2026-08-12.json}, 리눅스(CI)에서는 {@code documents/...}가 되어
     * <b>같은 파일이 OS마다 다른 열쇠</b>가 된다. 그러면 DB를 옮겼을 때 전부 다시 흡수된다.
     */
    public static String importKey(String filename) {
        return DOCUMENT_SUBDIR + "/" + filename;
    }

    /**
     * 파일 하나를 읽어 <b>그 안의 모든 편</b>을 초안으로 저장하고 흡수 이력을 남긴다.
     *
     * @return 저장된 초안 수(1 또는 2) — 입문편만 있으면 1, 심화편까지 있으면 2
     * @throws IOException              파일이 깨졌거나 형식이 다를 때 — 호출자가 그 파일만 건너뛴다
     * @throws IllegalArgumentException 분야나 입문편 본문이 비었을 때
     */
    @Transactional
    public int importFile(Path file) throws IOException {
        GeneratedDocumentFile parsed = objectMapper.readValue(file.toFile(), GeneratedDocumentFile.class);

        // 손으로 편집됐거나 형식이 바뀐 파일을 조용히 통과시키지 않는다.
        // 여기서 예외를 던지면 이력이 남지 않아, 파일을 고치면 다음 부팅에 다시 시도된다(의도된 동작).
        GeneratedDocumentItem document = parsed.document();
        if (parsed.domain() == null) {
            throw new IllegalArgumentException("domain이 없습니다: " + file.getFileName());
        }
        if (document == null || document.contentMd() == null || document.contentMd().isBlank()) {
            throw new IllegalArgumentException("문서 본문이 비어 있습니다: " + file.getFileName());
        }

        // 생성 당시의 모델을 그대로 기록한다 — 현재 설정값을 쓰면 모델 교체 후 흡수한 옛 파일이
        // 새 모델 이름으로 둔갑해 승인율 비교가 오염된다. 값이 없는 파일은 정직하게 unknown.
        String model = (parsed.model() == null || parsed.model().isBlank()) ? "unknown" : parsed.model();

        String key = importKey(file.getFileName().toString());
        int saved = saveOne(parsed, document, model, key);

        // 심화편은 없을 수 있다 — 옛 파일과 심화편 생성만 실패한 날. 그건 결함이 아니라
        // 정상 경로이므로 예외를 던지지 않는다(GeneratedDocumentFile.advancedDocument 주석).
        GeneratedDocumentItem advanced = parsed.advancedDocument();
        if (advanced != null && advanced.contentMd() != null && !advanced.contentMd().isBlank()) {
            saved += saveOne(parsed, advanced, model, key);
        }

        importedFileRepository.save(ImportedDraftFile.of(key, saved));
        return saved;
    }

    /** 한 편을 초안으로 저장하고 로그를 남긴다 — 두 편의 처리가 갈리지 않도록 한 곳에 둔다. */
    private int saveOne(GeneratedDocumentFile parsed, GeneratedDocumentItem item,
                        String model, String key) {
        GeneratedDocumentDraft draft = llmDocumentService.saveDraft(parsed.domain(), item, model);
        log.info("문서 초안 흡수: {} — \"{}\" ({}, {}, 본문 {}자)",
                key, draft.getTitle(), parsed.domain(),
                ClaudeDocumentGenerator.editionOf(draft.getContentMd()).getDisplayName(),
                draft.getContentMd().length());
        return 1;
    }
}
