package project.study.study_project.llm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.TestDomains;
import project.study.study_project.admin.dto.AdminTopicQueueRequest;
import project.study.study_project.global.common.DomainCode;
import project.study.study_project.llm.client.GeneratedDocumentItem;
import project.study.study_project.llm.dto.GeneratedDocumentFile;
import project.study.study_project.llm.repository.TopicQueueItemRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 주기별 분야 예측의 우선순위 — 문서 파일 → 주제 대기열 차례 → 순환.
 *
 * <p>2026-10-03에 분야 설정 미리보기와 배치 현황이 서로 다른 분야를 말했다. 미리보기는 날짜
 * 순환만 봤고, 배치는 대기열에서 꺼낸 주제의 분야로 문서를 썼다. 두 화면이 같은 예측을 쓰는지는
 * 여기서 우선순위를 지키는 것으로 갈음한다.
 *
 * <p>대기열을 비우고 시작한다. 개발 DB에 실제 주제가 들어 있어, 그대로 두면 테스트가 그 내용에 묶인다.
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties = "llm.import.dir=build/test-domain-forecast")
class BatchDomainForecastIntegrationTest {

    private static final Path DIR = Path.of("build/test-domain-forecast");

    @Autowired
    private BatchDomainForecast forecast;
    @Autowired
    private TopicQueueService topicQueueService;
    @Autowired
    private TopicQueueItemRepository topicQueueItemRepository;
    @Autowired
    private ObjectMapper objectMapper;

    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));

    @BeforeEach
    void setUp() throws Exception {
        topicQueueItemRepository.deleteAll();
        Path documents = DIR.resolve("documents");
        Files.createDirectories(documents);
        try (var files = Files.list(documents)) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
    }

    @Test
    @DisplayName("문서가 아직 없는 앞날 문서일은 대기열 차례대로 분야가 정해진다")
    void futureDocumentDaysFollowTheQueue() {
        enqueue(TestDomains.SECURITY, "토큰 만료");
        enqueue(TestDomains.DATABASE, "인덱스");

        Map<LocalDate, BatchDomainForecast.Forecast> result =
                forecast.forDocumentDates(List.of(today.plusDays(6), today.plusDays(2)));

        assertThat(result.get(today.plusDays(2)).domain()).isEqualTo(TestDomains.SECURITY);
        assertThat(result.get(today.plusDays(6)).domain()).isEqualTo(TestDomains.DATABASE);
        assertThat(result.values()).allMatch(f -> f.source().equals(DomainSettingService.SOURCE_QUEUE));
    }

    @Test
    @DisplayName("문서 파일이 있으면 그 문서의 분야가 이긴다 — 대기열 차례도 쓰지 않는다")
    void documentFileWinsAndDoesNotConsumeTheQueue() throws Exception {
        enqueue(TestDomains.SECURITY, "토큰 만료");
        writeDocumentAt(today.minusDays(1), TestDomains.OS);

        Map<LocalDate, BatchDomainForecast.Forecast> result =
                forecast.forDocumentDates(List.of(today.minusDays(1), today.plusDays(3)));

        assertThat(result.get(today.minusDays(1)))
                .isEqualTo(new BatchDomainForecast.Forecast(TestDomains.OS, DomainSettingService.SOURCE_DOCUMENT));
        assertThat(result.get(today.plusDays(3)).domain())
                .as("앞 주기가 문서로 정해졌으니 대기열 첫 차례는 다음 주기 몫이다")
                .isEqualTo(TestDomains.SECURITY);
    }

    @Test
    @DisplayName("지난 문서일에 파일이 없으면 예측하지 않는다 — 그 주기는 문서 없이 돌았다")
    void pastDocumentDayWithoutFileHasNoForecast() {
        enqueue(TestDomains.SECURITY, "토큰 만료");

        assertThat(forecast.forDocumentDates(List.of(today.minusDays(2)))).isEmpty();
    }

    @Test
    @DisplayName("대기열이 비면 예측하지 않는다 — 부르는 쪽이 순환 분야를 그대로 쓴다")
    void emptyQueueLeavesRotation() {
        assertThat(forecast.forDocumentDates(List.of(today.plusDays(1)))).isEmpty();

        List<DomainSettingService.PreviewCell> cells =
                forecast.preview(List.of(TestDomains.OS, TestDomains.NETWORK), 7);

        assertThat(cells).hasSize(7);
        assertThat(cells).allMatch(c -> c.source().equals(DomainSettingService.SOURCE_ROTATION));
        assertThat(cells).allMatch(c -> c.domain().equals("OS") || c.domain().equals("NETWORK"));
    }

    @Test
    @DisplayName("미리보기는 대기열에 차례가 있으면 넘긴 순서 대신 그 분야를 보여 준다")
    void previewShowsQueueDomainInsteadOfGivenOrder() {
        enqueue(TestDomains.SECURITY, "토큰 만료");

        List<DomainSettingService.PreviewCell> cells =
                forecast.preview(List.of(TestDomains.OS, TestDomains.NETWORK), 9);

        // 9일이면 4일 주기의 문서일이 적어도 두 번 든다 — 오늘 이후 문서일이 반드시 하나는 있다.
        assertThat(cells).filteredOn(c -> !c.documentDate().isBefore(today))
                .isNotEmpty()
                .allMatch(c -> c.source().equals(DomainSettingService.SOURCE_QUEUE))
                .allMatch(c -> c.domain().equals("SECURITY"));
    }

    /**
     * 2026-10-03 실물: 8월에 손으로 채운 {@code 2026-10-03.json}(네트워크 고급)이 있어 그날 배치가
     * 건너뛰었다. 달력은 파일 내용을 찍는데 미리보기는 보안 중급이라고 했다.
     */
    @Test
    @DisplayName("그날 결과 파일이 이미 있으면 미리보기도 파일의 분야·난이도를 보여 준다")
    void existingResultFileWinsInPreview() throws Exception {
        enqueue(TestDomains.SECURITY, "토큰 만료");
        List<DomainSettingService.PreviewCell> before =
                forecast.preview(List.of(TestDomains.OS, TestDomains.NETWORK), 4);
        LocalDate problemDay = before.stream().filter(c -> !c.documentDay()).findFirst().orElseThrow().date();
        Files.writeString(DIR.resolve(problemDay + ".json"),
                "{\"domain\":\"DATABASE\",\"difficulty\":\"ADVANCED\",\"problems\":[]}");

        try {
            DomainSettingService.PreviewCell cell =
                    forecast.preview(List.of(TestDomains.OS, TestDomains.NETWORK), 4).stream()
                            .filter(c -> c.date().equals(problemDay)).findFirst().orElseThrow();

            assertThat(cell.domain()).isEqualTo("DATABASE");
            assertThat(cell.difficulty()).isEqualTo("ADVANCED");
            assertThat(cell.source()).isEqualTo(DomainSettingService.SOURCE_FILE);
        } finally {
            Files.deleteIfExists(DIR.resolve(problemDay + ".json"));
        }
    }

    private void enqueue(DomainCode domain, String topic) {
        topicQueueService.add(new AdminTopicQueueRequest(domain, topic, null));
    }

    private void writeDocumentAt(LocalDate date, DomainCode domain) throws Exception {
        var file = new GeneratedDocumentFile("테스트", date.toString(), date + "T00:00:00Z",
                domain, "test", new GeneratedDocumentItem("제목", "slug", "# 본문", List.of("net")), null);
        objectMapper.writeValue(DIR.resolve("documents").resolve(date + ".json").toFile(), file);
    }
}
