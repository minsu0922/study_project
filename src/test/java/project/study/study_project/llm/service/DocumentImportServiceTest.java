package project.study.study_project.llm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import project.study.study_project.admin.service.AdminDocumentService;
import project.study.study_project.global.common.Domain;
import project.study.study_project.llm.client.GeneratedDocumentItem;
import project.study.study_project.llm.domain.DraftStatus;
import project.study.study_project.llm.domain.GeneratedDocumentDraft;
import project.study.study_project.llm.domain.ImportedDraftFile;
import project.study.study_project.llm.dto.GeneratedDocumentFile;
import project.study.study_project.llm.repository.GeneratedDocumentDraftRepository;
import project.study.study_project.llm.repository.ImportedDraftFileRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 문서 생성 결과 파일 흡수 테스트 — docs/15.
 *
 * <p>{@code DraftImportServiceTest}(문제)와 같은 방침이다: 저장소만 가짜로 두고
 * {@link LlmDocumentService}는 <b>진짜를 넣는다</b>. 파일로 들어온 문서가 어떤 처리를 받는지가
 * 이 테스트의 관심사인데, 그 처리를 담당하는 서비스를 가짜로 바꾸면 아무것도 검증하지 못한다.
 *
 * <p>{@code @TempDir}로 실제 JSON 파일을 만들어 읽는 이유도 같다 — 필드 이름이 어긋나
 * 값이 조용히 {@code null}로 들어오는 사고는 객체를 직접 넘기는 테스트로는 잡히지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class DocumentImportServiceTest {

    @Mock
    private GeneratedDocumentDraftRepository draftRepository;
    @Mock
    private ImportedDraftFileRepository importedFileRepository;
    @Mock
    private AdminDocumentService adminDocumentService;

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private DocumentImportService service;

    @BeforeEach
    void setUp() {
        LlmDocumentService llmDocumentService =
                new LlmDocumentService(draftRepository, adminDocumentService, objectMapper);
        service = new DocumentImportService(llmDocumentService, importedFileRepository, objectMapper);

        // save는 받은 엔티티를 그대로 돌려준다 — 실제 JPA의 동작과 같게 흉내
        lenient().when(draftRepository.save(any()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("정상 파일을 읽으면 PENDING 초안 1건이 저장된다")
    void importsValidFile() throws Exception {
        Path file = writeDocumentFile("2026-08-12.json", "claude-opus-5",
                item("캐시 전략", "cache-strategy", List.of("cache", "performance")));

        int saved = service.importFile(file);

        assertThat(saved).isEqualTo(1);
        GeneratedDocumentDraft draft = capturedDraft();
        assertThat(draft.getTitle()).isEqualTo("캐시 전략");
        assertThat(draft.getSlug()).isEqualTo("cache-strategy");
        assertThat(draft.getDomain()).isEqualTo(Domain.SYSTEM_DESIGN);
        assertThat(draft.getStatus()).as("흡수된 초안은 항상 검수 대기 상태로 태어난다")
                .isEqualTo(DraftStatus.PENDING);
        assertThat(draft.getTagsJson()).isEqualTo("[\"cache\",\"performance\"]");
    }

    /**
     * <b>이 프로젝트에서 가장 조용한 실패가 될 뻔한 지점.</b> 문제 파일과 문서 파일은
     * 이름이 완전히 같다({@code 2026-08-12.json}). 흡수 이력의 열쇠가 파일명뿐이라면
     * 문제를 먼저 흡수한 순간 도장이 찍혀 <b>문서는 예외도 로그도 없이 건너뛰어진다</b>.
     */
    @Test
    @DisplayName("흡수 이력의 열쇠에 폴더가 붙는다 — 문제 파일과 이름이 같아서 안 붙이면 문서가 통째로 사라진다")
    void recordsHistoryKeyWithFolderPrefix() throws Exception {
        Path file = writeDocumentFile("2026-08-12.json", "claude-opus-5",
                item("캐시 전략", "cache-strategy", List.of("cache")));

        service.importFile(file);

        ArgumentCaptor<ImportedDraftFile> captor = ArgumentCaptor.forClass(ImportedDraftFile.class);
        verify(importedFileRepository).save(captor.capture());
        assertThat(captor.getValue().getFilename())
                .as("문제 파일의 열쇠(2026-08-12.json)와 반드시 달라야 한다")
                .isEqualTo("documents/2026-08-12.json");
    }

    @Test
    @DisplayName("열쇠 구분자는 OS와 무관하게 '/' — 윈도우에서 만든 이력을 리눅스에서 다시 읽어야 한다")
    void usesForwardSlashRegardlessOfOs() {
        assertThat(DocumentImportService.importKey("2026-08-12.json"))
                .isEqualTo("documents/2026-08-12.json")
                .doesNotContain("\\");
    }

    @Test
    @DisplayName("파일에 적힌 모델명이 초안에 그대로 기록된다 — 현재 설정값으로 덮으면 승인율 비교가 오염된다")
    void recordsModelFromFile() throws Exception {
        Path file = writeDocumentFile("2026-08-01.json", "claude-opus-4-8",
                item("가상 메모리", "virtual-memory", List.of("os")));

        service.importFile(file);

        assertThat(capturedDraft().getModel()).isEqualTo("claude-opus-4-8");
    }

    @Test
    @DisplayName("모델명이 비어 있으면 현재 설정값을 갖다 쓰지 않고 unknown으로 남긴다")
    void recordsUnknownWhenModelMissing() throws Exception {
        Path file = writeDocumentFile("2026-08-02.json", null,
                item("가상 메모리", "virtual-memory", List.of("os")));

        service.importFile(file);

        assertThat(capturedDraft().getModel()).isEqualTo("unknown");
    }

    /**
     * 문제 흡수와 <b>일부러 다르게</b> 만든 지점이다. 문제는 규약을 어긴 항목을 저장 단계에서
     * 버리지만(5개 중 1개니까), 문서는 하루 한 편이라 버리면 그날 결과가 0건이 된다.
     * 게다가 걸린 실물을 봐야 프롬프트를 어떻게 고칠지 알 수 있다.
     */
    @Test
    @DisplayName("검증에 걸리는 문서도 일단 흡수한다 — 버리면 그날 결과가 0건이고 고칠 단서도 사라진다")
    void importsEvenWhenValidationFails() throws Exception {
        // 필수 절이 하나도 없고 slug 형식도 틀린, 명백히 문제 있는 문서
        GeneratedDocumentItem broken = new GeneratedDocumentItem(
                "엉망 문서", "Bad_Slug", "# 엉망 문서\n\n아무 절도 없다.", List.of("x"));
        Path file = writeDocumentFile("2026-08-03.json", "claude-opus-5", broken);

        assertThat(service.importFile(file)).isEqualTo(1);
        verify(draftRepository).save(any());
        verify(importedFileRepository).save(any());
    }

    @Test
    @DisplayName("분야가 빠진 파일은 예외를 던지고 이력을 남기지 않는다 — 파일을 고치면 다음 부팅에 다시 시도된다")
    void rejectsFileWithMissingDomain() throws Exception {
        Path file = tempDir.resolve("2026-08-14.json");
        Files.writeString(file, """
                {
                  "date": "2026-08-14",
                  "model": "claude-opus-5",
                  "document": { "title": "제목", "slug": "slug", "contentMd": "본문", "tags": [] }
                }
                """);

        assertThatThrownBy(() -> service.importFile(file))
                .isInstanceOf(IllegalArgumentException.class);

        // 이력이 남으면 파일을 고쳐도 영영 다시 읽지 않는다 — 남기지 않는 것이 핵심
        verify(importedFileRepository, never()).save(any());
    }

    @Test
    @DisplayName("본문이 빈 파일은 예외로 막는다 — 조용히 빈 초안을 만들면 검수함만 지저분해진다")
    void rejectsEmptyContent() throws Exception {
        Path file = writeDocumentFile("2026-08-15.json", "claude-opus-5",
                new GeneratedDocumentItem("제목", "slug", "   ", List.of()));

        assertThatThrownBy(() -> service.importFile(file))
                .isInstanceOf(IllegalArgumentException.class);

        verify(importedFileRepository, never()).save(any());
    }

    /**
     * 컬럼 길이(200자)를 넘는 제목은 DB가 예외를 던지고, 그러면 그날 문서가 통째로 날아간다.
     * 잘라서라도 대기함에 올리는 편이 낫다는 판단({@code LlmDocumentService.truncate} 주석).
     */
    @Test
    @DisplayName("제목이 컬럼 길이를 넘으면 잘라서 저장한다 — 통째로 버리는 것보다 낫다")
    void truncatesOverlongTitle() throws Exception {
        String longTitle = "가".repeat(250);
        Path file = writeDocumentFile("2026-08-16.json", "claude-opus-5",
                new GeneratedDocumentItem(longTitle, "long-title", "# 제목\n\n본문", List.of()));

        service.importFile(file);

        assertThat(capturedDraft().getTitle()).hasSize(200);
    }

    /* ── 도우미 ──────────────────────────────────────────────── */

    private GeneratedDocumentDraft capturedDraft() {
        ArgumentCaptor<GeneratedDocumentDraft> captor = ArgumentCaptor.forClass(GeneratedDocumentDraft.class);
        verify(draftRepository).save(captor.capture());
        return captor.getValue();
    }

    private GeneratedDocumentItem item(String title, String slug, List<String> tags) {
        String content = """
                # %s

                ## 왜 필요한가
                내용.

                ## 면접에서 이렇게 물어본다
                **Q. 무엇인가?**
                답변 요점.

                ## 면접 한 줄 요약
                한 줄.
                """.formatted(title);
        return new GeneratedDocumentItem(title, slug, content, tags);
    }

    /** 실제 배치가 쓰는 것과 같은 형식으로 파일을 만든다(직렬화 경로까지 테스트에 포함). */
    private Path writeDocumentFile(String filename, String model, GeneratedDocumentItem document)
            throws Exception {
        GeneratedDocumentFile file = new GeneratedDocumentFile(
                "테스트용", filename.replace(".json", ""), "2026-08-12T21:17:00Z",
                Domain.SYSTEM_DESIGN, model, document);
        Path path = tempDir.resolve(filename);
        objectMapper.writeValue(path.toFile(), file);
        return path;
    }
}
