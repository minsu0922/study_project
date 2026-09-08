package project.study.study_project.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.admin.dto.AdminBatchStatus;
import project.study.study_project.admin.service.AdminBatchService;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.llm.client.GeneratedDocumentItem;
import project.study.study_project.llm.domain.GeneratedProblemDraft;
import project.study.study_project.llm.domain.ImportedDraftFile;
import project.study.study_project.llm.dto.GeneratedDocumentFile;
import project.study.study_project.llm.repository.GeneratedProblemDraftRepository;
import project.study.study_project.llm.repository.ImportedDraftFileRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배치 현황이 <b>파일과 DB를 맞춰 보는</b> 부분을 지킨다 — 2026-09-01 신설.
 *
 * <p>이 화면의 값은 계산이 아니라 <b>대조</b>에 있다. 주기 계산은 이미
 * {@code GenerationScheduleTest}가 지키고 있고, 여기서 틀리면 조용히 틀리는 것은 셋이다:
 *
 * <ul>
 *   <li>스냅샷 보조 파일({@code _}로 시작)을 "안 들어온 파일"로 세는 것 —
 *       그러면 목록이 <b>늘</b> 빨갛고, 늘 켜진 경보는 없는 것과 같다.
 *   <li>문서 파일의 이력 열쇠에 {@code documents/} 접두를 빠뜨리는 것 —
 *       들어온 문서가 매번 "안 들어옴"으로 뜬다.
 *   <li>지나간 날짜를 "막힌 주기"로 세는 것 — 이미 돈 날에 파일이 있는 것은 정상인데,
 *       섞이면 앞으로 죽을 날짜가 목록에 묻힌다.
 * </ul>
 *
 * <p>임시 폴더를 {@code llm.import.dir}로 물려 실제 파일을 놓고 본다. 폴더를 흉내 내는 대신
 * 진짜 파일을 쓰는 이유: 이 서비스가 하는 일의 절반이 <b>파일 이름을 읽는 것</b>이라,
 * 이름 규칙을 모형으로 바꾸면 정작 지켜야 할 것이 검사되지 않는다.
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties = "llm.import.dir=build/test-batch-status")
class AdminBatchStatusIntegrationTest {

    private static final Path DIR = Path.of("build/test-batch-status");

    @Autowired
    private AdminBatchService adminBatchService;

    @Autowired
    private ImportedDraftFileRepository importedDraftFileRepository;

    @Autowired
    private GeneratedProblemDraftRepository generatedProblemDraftRepository;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 매 테스트마다 폴더를 비운다.
     *
     * <p>{@code @Transactional}은 DB만 되돌린다 — 파일은 그대로 남는다. 남겨 두면 앞 테스트가
     * 놓은 파일이 뒤 테스트의 "안 들어온 파일"·"막힌 주기" 목록에 섞여, <b>혼자 돌리면 통과하고
     * 다 같이 돌리면 실패하는</b> 종류의 테스트가 된다. 그 실패는 원인을 찾는 데만 한나절 든다.
     */
    @org.junit.jupiter.api.BeforeEach
    void clearDir() throws Exception {
        if (!Files.isDirectory(DIR)) {
            return;
        }
        try (var paths = Files.walk(DIR)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (Exception ignored) {
                    // 지우지 못한 파일이 있어도 테스트를 멈추지 않는다 — 아래 단언이 대신 말해 준다.
                }
            });
        }
    }

    @Test
    @DisplayName("들어온 파일은 빼고, 안 들어온 것만 목록에 남는다")
    void listsOnlyWaitingFiles() throws Exception {
        Files.createDirectories(DIR);
        write("2026-01-01.json", "{}");
        write("2026-01-02.json", "{}");
        importedDraftFileRepository.save(ImportedDraftFile.of("2026-01-01.json", 5));

        AdminBatchStatus status = adminBatchService.getStatus();

        assertThat(status.waitingFiles()).contains("2026-01-02.json");
        assertThat(status.waitingFiles()).doesNotContain("2026-01-01.json");
    }

    @Test
    @DisplayName("_로 시작하는 스냅샷 파일은 세지 않는다 — 이력에 영영 안 남아 늘 경보가 된다")
    void ignoresSnapshotFiles() throws Exception {
        Files.createDirectories(DIR);
        write("_existing-questions.json", "{}");
        write("_rejection-notes.json", "{}");

        AdminBatchStatus status = adminBatchService.getStatus();

        assertThat(status.waitingFiles()).noneMatch(f -> f.contains("_existing"));
        assertThat(status.waitingFiles()).noneMatch(f -> f.contains("_rejection"));
    }

    @Test
    @DisplayName("문서 파일의 이력 열쇠에는 documents/ 접두가 붙는다 — 빠뜨리면 늘 '안 들어옴'으로 뜬다")
    void usesDocumentPrefixForHistory() throws Exception {
        Files.createDirectories(DIR.resolve("documents"));
        writeDocument("2026-01-03.json", "probe-doc");
        importedDraftFileRepository.save(ImportedDraftFile.of("documents/2026-01-03.json", 1));

        AdminBatchStatus status = adminBatchService.getStatus();

        assertThat(status.waitingFiles()).doesNotContain("documents/2026-01-03.json");
    }

    @Test
    @DisplayName("앞으로의 날짜만 '막힌 주기'다 — 지나간 날에 파일이 있는 것은 정상이다")
    void blocksOnlyFutureDates() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        Files.createDirectories(DIR);
        write(today.plusDays(10) + ".json", "{}");
        write(today.minusDays(10) + ".json", "{}");

        AdminBatchStatus status = adminBatchService.getStatus();

        assertThat(status.blockedDates()).extracting(AdminBatchStatus.BlockedDate::date)
                .contains(today.plusDays(10))
                .doesNotContain(today.minusDays(10));
    }

    @Test
    @DisplayName("접미사가 붙은 파일은 막지 않는다 — 접미사는 예약 실행과 겹치지 않으려고 붙이는 것이다")
    void suffixedFilesDoNotBlock() throws Exception {
        LocalDate future = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(20);
        Files.createDirectories(DIR);
        write(future + "-hand-filled.json", "{}");

        AdminBatchStatus status = adminBatchService.getStatus();

        assertThat(status.blockedDates()).extracting(AdminBatchStatus.BlockedDate::date)
                .doesNotContain(future);
    }

    @Test
    @DisplayName("근거 문서가 없으면 slug가 비고, 그것이 곧 '오늘은 폴백'이라는 신호다")
    void reportsMissingSourceDocument() throws Exception {
        Files.createDirectories(DIR.resolve("documents"));

        AdminBatchStatus status = adminBatchService.getStatus();

        // 오늘 주기의 문서 파일을 만들지 않았으므로 비어 있어야 한다.
        assertThat(status.plan().documentSlug()).isNull();
        assertThat(status.plan().documentDate()).isNotNull();
        assertThat(status.plan().dayInCycle()).isBetween(0, 3);
    }

    @Test
    @DisplayName("근거 문서가 있으면 분야는 문서 쪽이 이긴다 — 배치가 그렇게 하므로 화면도 같아야 한다")
    void documentDomainWinsOverCycleDomain() throws Exception {
        // 오늘 주기가 근거로 삼을 날짜에 <네트워크> 문서를 놓는다. 주기가 어느 분야를 고르든
        // 배치는 문서 쪽으로 맞추므로(DraftGeneratorCli.alignDomainWithDocument),
        // 화면의 "나올 분야"도 네트워크여야 한다.
        Files.createDirectories(DIR.resolve("documents"));
        LocalDate documentDate = adminBatchService.getStatus().plan().documentDate();
        writeDocumentAt(documentDate, "aligned-probe");

        AdminBatchStatus.TodayPlan plan = adminBatchService.getStatus().plan();

        assertThat(plan.documentSlug()).isEqualTo("aligned-probe");
        assertThat(plan.domain()).isEqualTo(Domain.NETWORK);
        // 주기가 고른 분야는 그대로 남아 있어야 한다 — 화면이 "무엇에서 무엇으로 바뀌었는지"를
        // 말해 주려면 둘 다 필요하다. 덮어써 버리면 어긋남이 있었다는 사실 자체가 사라진다.
        assertThat(plan.cycleDomain()).isNotNull();
    }

    /* ── 달력(2026-09-08) ──────────────────────────────────────────────
     *
     * 달력이 답하는 것은 <없는 날짜>다. "안 들어온 파일"·"막힌 주기" 표는 줄이 있는 것만
     * 보여 주므로, 그날 몫이 아예 안 나온 것은 어느 표에도 안 나타났다. 그래서 여기서
     * 지켜야 할 것은 개수가 아니라 <b>상태 판정 네 갈래</b>다. */

    @Test
    @DisplayName("지난 날인데 파일이 없으면 '안 나옴' — 표에는 줄이 안 생기던 바로 그 날이다")
    void calendarMarksMissingPastDays() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));

        AdminBatchStatus.DayCell cell = cellOf(adminBatchService.getStatus(), today.minusDays(2));

        assertThat(cell.state()).isEqualTo(AdminBatchStatus.DayState.MISSING);
        assertThat(cell.draftCount()).isNull();
    }

    @Test
    @DisplayName("파일이 있으면 들여왔는지에 따라 '들어옴'과 '대기'가 갈린다")
    void calendarSplitsImportedAndWaiting() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        LocalDate target = today.minusDays(2);
        // 파일 이름 규칙(문서일이면 documents/ 접두)은 서버가 이미 계산해 두었으므로
        // 테스트가 다시 짐작하지 않는다 — 짐작하면 규칙이 갈라져도 테스트가 통과한다.
        String filename = cellOf(adminBatchService.getStatus(), target).filename();
        // 이 테스트는 <오늘 기준> 날짜를 쓰므로 개발 PC의 실제 이력과 같은 이름을 만질 수 있다.
        // 그 이력이 남아 있으면 파일을 놓자마자 '들어옴'이 되어 첫 단언이 깨진다.
        // (@Transactional이 이 삭제도 되돌리므로 실제 DB는 그대로다.)
        importedDraftFileRepository.deleteById(filename);

        writeByName(filename);
        assertThat(cellOf(adminBatchService.getStatus(), target).state())
                .isEqualTo(AdminBatchStatus.DayState.WAITING);

        importedDraftFileRepository.save(ImportedDraftFile.of(filename, 4));
        AdminBatchStatus.DayCell imported = cellOf(adminBatchService.getStatus(), target);
        assertThat(imported.state()).isEqualTo(AdminBatchStatus.DayState.IMPORTED);
        assertThat(imported.draftCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("앞으로의 날에 파일이 있으면 '미리 만들어 둠'이다 — 지난 날의 '대기'와 뜻이 정반대다")
    void calendarMarksFutureFilesAsPreset() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        LocalDate target = today.plusDays(1);
        String filename = cellOf(adminBatchService.getStatus(), target).filename();

        writeByName(filename);

        assertThat(cellOf(adminBatchService.getStatus(), target).state())
                .isEqualTo(AdminBatchStatus.DayState.PRESET);
    }

    @Test
    @DisplayName("달력은 주기 경계에서 시작해 4칸씩 맞아떨어진다 — 한 줄이 곧 한 주기여야 읽힌다")
    void calendarIsAlignedToCycles() {
        List<AdminBatchStatus.DayCell> calendar = adminBatchService.getStatus().calendar();

        assertThat(calendar.size() % 4).isZero();
        // 4칸마다 문서일(0일차)로 시작해야 화면의 "문서·초급·중급·고급" 머리줄과 맞는다.
        for (int i = 0; i < calendar.size(); i += 4) {
            assertThat(calendar.get(i).dayInCycle()).isZero();
            assertThat(calendar.get(i).documentDay()).isTrue();
            // 한 주기의 나흘은 같은 분야다 — 화면이 줄 이름으로 분야를 한 번만 적는 근거.
            assertThat(calendar.subList(i, i + 4))
                    .extracting(AdminBatchStatus.DayCell::domain)
                    .containsOnly(calendar.get(i).domain());
        }
    }

    @Test
    @DisplayName("수확 집계는 만든 초안을 승인·거절·대기로 가른다 — '몇 건 들어왔나'로는 알 수 없던 것")
    void harvestCountsDraftsByStatus() {
        AdminBatchStatus.Harvest before = adminBatchService.getStatus().harvest();

        generatedProblemDraftRepository.save(GeneratedProblemDraft.pending(
                Domain.NETWORK, Difficulty.BEGINNER, ProblemType.MULTIPLE_CHOICE,
                "제목", "질문?", "1", "해설", "[]", "test-model", null, null, null));

        AdminBatchStatus.Harvest after = adminBatchService.getStatus().harvest();

        assertThat(after.generated()).isEqualTo(before.generated() + 1);
        assertThat(after.pending()).isEqualTo(before.pending() + 1);
        // 합이 맞아야 한다 — 화면이 이 셋으로 막대를 그리므로 어긋나면 막대가 100%를 넘는다.
        assertThat(after.generated()).isEqualTo(after.approved() + after.rejected() + after.pending());
    }

    /** 달력에서 그 날짜의 칸을 꺼낸다. 없으면 창(24일)이 잘못 잡힌 것이므로 단언으로 알린다. */
    private AdminBatchStatus.DayCell cellOf(AdminBatchStatus status, LocalDate date) {
        return status.calendar().stream()
                .filter(c -> c.date().equals(date))
                .findFirst()
                .orElseThrow(() -> new AssertionError("달력에 " + date + " 칸이 없다 — 창이 잘못 잡혔다"));
    }

    /** {@code documents/2026-01-01.json}처럼 접두가 붙은 이름도 그대로 받아 만든다. */
    private void writeByName(String filename) throws Exception {
        Path file = DIR.resolve(filename);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{}");
    }

    private void writeDocumentAt(LocalDate date, String slug) throws Exception {
        var file = new GeneratedDocumentFile("테스트", date.toString(), date + "T00:00:00Z",
                Domain.NETWORK, "test", new GeneratedDocumentItem("제목", slug, "# 본문", List.of("net")), null);
        objectMapper.writeValue(DIR.resolve("documents").resolve(date + ".json").toFile(), file);
    }

    private void write(String name, String body) throws Exception {
        Files.writeString(DIR.resolve(name), body);
    }

    private void writeDocument(String name, String slug) throws Exception {
        var file = new GeneratedDocumentFile("테스트", "2026-01-03", "2026-01-03T00:00:00Z",
                Domain.NETWORK, "test", new GeneratedDocumentItem("제목", slug, "# 본문", List.of("net")), null);
        objectMapper.writeValue(DIR.resolve("documents").resolve(name).toFile(), file);
    }
}
