package project.study.study_project.llm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.global.common.DomainCode;
import project.study.study_project.llm.dto.GeneratedBatchFile;
import project.study.study_project.llm.dto.GeneratedDocumentFile;
import project.study.study_project.llm.dto.TopicQueueItemResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 주기마다 <b>실제로 나올 분야</b>를 한곳에서 정한다.
 *
 * <p>날짜 순환(분야 설정의 순서)은 분야를 정하는 마지막 수단일 뿐이다. 배치는 문서일에 주제
 * 대기열에서 범위를 꺼내 그 범위의 분야로 문서를 쓰고({@code DocumentBatch.topicDomain}), 이어지는
 * 사흘의 문제는 문서의 분야를 따른다({@code alignDomainWithDocument}). 그런데 분야 설정 미리보기는
 * 순환만 보고, 배치 현황은 이미 나온 문서만 봐서 두 화면이 서로 다른 분야를 말했다
 * (2026-10-03: 미리보기 "스프링·백엔드", 배치 현황 "보안").
 *
 * <p>우선순위는 배치가 따르는 순서 그대로다 — 그 주기의 문서 파일 → 대기열 차례 → 순환.
 * 미리보기는 여기에 <b>그날 결과 파일</b>을 맨 앞에 둔다(파일이 있는 날은 배치가 건너뛴다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchDomainForecast {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 개념 문서가 쌓이는 하위 폴더({@code DraftGeneratorCli}와 같은 이름이어야 한다). */
    private static final String DOCUMENT_SUBDIR = "documents";

    private final TopicQueueService topicQueueService;
    private final DomainSettingService domainSettingService;
    private final ObjectMapper objectMapper;

    @Value("${llm.import.dir:generated}")
    private String importDir;

    /**
     * 한 주기의 분야 예측.
     *
     * @param source {@link DomainSettingService#SOURCE_DOCUMENT} 또는 {@link DomainSettingService#SOURCE_QUEUE}
     */
    public record Forecast(DomainCode domain, String source) {
    }

    /**
     * 문서일별 분야. <b>순환으로 정해질 주기는 맵에 없다</b> — 부르는 쪽이 자기 계획 값을 그대로 쓴다.
     *
     * <p>대기열은 문서 파일이 아직 없는 <b>오늘 이후</b> 문서일에만 차례대로 하나씩 댄다.
     * 지난 문서일에 파일이 없으면 그 주기는 문서 없이 돈 것이라(폴백) 순환 분야가 맞다.
     */
    @Transactional(readOnly = true)
    public Map<LocalDate, Forecast> forDocumentDates(Collection<LocalDate> documentDates) {
        LocalDate today = LocalDate.now(KST);
        List<TopicQueueItemResponse> queue = topicQueueService.upcoming();
        int taken = 0;

        Map<LocalDate, Forecast> forecasts = new HashMap<>();
        for (LocalDate documentDate : new TreeSet<>(documentDates)) {
            DomainCode fromFile = documentDomainAt(documentDate);
            if (fromFile != null) {
                forecasts.put(documentDate, new Forecast(fromFile, DomainSettingService.SOURCE_DOCUMENT));
            } else if (!documentDate.isBefore(today) && !queue.isEmpty()) {
                // 범위는 소진되지 않는다 — 다 쓰면 가장 오래전에 쓴 것부터 다시 돈다. 한 바퀴를 넘는
                // 앞날까지 내다볼 일은 드물지만, 그때도 같은 차례가 되풀이된다.
                forecasts.put(documentDate,
                        new Forecast(queue.get(taken++ % queue.size()).domain(), DomainSettingService.SOURCE_QUEUE));
            }
        }
        return forecasts;
    }

    /**
     * 분야 설정 미리보기에 실제 분야를 덮어쓴다. 난이도와 문서일 여부는 날짜가 정하므로 그대로 둔다.
     *
     * @param domains 화면이 지금 들고 있는 순서 — 대기열도 문서도 없는 주기에만 쓰인다
     */
    @Transactional(readOnly = true)
    public List<DomainSettingService.PreviewCell> preview(List<DomainCode> domains, int days) {
        List<DomainSettingService.PreviewCell> cells = domainSettingService.preview(domains, days);
        Map<LocalDate, Forecast> forecasts = forDocumentDates(
                cells.stream().map(DomainSettingService.PreviewCell::documentDate).toList());

        return cells.stream()
                .map(cell -> {
                    // 그날 결과 파일이 이미 있으면 그것이 이긴다 — 배치는 파일이 있는 날을 건너뛰므로
                    // 들어오는 것은 파일의 내용이다(배치 현황 달력과 같은 우선순위, AdminBatchService.calendar).
                    GeneratedBatchFile file = cell.documentDay() ? null : batchFileAt(cell.date());
                    if (file != null && file.domain() != null) {
                        return new DomainSettingService.PreviewCell(
                                cell.date(), false, file.domain().value(),
                                file.difficulty() == null ? cell.difficulty() : file.difficulty().name(),
                                cell.documentDate(), DomainSettingService.SOURCE_FILE);
                    }
                    Forecast forecast = forecasts.get(cell.documentDate());
                    return forecast == null ? cell : new DomainSettingService.PreviewCell(
                            cell.date(), cell.documentDay(), forecast.domain().value(), cell.difficulty(),
                            cell.documentDate(), forecast.source());
                })
                .toList();
    }

    /** 그 날짜의 예약 실행 결과 파일. 없거나 못 읽으면 {@code null}. */
    private GeneratedBatchFile batchFileAt(LocalDate date) {
        Path file = Path.of(importDir).resolve(date + ".json");
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return objectMapper.readValue(file.toFile(), GeneratedBatchFile.class);
        } catch (IOException e) {
            log.warn("생성 결과 파일을 읽지 못했습니다: {} — {}", file, e.getMessage());
            return null;
        }
    }

    /** 그 날짜 문서 파일의 분야. 파일이 없거나 못 읽으면 {@code null} — 못 읽는 문서는 없는 문서와 같다. */
    private DomainCode documentDomainAt(LocalDate documentDate) {
        Path file = Path.of(importDir).resolve(DOCUMENT_SUBDIR).resolve(documentDate + ".json");
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return objectMapper.readValue(file.toFile(), GeneratedDocumentFile.class).domain();
        } catch (IOException e) {
            log.warn("근거 문서를 읽지 못했습니다: {} — {}", file, e.getMessage());
            return null;
        }
    }
}
