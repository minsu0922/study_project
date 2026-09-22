package project.study.study_project.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.TestDomains;
import project.study.study_project.admin.dto.AdminBatchStatus;
import project.study.study_project.admin.dto.AdminDomainSettingRequest;
import project.study.study_project.llm.service.DomainSettingService;
import project.study.study_project.admin.service.AdminBatchService;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.DomainCode;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.llm.client.GeneratedDocumentItem;
import project.study.study_project.llm.domain.GeneratedProblemDraft;
import project.study.study_project.llm.domain.ImportedDraftFile;
import project.study.study_project.llm.dto.GeneratedDocumentFile;
import project.study.study_project.llm.support.DocumentEditionRule;
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
// 난이도별 개수를 여기서 못 박는다(2026-09-19). 수확률 테스트가 "요청 3"을 기대하는데,
// application.yml 값을 따라가게 두면 설정을 바꾸는 순간 무관한 이 테스트가 깨진다.
@TestPropertySource(properties = {
        "llm.import.dir=build/test-batch-status",
        "llm.generation.batch-count-by-difficulty=BEGINNER=7,INTERMEDIATE=5,ADVANCED=3"})
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

    @Autowired
    private DomainSettingService domainSettingService;

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
        assertThat(plan.domain()).isEqualTo(TestDomains.NETWORK);
        // 주기가 고른 분야는 그대로 남아 있어야 한다 — 화면이 "무엇에서 무엇으로 바뀌었는지"를
        // 말해 주려면 둘 다 필요하다. 덮어써 버리면 어긋남이 있었다는 사실 자체가 사라진다.
        assertThat(plan.cycleDomain()).isNotNull();
    }

    /**
     * <b>다음 문서일이 주기와 맞는지</b>(2026-09-14 신설).
     *
     * <p>대기열의 "다음 차례" 배지에 붙는 날짜다. 4일 주기라 "맨 위로 올렸는데 언제 나오지"가
     * 매번 손계산이었는데, 그 계산을 화면이 하게 됐으니 <b>틀리면 사람이 그대로 믿는다</b>.
     *
     * <p>여기서도 날짜를 글자로 박지 않는다. 오늘이 주기의 며칠차인지는 실행하는 날마다
     * 달라서, 지켜야 할 것은 특정 날짜가 아니라 <b>주기와의 관계</b>다.
     */
    @Test
    @DisplayName("다음 문서일은 이번 주기의 다음 0일차 — 오늘이 문서일이면 오늘이다")
    void reportsNextDocumentDate() {
        AdminBatchStatus status = adminBatchService.getStatus();
        AdminBatchStatus.TodayPlan plan = status.plan();

        LocalDate expected = plan.documentDay()
                ? status.today()
                : plan.documentDate().plusDays(4);

        assertThat(status.nextDocumentDate()).isEqualTo(expected);
        assertThat(status.nextDocumentDate())
                .as("지나간 날이면 '언제 나오나'의 답이 될 수 없다")
                .isAfterOrEqualTo(status.today());
    }

    /**
     * <b>화면이 배치와 같은 편을 가리키는지</b>(2026-09-14 신설).
     *
     * <p>이 화면이 존재하는 이유가 "설정과 실제가 어긋난 것을 한눈에 보는 것"인데, 정작 이 줄이
     * 어긋나 있었다. 서버가 난이도를 안 보고 늘 {@code parsed.document()}, 즉 입문편 slug만
     * 찍었기 때문이다. 그날 실행 로그에는 {@code ...-advanced}가 남아 있었다.
     *
     * <p><b>기대값을 글자로 적지 않는다.</b> 오늘이 주기의 며칠차인지는 날짜마다 달라서
     * "심화편이어야 한다"고 박으면 나흘 중 사흘만 맞는 테스트가 된다. 대신 <b>규칙에게 물어</b>
     * 같은 답이 나오는지 본다 — 지켜야 할 것이 "정답"이 아니라 <b>두 곳의 일치</b>이기 때문이다.
     * 누군가 {@code sourceOf}에 조건문을 다시 복사해 넣으면 그 순간 갈라지고 여기서 걸린다.
     */
    @Test
    @DisplayName("화면이 가리키는 편이 배치가 읽을 편과 같다 — 규칙이 갈라지면 여기서 걸린다")
    void planPointsAtTheSameEditionAsTheBatch() throws Exception {
        Files.createDirectories(DIR.resolve("documents"));
        LocalDate documentDate = adminBatchService.getStatus().plan().documentDate();
        GeneratedDocumentFile written = writeBothEditionsAt(documentDate, "edition-probe");

        AdminBatchStatus.TodayPlan plan = adminBatchService.getStatus().plan();
        String expected = DocumentEditionRule.pick(written, plan.difficulty()).slug();

        assertThat(plan.documentSlug())
                .as("오늘 난이도(%s)가 읽을 편의 slug여야 한다", plan.difficulty())
                .isEqualTo(expected);
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
        // 바로 위 테스트와 같은 이유로 들여온 이력을 먼저 지운다. 이 테스트는 <내일>을 보는데,
        // 개발 PC에는 앞날 파일을 미리 만들어 들여놓은 이력이 남아 있다(2026-09-21에 실제로
        // 2026-09-22.json이 그랬다). 그러면 칸이 '미리 만들어 둠'이 아니라 '들어옴'으로 잡혀
        // 날짜가 바뀔 때마다 테스트가 붙었다 떨어졌다 한다 — 코드가 아니라 달력이 결과를 정한다.
        // (@Transactional이 이 삭제도 되돌리므로 실제 DB는 그대로다.)
        importedDraftFileRepository.deleteById(filename);

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

    /**
     * <b>달력도 근거 문서의 분야를 따른다</b>(2026-09-19 신설).
     *
     * <p>실물에서 지난 네 주기 16칸이 전부 틀린 분야를 달고 있었다. 달력은 날짜로 계산한 분야를
     * 찍었는데, 배치는 대기열에서 꺼낸 문서의 분야로 문제를 만든다. 오늘 카드는 이미 문서 쪽을
     * 따르고 있어서({@link #documentDomainWinsOverCycleDomain}) 한 화면에서 두 답이 나왔다.
     *
     * <p>기대 분야를 글자로 박지 않고 <b>지금 칸이 찍는 것과 다른 분야</b>를 골라 문서에 넣는다.
     * 우연히 계획과 같은 분야를 넣으면 고치기 전 코드로도 통과해 버린다.
     */
    @Test
    @DisplayName("달력의 분야는 근거 문서 쪽이 이긴다 — 오늘 카드와 같은 규칙이어야 한다")
    void calendarUsesDocumentDomain() throws Exception {
        Files.createDirectories(DIR.resolve("documents"));
        LocalDate documentDate = adminBatchService.getStatus().plan().documentDate();
        DomainCode planned = cellOf(adminBatchService.getStatus(), documentDate).domain();
        DomainCode other = otherThan(planned);
        writeDocumentAt(documentDate, "calendar-probe", other);

        List<AdminBatchStatus.DayCell> cycle = adminBatchService.getStatus().calendar().stream()
                .filter(c -> !c.date().isBefore(documentDate) && c.date().isBefore(documentDate.plusDays(4)))
                .toList();

        // 결과 파일이 없는 칸들이므로 나흘 모두 문서의 분야를 따라야 한다.
        assertThat(cycle).hasSize(4).extracting(AdminBatchStatus.DayCell::domain).containsOnly(other);
    }

    /**
     * <b>결과 파일이 있으면 파일 내용이 이긴다</b>(2026-09-19 신설).
     *
     * <p>08-29에 손으로 채운 파일들은 옛 위상으로 만들어져 분야도 난이도도 계획과 다르다
     * (예: 09-21 계획은 중급인데 파일은 초급). 그날 배치는 파일이 있어 건너뛰므로 실제로 들어오는
     * 것은 파일 내용이다 — 화면이 계획을 찍으면 들어오지도 않을 것을 말하게 된다.
     */
    @Test
    @DisplayName("결과 파일이 있는 칸은 파일의 분야·난이도를 찍는다 — 들어오는 것은 계획이 아니라 파일이다")
    void calendarUsesProducedFile() throws Exception {
        // 문제일을 하나 고른다. 오늘 주기 안에서 찾으면 날짜에 따라 없을 수 있어 달력 전체에서 찾는다.
        AdminBatchStatus.DayCell target = adminBatchService.getStatus().calendar().stream()
                .filter(c -> !c.documentDay())
                .findFirst()
                .orElseThrow();
        DomainCode domain = otherThan(target.domain());
        Difficulty difficulty = target.difficulty() == Difficulty.BEGINNER
                ? Difficulty.ADVANCED : Difficulty.BEGINNER;
        importedDraftFileRepository.deleteById(target.filename());
        Files.createDirectories(DIR);
        write(target.filename(), """
                {"domain":"%s","difficulty":"%s","problems":[]}""".formatted(domain, difficulty));

        AdminBatchStatus.DayCell cell = cellOf(adminBatchService.getStatus(), target.date());

        assertThat(cell.domain()).isEqualTo(domain);
        assertThat(cell.difficulty()).isEqualTo(difficulty);
    }

    /* ── 요청 대비 수확(2026-09-19) ──────────────────────────────────────
     *
     * 고급이 네 주기 연속 3개 중 1~2개만 나왔는데 달력은 전부 ✓였다. 들어온 수만 적고
     * 요청 수를 안 적었기 때문이다. 여기서 지킬 것은 세 가지다 — 빈 지문은 세지 않는다,
     * 요청 수는 파일의 난이도를 따른다, 손 실행·앞날 파일은 수확률에 섞이지 않는다. */

    @Test
    @DisplayName("달력 칸은 요청 수와 나온 수를 함께 싣는다 — 빈 지문은 나온 것으로 세지 않는다")
    void calendarCarriesRequestedAndProduced() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        AdminBatchStatus.DayCell target = adminBatchService.getStatus().calendar().stream()
                // 09-05 이전은 난이도와 무관하게 5를 요청하던 시절이라(COUNT_SPLIT_SINCE) 피한다
                .filter(c -> !c.documentDay() && !c.date().isAfter(today)
                        && !c.date().isBefore(LocalDate.of(2026, 9, 5)))
                .findFirst()
                .orElseThrow();
        Files.createDirectories(DIR);
        // 09-18 실물과 같은 모양 — 셋 중 둘이 빈 지문
        writeProblems(target.filename(), Difficulty.ADVANCED, "지문", "", "  ");

        AdminBatchStatus.DayCell cell = cellOf(adminBatchService.getStatus(), target.date());

        assertThat(cell.requested()).isEqualTo(3);
        assertThat(cell.produced()).isEqualTo(1);
        // 이유 필드가 없는 파일(09-19 이전 모양)은 null — 화면이 "이유가 남아 있지 않다"로 갈라 말한다
        assertThat(cell.shortfallReasons()).isNull();
    }

    @Test
    @DisplayName("결과 파일에 남은 이유가 칸에 실린다 — Actions 요약까지 가지 않아도 되게")
    void calendarCarriesShortfallReasons() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        AdminBatchStatus.DayCell target = adminBatchService.getStatus().calendar().stream()
                .filter(c -> !c.documentDay() && !c.date().isAfter(today))
                .findFirst()
                .orElseThrow();
        Files.createDirectories(DIR);
        write(target.filename(), """
                {"domain":"DATABASE","difficulty":"ADVANCED","problems":[{"question":"지문"}],
                 "shortfallReasons":["2번: 한계 조건을 1번에서 다 썼음","3번: (이유를 남기지 않음)"]}""");

        AdminBatchStatus.DayCell cell = cellOf(adminBatchService.getStatus(), target.date());

        assertThat(cell.shortfallReasons())
                .containsExactly("2번: 한계 조건을 1번에서 다 썼음", "3번: (이유를 남기지 않음)");
    }

    @Test
    @DisplayName("문서일과 파일 없는 날은 분수를 싣지 않는다 — 없는 부족을 그리지 않게")
    void calendarLeavesFractionEmptyWithoutFile() {
        List<AdminBatchStatus.DayCell> calendar = adminBatchService.getStatus().calendar();

        assertThat(calendar).filteredOn(AdminBatchStatus.DayCell::documentDay)
                .allSatisfy(c -> assertThat(c.requested()).isNull());
        // 폴더를 비웠으므로 모든 칸에 파일이 없다
        assertThat(calendar).allSatisfy(c -> assertThat(c.produced()).isNull());
    }

    @Test
    @DisplayName("난이도별 수확률은 지난 30일 예약 실행만 센다 — 손 실행(접미사)·앞날 파일은 뺀다")
    void yieldCountsOnlyScheduledPastRuns() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        Files.createDirectories(DIR);
        writeProblems(today.minusDays(1) + ".json", Difficulty.ADVANCED, "a", "", "");    // 1/3
        writeProblems(today.minusDays(2) + ".json", Difficulty.ADVANCED, "a", "b", "c");  // 3/3
        writeProblems(today.minusDays(3) + "-hand.json", Difficulty.ADVANCED, "", "", "a"); // 손 실행 — 제외
        writeProblems(today.plusDays(3) + ".json", Difficulty.ADVANCED, "", "", "a");      // 앞날 — 제외
        writeProblems(today.minusDays(40) + ".json", Difficulty.ADVANCED, "", "", "a");    // 기간 밖 — 제외

        List<AdminBatchStatus.DifficultyYield> yields = adminBatchService.getStatus().yieldByDifficulty();

        // 실행이 없는 난이도도 줄이 있어야 한다 — 빠지면 화면이 "문제없음"으로 읽는다
        assertThat(yields).extracting(AdminBatchStatus.DifficultyYield::difficulty)
                .containsExactly(Difficulty.BEGINNER, Difficulty.INTERMEDIATE, Difficulty.ADVANCED);
        AdminBatchStatus.DifficultyYield advanced = yields.get(2);
        assertThat(advanced.runs()).isEqualTo(2);
        assertThat(advanced.requested()).isEqualTo(6);
        assertThat(advanced.produced()).isEqualTo(4);
        assertThat(advanced.shortRuns()).isEqualTo(1);
        assertThat(yields.get(0).runs()).isZero();
    }

    @Test
    @DisplayName("수확 집계는 만든 초안을 승인·거절·대기로 가른다 — '몇 건 들어왔나'로는 알 수 없던 것")
    void harvestCountsDraftsByStatus() {
        AdminBatchStatus.Harvest before = adminBatchService.getStatus().harvest();

        generatedProblemDraftRepository.save(GeneratedProblemDraft.pending(
                TestDomains.NETWORK, Difficulty.BEGINNER, ProblemType.MULTIPLE_CHOICE,
                "제목", "질문?", "1", "해설", "[]", "test-model", null, null, null));

        AdminBatchStatus.Harvest after = adminBatchService.getStatus().harvest();

        assertThat(after.generated()).isEqualTo(before.generated() + 1);
        assertThat(after.pending()).isEqualTo(before.pending() + 1);
        // 합이 맞아야 한다 — 화면이 이 셋으로 막대를 그리므로 어긋나면 막대가 100%를 넘는다.
        assertThat(after.generated()).isEqualTo(after.approved() + after.rejected() + after.pending());
    }

    /**
     * <b>배치 현황도 분야 설정 테이블의 순서로 돈다</b>(최종 리뷰 Important 1, 2026-09-22).
     *
     * <p>이 서비스만 yml {@code batch-domains}를 {@code @Value}로 받아 달력을 그렸다. 설정 화면에서
     * 순서를 바꾸고 커밋하면 배치와 미리보기는 새 순서로 도는데 이 화면만 옛 순서를 보여 줬다.
     *
     * <p>OS 하나만 켠다 — yml 8개로 계산하면 24일 달력에 여러 분야가 섞이므로, 칸이 전부 OS인지로
     * 어느 목록을 읽었는지가 갈린다. 저장 <직후> 같은 빈으로 다시 부르는 것도 본다 — 목록을 필드에
     * 굳혀 두면 재시작 전까지 옛 순서가 남는다. 폴더를 비웠으므로 근거 문서·결과 파일이 칸의 분야를
     * 덮어쓰지 않는다(그 우선순위는 위 두 테스트가 따로 본다).
     */
    @Test
    @DisplayName("오늘 카드와 달력은 분야 설정 테이블을 읽는다 — yml 순서가 아니라")
    void usesDomainSettingTableNotYml() {
        domainSettingService.findAll().stream()
                .filter(s -> s.getDomain().equals(TestDomains.OS))
                .forEach(s -> domainSettingService.edit(TestDomains.OS,
                        new AdminDomainSettingRequest(true, s.getDisplayName(), s.getHint())));
        domainSettingService.findAll().stream()
                .filter(s -> !s.getDomain().equals(TestDomains.OS) && s.isEnabled())
                .forEach(s -> domainSettingService.edit(s.getDomain(),
                        new AdminDomainSettingRequest(false, s.getDisplayName(), s.getHint())));

        AdminBatchStatus status = adminBatchService.getStatus();

        assertThat(status.plan().cycleDomain()).isEqualTo(TestDomains.OS);
        assertThat(status.calendar())
                .extracting(AdminBatchStatus.DayCell::domain)
                .as("yml 8개로 계산했다면 24일 동안 여러 분야가 섞인다")
                .containsOnly(TestDomains.OS);
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
        writeDocumentAt(date, slug, TestDomains.NETWORK);
    }

    private void writeDocumentAt(LocalDate date, String slug, DomainCode domain) throws Exception {
        var file = new GeneratedDocumentFile("테스트", date.toString(), date + "T00:00:00Z",
                domain, "test", new GeneratedDocumentItem("제목", slug, "# 본문", List.of("net")), null);
        objectMapper.writeValue(DIR.resolve("documents").resolve(date + ".json").toFile(), file);
    }

    /**
     * 문제 결과 파일. 지문만 채운 최소 모양이다 — 이 서비스가 읽는 것은 머리(분야·난이도)와
     * 지문의 빈칸 여부뿐이라, 보기·해설까지 채우면 무엇을 검사하는 테스트인지가 흐려진다.
     */
    private void writeProblems(String filename, Difficulty difficulty, String... questions) throws Exception {
        String items = java.util.Arrays.stream(questions)
                .map(q -> "{\"question\":\"%s\"}".formatted(q))
                .collect(java.util.stream.Collectors.joining(","));
        write(filename, """
                {"domain":"NETWORK","difficulty":"%s","problems":[%s]}""".formatted(difficulty, items));
    }

    /** 주어진 것과 다른 분야 하나. 계획과 우연히 같은 값을 넣어 테스트가 헛돌지 않게 한다. */
    private DomainCode otherThan(DomainCode domain) {
        return TestDomains.NETWORK.equals(domain) ? TestDomains.OS : TestDomains.NETWORK;
    }

    /** 두 편이 다 있는 문서. 편을 고르는 규칙을 확인하려면 고를 것이 둘이어야 한다. */
    private GeneratedDocumentFile writeBothEditionsAt(LocalDate date, String slug) throws Exception {
        var file = new GeneratedDocumentFile("테스트", date.toString(), date + "T00:00:00Z",
                TestDomains.NETWORK, "test",
                new GeneratedDocumentItem("제목", slug, "# 입문편", List.of("net")),
                new GeneratedDocumentItem("제목", slug + "-advanced", "# 심화편", List.of("net")));
        objectMapper.writeValue(DIR.resolve("documents").resolve(date + ".json").toFile(), file);
        return file;
    }

    private void write(String name, String body) throws Exception {
        Files.writeString(DIR.resolve(name), body);
    }

    private void writeDocument(String name, String slug) throws Exception {
        var file = new GeneratedDocumentFile("테스트", "2026-01-03", "2026-01-03T00:00:00Z",
                TestDomains.NETWORK, "test", new GeneratedDocumentItem("제목", slug, "# 본문", List.of("net")), null);
        objectMapper.writeValue(DIR.resolve("documents").resolve(name).toFile(), file);
    }
}
