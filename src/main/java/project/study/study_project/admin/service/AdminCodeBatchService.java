package project.study.study_project.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import project.study.study_project.llm.support.JsonMappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.admin.dto.AdminCodeBatchStatus;
import project.study.study_project.llm.client.ProblemReview;
import project.study.study_project.llm.domain.TopicKind;
import project.study.study_project.llm.dto.GeneratedBatchFile;
import project.study.study_project.llm.dto.TopicQueueItemResponse;
import project.study.study_project.llm.repository.ImportedDraftFileRepository;
import project.study.study_project.llm.service.TopicQueueService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 주간 코드 문제 배치의 상태를 모은다 — 주제 대기열의 다음 차례와 {@code generated/}의 결과 파일.
 *
 * <p>일일 배치처럼 로컬 앱에서만 뜻이 있다. 결과 파일은 이 PC의 저장소 사본에 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminCodeBatchService {

    /** {@code CodeProblemCli.fileNameOf}가 쓰는 이름. 날짜 하나에 난이도별 파일이 최대 셋이다. */
    private static final Pattern CODE_FILE = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})-code-(beg|int|adv)\\.json$");

    /** 워크플로 cron {@code 47 17 * * 6}(토 17:47 UTC)을 한국 시간으로 옮긴 값. 바꾸면 두 곳을 같이 고친다. */
    static final DayOfWeek RUN_DAY = DayOfWeek.SUNDAY;
    static final LocalTime RUN_TIME = LocalTime.of(2, 47);

    /** 화면에 싣는 최근 실행 수. 두 달 치면 주제가 한 바퀴 도는지 보인다. */
    private static final int RECENT_RUNS = 8;

    private static final ObjectMapper READER = JsonMappers.LENIENT;

    private final TopicQueueService topicQueueService;
    private final ImportedDraftFileRepository importedDraftFileRepository;

    @Value("${llm.import.dir:generated}")
    private String importDir;

    @Transactional(readOnly = true)
    public AdminCodeBatchStatus getStatus() {
        List<TopicQueueItemResponse> topics = topicQueueService.getAll(TopicKind.CODE);
        String next = topics.stream().filter(TopicQueueItemResponse::next)
                .map(TopicQueueItemResponse::topic).findFirst().orElse(null);
        return new AdminCodeBatchStatus(topics.size(), next,
                nextRunAt(LocalDateTime.now(ZoneId.of("Asia/Seoul"))), recentRuns(Path.of(importDir)));
    }

    /** 오늘이 일요일이고 아직 02:47 전이면 오늘이다. */
    static LocalDateTime nextRunAt(LocalDateTime now) {
        LocalDateTime candidate = now.toLocalDate().with(TemporalAdjusters.nextOrSame(RUN_DAY)).atTime(RUN_TIME);
        return candidate.isAfter(now) ? candidate : candidate.plusWeeks(1);
    }

    /** 파일을 날짜로 묶는다. 읽지 못한 파일은 건너뛴다 — 화면 한 줄 때문에 전체가 죽으면 안 된다. */
    List<AdminCodeBatchStatus.Run> recentRuns(Path dir) {
        Map<String, List<Path>> byDate = new TreeMap<>(java.util.Comparator.reverseOrder());
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.forEach(f -> {
                Matcher m = CODE_FILE.matcher(f.getFileName().toString());
                if (m.matches()) {
                    byDate.computeIfAbsent(m.group(1), d -> new ArrayList<>()).add(f);
                }
            });
        } catch (IOException e) {
            log.warn("코드 문제 결과 파일 목록을 읽지 못했습니다: {}", e.getMessage());
            return List.of();
        }

        List<AdminCodeBatchStatus.Run> runs = new ArrayList<>();
        for (Map.Entry<String, List<Path>> entry : byDate.entrySet()) {
            if (runs.size() >= RECENT_RUNS) {
                break;
            }
            runs.add(runOf(entry.getKey(), entry.getValue()));
        }
        return runs;
    }

    private AdminCodeBatchStatus.Run runOf(String date, List<Path> files) {
        String topic = null;
        int problems = 0;
        Integer findings = null;
        boolean imported = true;
        for (Path file : files) {
            imported &= importedDraftFileRepository.existsById(file.getFileName().toString());
            try {
                GeneratedBatchFile batch = READER.readValue(file.toFile(), GeneratedBatchFile.class);
                topic = topic == null ? batch.codeTopic() : topic;
                problems += batch.problems() == null ? 0 : batch.problems().size();
                if (batch.reviewFindings() != null) {
                    // 의견(풀이·가독성)은 문제마다 붙어 세면 늘 문제 수만큼 나온다. 검수함 요약 표와 같은 기준이다
                    long count = batch.reviewFindings().stream()
                            .filter(f -> f != null && f.type() != null
                                    && f.type().grade() != ProblemReview.Grade.OPINION)
                            .count();
                    findings = (findings == null ? 0 : findings) + (int) count;
                }
            } catch (IOException e) {
                log.warn("코드 문제 결과 파일을 읽지 못했습니다: {} ({})", file.getFileName(), e.getMessage());
            }
        }
        return new AdminCodeBatchStatus.Run(date, topic, problems, findings, imported);
    }
}
