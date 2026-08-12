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
import project.study.study_project.admin.service.AdminProblemService;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.llm.client.GeneratedProblemItem;
import project.study.study_project.llm.client.ProblemGenerator;
import project.study.study_project.llm.domain.GeneratedProblemDraft;
import project.study.study_project.llm.domain.ImportedDraftFile;
import project.study.study_project.llm.dto.GeneratedBatchFile;
import project.study.study_project.llm.repository.GeneratedProblemDraftRepository;
import project.study.study_project.llm.repository.ImportedDraftFileRepository;
import project.study.study_project.quiz.repository.ProblemRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 생성 결과 파일 흡수 테스트 — docs/14.
 *
 * <p><b>가짜 LlmProblemService를 쓰지 않고 진짜를 넣은 이유</b>가 이 테스트의 핵심이다.
 * 이 설계의 가장 중요한 약속은 "GitHub Actions가 만든 문제도 관리자 버튼으로 만든 문제와
 * <b>완전히 같은 규약 검증</b>을 통과한다"인데, 검증을 담당하는 LlmProblemService를 가짜로
 * 바꿔 버리면 정작 그 약속을 검증하지 못한다. 저장소만 가짜로 두고 검증 로직은 실물을 태운다.
 *
 * <p>{@code @TempDir}로 임시 파일을 만드는 이유: 실제 JSON 직렬화·역직렬화까지 통과시켜야
 * "필드 이름이 어긋나 값이 조용히 null로 들어오는" 종류의 사고를 잡을 수 있다.
 * 객체를 직접 넘기는 테스트는 그 부분을 건너뛴다.
 */
@ExtendWith(MockitoExtension.class)
class DraftImportServiceTest {

    @Mock
    private GeneratedProblemDraftRepository draftRepository;
    @Mock
    private ImportedDraftFileRepository importedFileRepository;
    @Mock
    private ProblemGenerator problemGenerator;
    @Mock
    private ProblemRepository problemRepository;
    @Mock
    private AdminProblemService adminProblemService;

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private DraftImportService service;

    @BeforeEach
    void setUp() {
        LlmProblemService llmProblemService = new LlmProblemService(
                problemGenerator, draftRepository, problemRepository, adminProblemService,
                objectMapper, "claude-opus-5", List.of(Domain.NETWORK));
        service = new DraftImportService(llmProblemService, importedFileRepository, objectMapper);

        // saveAll은 받은 목록을 그대로 돌려준다 — 실제 JPA의 동작과 같게 흉내
        lenient().when(draftRepository.saveAll(any()))
                .thenAnswer(inv -> new ArrayList<GeneratedProblemDraft>(inv.getArgument(0)));
    }

    @Test
    @DisplayName("정상 파일을 읽으면 문제 수만큼 초안이 저장되고 흡수 이력이 남는다")
    void importsValidFile() throws Exception {
        Path file = writeBatchFile("2026-08-12.json", "claude-opus-5",
                validItem("TCP 3-way handshake의 순서로 옳은 것은?"),
                validItem("TIME_WAIT 상태가 존재하는 이유로 옳은 것은?"));

        int saved = service.importFile(file);

        assertThat(saved).isEqualTo(2);

        ArgumentCaptor<ImportedDraftFile> captor = ArgumentCaptor.forClass(ImportedDraftFile.class);
        verify(importedFileRepository).save(captor.capture());
        assertThat(captor.getValue().getFilename()).isEqualTo("2026-08-12.json");
        assertThat(captor.getValue().getDraftCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("파일에 적힌 모델명이 초안에 그대로 기록된다 — 현재 설정값으로 덮어쓰면 승인율 비교가 오염된다")
    void recordsModelFromFileNotCurrentSetting() throws Exception {
        // 서비스의 현재 설정은 claude-opus-5지만, 파일은 옛 모델로 생성된 것
        Path file = writeBatchFile("2026-08-01.json", "claude-opus-4-8",
                validItem("가상 메모리의 페이지 폴트가 발생하는 시점은?"));

        service.importFile(file);

        assertThat(capturedDrafts()).singleElement()
                .extracting(GeneratedProblemDraft::getModel)
                .isEqualTo("claude-opus-4-8");
    }

    @Test
    @DisplayName("모델명이 비어 있으면 현재 설정값을 갖다 쓰지 않고 unknown으로 남긴다")
    void recordsUnknownWhenModelMissing() throws Exception {
        Path file = writeBatchFile("2026-08-02.json", null,
                validItem("인덱스가 있는데도 풀 스캔이 도는 경우로 옳은 것은?"));

        service.importFile(file);

        assertThat(capturedDrafts()).singleElement()
                .extracting(GeneratedProblemDraft::getModel)
                .isEqualTo("unknown");
    }

    @Test
    @DisplayName("규약을 어긴 문제(정답 2개)는 건너뛰고 나머지만 저장한다 — 파일도 관리자 버튼과 같은 검증을 탄다")
    void skipsItemsViolatingContract() throws Exception {
        GeneratedProblemItem twoCorrect = new GeneratedProblemItem(
                "정답이 두 개인 잘못된 문제", "", "해설",
                List.of(new GeneratedProblemItem.GeneratedChoice("보기1", true),
                        new GeneratedProblemItem.GeneratedChoice("보기2", true),
                        new GeneratedProblemItem.GeneratedChoice("보기3", false),
                        new GeneratedProblemItem.GeneratedChoice("보기4", false)));

        Path file = writeBatchFile("2026-08-13.json", "claude-opus-5",
                validItem("정상 문제 1"), twoCorrect, validItem("정상 문제 2"));

        int saved = service.importFile(file);

        assertThat(saved).as("3개 중 규약 위반 1개는 제외").isEqualTo(2);
        // 걸러진 파일도 "처리 완료"로 기록한다 — 매 부팅마다 다시 읽고 다시 버리지 않기 위해(V7 주석)
        verify(importedFileRepository).save(any());
    }

    /**
     * 2단계: 4일 주기의 문제일에는 파일에 근거 문서 slug가 실려 온다. 그 값이 초안까지 닿지 않으면
     * 검수 화면에 "근거 문서" 링크가 안 뜨고, 승인해도 문제에 연결이 남지 않는다 —
     * 값이 조용히 사라지는 종류라 파일→초안 구간을 테스트로 못 박는다.
     */
    @Test
    @DisplayName("파일의 근거 문서 slug가 초안까지 그대로 전달된다")
    void carriesDocumentSlugFromFileToDraft() throws Exception {
        Path file = writeBatchFile("2026-08-13.json", "claude-opus-5", "xss-and-csrf",
                validItem("CSRF 토큰을 넣었는데도 계정이 탈취된 원인으로 옳은 것은?"));

        service.importFile(file);

        assertThat(capturedDrafts()).singleElement()
                .extracting(GeneratedProblemDraft::getDocumentSlug)
                .isEqualTo("xss-and-csrf");
    }

    @Test
    @DisplayName("근거 문서 없이 만든 파일은 slug가 비어 있다 — 옛 파일도 그대로 흡수돼야 한다")
    void allowsMissingDocumentSlug() throws Exception {
        Path file = writeBatchFile("2026-08-14.json", "claude-opus-5",
                validItem("가상 메모리의 페이지 폴트가 발생하는 시점은?"));

        service.importFile(file);

        assertThat(capturedDrafts()).singleElement()
                .extracting(GeneratedProblemDraft::getDocumentSlug)
                .isNull();
    }

    @Test
    @DisplayName("분야가 빠진 파일은 예외를 던지고 이력을 남기지 않는다 — 파일을 고치면 다음 부팅에 다시 시도된다")
    void rejectsFileWithMissingDomain() throws Exception {
        Path file = tempDir.resolve("2026-08-14.json");
        Files.writeString(file, """
                {
                  "date": "2026-08-14",
                  "difficulty": "BEGINNER",
                  "type": "MULTIPLE_CHOICE",
                  "model": "claude-opus-5",
                  "problems": []
                }
                """);

        assertThatThrownBy(() -> service.importFile(file))
                .isInstanceOf(IllegalArgumentException.class);

        // 이력이 남으면 파일을 고쳐도 영영 다시 읽지 않는다 — 남기지 않는 것이 핵심
        verify(importedFileRepository, never()).save(any());
    }

    @Test
    @DisplayName("문제 목록이 빈 파일은 예외로 막는다 — 조용히 0건 성공으로 넘기면 사고를 놓친다")
    void rejectsEmptyProblemList() throws Exception {
        Path file = writeBatchFile("2026-08-15.json", "claude-opus-5");

        assertThatThrownBy(() -> service.importFile(file))
                .isInstanceOf(IllegalArgumentException.class);

        verify(importedFileRepository, never()).save(any());
    }

    /* ── 도우미 ──────────────────────────────────────────────── */

    /** 저장된 초안 목록을 잡아낸다(saveAll 인자). */
    @SuppressWarnings("unchecked")
    private List<GeneratedProblemDraft> capturedDrafts() {
        ArgumentCaptor<List<GeneratedProblemDraft>> captor = ArgumentCaptor.forClass(List.class);
        verify(draftRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    /** 규약을 지키는 객관식 문제 하나(보기 4개, 정답 1개). */
    private GeneratedProblemItem validItem(String question) {
        return new GeneratedProblemItem(question, "", "해설: 왜 정답인지와 오답의 오해를 설명한다.",
                List.of(new GeneratedProblemItem.GeneratedChoice("정답 보기", true),
                        new GeneratedProblemItem.GeneratedChoice("오답 보기 1", false),
                        new GeneratedProblemItem.GeneratedChoice("오답 보기 2", false),
                        new GeneratedProblemItem.GeneratedChoice("오답 보기 3", false)));
    }

    /** 근거 문서 없이 만든 파일 — 지금까지의 대부분이 이 형태다. */
    private Path writeBatchFile(String filename, String model, GeneratedProblemItem... items) throws Exception {
        return writeBatchFile(filename, model, null, items);
    }

    /** 실제 워크플로가 쓰는 것과 같은 형식으로 파일을 만든다(직렬화 경로까지 테스트에 포함). */
    private Path writeBatchFile(String filename, String model, String documentSlug,
                                GeneratedProblemItem... items) throws Exception {
        GeneratedBatchFile batch = new GeneratedBatchFile(
                "테스트용", filename.replace(".json", ""), "2026-08-12T21:17:00Z",
                Domain.NETWORK, Difficulty.BEGINNER, ProblemType.MULTIPLE_CHOICE, model,
                documentSlug, List.of(items));
        Path file = tempDir.resolve(filename);
        objectMapper.writeValue(file.toFile(), batch);
        return file;
    }
}
