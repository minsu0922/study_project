package project.study.study_project.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.admin.dto.AdminBatchStatus;
import project.study.study_project.global.common.Domain;
import project.study.study_project.llm.domain.DraftStatus;
import project.study.study_project.llm.dto.GeneratedDocumentFile;
import project.study.study_project.llm.repository.GeneratedDocumentDraftRepository;
import project.study.study_project.llm.repository.GeneratedProblemDraftRepository;
import project.study.study_project.llm.repository.ImportedDraftFileRepository;
import project.study.study_project.llm.support.BatchCountRule;
import project.study.study_project.llm.support.GenerationSchedule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 배치 현황 화면이 읽는 것들을 한곳에 모은다 — 2026-09-01 신설(docs/14).
 *
 * <p><b>왜 서비스가 파일을 읽나.</b> 배치의 상태는 절반이 DB 밖에 있다. 무엇이 만들어졌는지는
 * {@code generated/} 폴더의 파일이고, 무엇이 들어왔는지는 DB의 이력 테이블이다. 둘을 맞춰 봐야
 * "만들어졌는데 아직 안 들어온 것"이 보이는데, 그게 이 화면에서 가장 자주 찾게 되는 답이다
 * (앱을 며칠 안 켜면 그대로 쌓인다).
 *
 * <p><b>로컬 앱에서만 뜻이 있다.</b> 배포된 서버에는 {@code generated/} 폴더가 없다 —
 * 그 폴더는 저장소의 것이고 배치도 GitHub Actions에서 돈다. 폴더가 없으면 파일 관련 칸이
 * 빈 채로 나가고 나머지(설정·주기·이력)는 그대로 답한다. <b>예외를 던지지 않는다</b> —
 * 이 화면은 진단용이라, 한 조각을 못 읽는다고 나머지까지 못 보게 되는 쪽이 더 나쁘다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminBatchService {

    /** 예약 실행이 쓰는 결과 파일 이름 — 접미사가 붙지 않은 날짜 그대로. */
    private static final Pattern SCHEDULED_FILE = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})\\.json$");

    /** 이력 목록에 실어 보낼 최근 건수. 화면 한 눈에 들어오는 만큼만 — 더 필요하면 파일을 본다. */
    private static final int RECENT_IMPORTS = 15;

    /** 개념 문서가 쌓이는 하위 폴더({@code DraftGeneratorCli}와 같은 이름이어야 한다). */
    private static final String DOCUMENT_SUBDIR = "documents";

    /**
     * 달력이 거슬러 올라가는 주기 수(과거)와 앞서 보여 주는 주기 수(미래).
     *
     * <p>4 + 1 + 1(오늘이 든 주기) = 6줄 24일. 더 늘리면 화면을 스크롤해야 하고, 줄이면
     * "언제부터 빠졌나"를 답하지 못한다 — 배치가 조용히 죽어 있던 기간이 실제로 2~3주였다(docs/14).
     */
    private static final int PAST_CYCLES = 4;
    private static final int FUTURE_CYCLES = 1;

    /** 수확 집계 기간. 한 달이면 주기가 일곱 번 넘게 돌아 한두 번의 실패에 숫자가 흔들리지 않는다. */
    private static final int HARVEST_DAYS = 30;

    private final ImportedDraftFileRepository importedDraftFileRepository;
    private final GeneratedProblemDraftRepository generatedProblemDraftRepository;
    private final GeneratedDocumentDraftRepository generatedDocumentDraftRepository;
    private final ObjectMapper objectMapper;

    @Value("${llm.generation.batch-enabled:true}")
    private boolean batchEnabled;

    @Value("${llm.generation.batch-type:auto}")
    private String batchType;

    @Value("${llm.generation.batch-count:5}")
    private int batchCount;

    // 난이도별 배분(2026-09-05). 이 값이 CLI가 읽는 것과 어긋나면 화면이 거짓말을 한다 —
    // cycle-anchor에 적어 둔 것과 같은 이유다. 기본값 문자열도 BatchCountRule.DEFAULT_SPEC에서
    // 꺼내 쓰고 싶지만 @Value는 상수 표현식만 받으므로, 어긋나지 않게 테스트가 둘을 대조한다.
    @Value("${llm.generation.batch-count-by-difficulty:BEGINNER=7,INTERMEDIATE=5,ADVANCED=3}")
    private String batchCountByDifficulty;

    // 기본값 문자열이 AdminStatsService·LlmProblemService와 같아야 한다. 갈라지면 화면이 말하는
    // "이번 주기의 분야"와 배치가 실제로 고르는 분야가 어긋난다(그쪽 주석의 판단을 따른다).
    @Value("${llm.generation.batch-domains:NETWORK,OS,DATABASE,DS_ALGORITHM,SYSTEM_DESIGN,SECURITY,LANGUAGE_RUNTIME,BACKEND_FRAMEWORK}")
    private List<Domain> batchDomains;

    // 주기의 0일차(2026-09-02 신설). 이 값이 CLI가 읽는 것과 어긋나면 <b>화면이 거짓말을 한다</b> —
    // "오늘은 문서일"이라고 띄우는데 배치는 고급 문제를 만든다. 기본값을 에포크로 둔 것도 같은
    // 이유다: 설정이 없으면 양쪽 다 앵커 없던 시절의 위상을 쓴다(GenerationSchedule.DEFAULT_ANCHOR).
    @Value("${llm.generation.cycle-anchor:1970-01-01}")
    private LocalDate cycleAnchor;

    @Value("${llm.import.dir:generated}")
    private String importDir;

    @Transactional(readOnly = true)
    public AdminBatchStatus getStatus() {
        // 한국 날짜로 센다. 워크플로도 KST로 바꿔 CLI에 넘기므로 기준을 맞춰야
        // 화면이 말하는 "오늘"과 배치가 계산한 "오늘"이 같은 날이 된다.
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        Path dir = Path.of(importDir);

        AdminBatchStatus.TodayPlan plan = planOf(today, dir);
        // 개수는 <오늘 난이도의> 값을 싣는다(2026-09-05). 난이도별 배분이 생긴 뒤로도
        // batch-count를 그대로 보여 주면 초급 날에도 화면은 "5건"이라 말하는데 실제로는 7건이
        // 나온다 — 이 화면의 존재 이유가 "설정과 실제가 어긋난 것을 한눈에 보는 것"이라
        // (클래스 주석) 그 자리에서 어긋나면 화면이 없느니만 못하다.
        // 문서일에는 difficulty가 null이고, 그때는 만들 문제가 없으므로 폴백 값이 실린다.
        int count = BatchCountRule.countFor(batchCountByDifficulty, plan.difficulty(), batchCount);

        return new AdminBatchStatus(
                batchEnabled, batchType, count, today,
                plan,
                calendar(dir, today),
                harvest(today),
                generatedProblemDraftRepository.countByStatus(DraftStatus.PENDING),
                generatedDocumentDraftRepository.countByStatus(DraftStatus.PENDING),
                recentImports(),
                waitingFiles(dir),
                blockedDates(dir, today));
    }

    /**
     * 하루 한 칸의 달력 — 지난 네 주기부터 다음 주기까지, <b>주기 경계에 맞춰</b> 24일.
     *
     * <p><b>왜 이게 필요했나.</b> 같은 사실이 세 표에 흩어져 있었다. 파일이 있는데 안 들어온 것은
     * "안 들어온 파일", 앞으로 건너뛸 날은 "막힌 주기", 들어온 것은 "최근 들여오기". 정작 가장
     * 자주 묻는 <b>"며칠부터 안 나왔지"</b>는 어느 표도 답하지 않았다 — 그건 <b>없는 날짜</b>에
     * 대한 질문이라 어느 목록에도 줄이 생기지 않기 때문이다. 날짜를 축으로 깔면 빈 날이 곧 답이다.
     *
     * <p>시작을 오늘 주기의 문서일에서 역산해 잡는다. 그래야 4칸이 정확히 문서·초급·중급·고급이
     * 되어, 줄 하나가 주기 하나로 읽힌다(칸 순서가 어긋나면 달력이 아니라 숫자 나열이 된다).
     *
     * <p>파일 존재 여부를 하루씩 {@link Files#exists}로 묻는다 — 24번이면 폴더를 훑는 것보다 싸고,
     * 무엇보다 <b>파일 이름 규칙을 배치와 똑같이 적는</b> 코드가 된다(접미사 파일은 세지 않는다는
     * 규칙이 자연히 지켜진다 — 이름이 정확히 {@code <날짜>.json}인 것만 묻기 때문).
     */
    private List<AdminBatchStatus.DayCell> calendar(Path dir, LocalDate today) {
        LocalDate start = GenerationSchedule.planFor(today, batchDomains, cycleAnchor)
                .documentDate()
                .minusDays((long) GenerationSchedule.CYCLE_DAYS * PAST_CYCLES);
        int totalDays = GenerationSchedule.CYCLE_DAYS * (PAST_CYCLES + FUTURE_CYCLES + 1);

        // 1) 하루씩 계획과 파일명을 정한다.
        List<LocalDate> dates = new ArrayList<>();
        List<GenerationSchedule.Plan> plans = new ArrayList<>();
        List<String> filenames = new ArrayList<>();
        for (int i = 0; i < totalDays; i++) {
            LocalDate date = start.plusDays(i);
            GenerationSchedule.Plan plan = GenerationSchedule.planFor(date, batchDomains, cycleAnchor);
            dates.add(date);
            plans.add(plan);
            filenames.add(plan.documentDay() ? DOCUMENT_SUBDIR + "/" + date + ".json" : date + ".json");
        }

        // 2) 들여오기 이력은 한 번에 읽는다. 하루마다 existsById를 부르면 24번 왕복하는데,
        //    이 화면은 진단용이라 느려도 되지만 <b>이유 없이</b> 느릴 필요는 없다.
        Map<String, Integer> imported = new HashMap<>();
        importedDraftFileRepository.findAllById(filenames)
                .forEach(f -> imported.put(f.getFilename(), f.getDraftCount()));

        // 3) 근거 문서가 있는지는 주기마다 한 번만 본다(같은 주기의 사흘이 같은 파일을 가리킨다).
        Map<LocalDate, Boolean> sourceExists = new HashMap<>();

        List<AdminBatchStatus.DayCell> cells = new ArrayList<>();
        for (int i = 0; i < totalDays; i++) {
            LocalDate date = dates.get(i);
            GenerationSchedule.Plan plan = plans.get(i);
            String filename = filenames.get(i);

            boolean fileExists = Files.exists(dir.resolve(filename));
            Integer draftCount = imported.get(filename);
            AdminBatchStatus.DayState state = stateOf(fileExists, draftCount != null, date, today);

            // 폴백 여부는 문제일에만 뜻이 있다. 문서일은 스스로가 근거를 만드는 날이다.
            //
            // <b>근거 문서일이 아직 안 온 날은 표시하지 않는다</b>(실물에서 걸렸다 — 2026-09-08).
            // 다음다음 주기의 문제일 셋이 전부 "근거없음"으로 떴는데, 그 주기의 문서일 자체가
            // 나흘 뒤였다. 아직 만들 차례가 아닌 것을 결함처럼 칠하면 경고가 값을 잃는다.
            // 반대로 <b>내일</b> 문제일의 근거 문서가 어제 안 나온 것은 진짜 경고다 — 그건 남는다.
            boolean fallback = !plan.documentDay()
                    && !plan.documentDate().isAfter(today)
                    && !sourceExists.computeIfAbsent(
                    plan.documentDate(),
                    d -> Files.exists(dir.resolve(DOCUMENT_SUBDIR).resolve(d + ".json")));

            cells.add(new AdminBatchStatus.DayCell(
                    date,
                    (int) (date.toEpochDay() - plan.documentDate().toEpochDay()),
                    plan.documentDay(), plan.domain(), plan.difficulty(),
                    state, filename,
                    state == AdminBatchStatus.DayState.IMPORTED ? draftCount : null,
                    fallback));
        }
        return cells;
    }

    /**
     * 칸 하나의 상태 판정 — 파일이 있나 / 들어왔나 / 지난 날인가 세 가지로 갈린다.
     *
     * <p>오늘은 <b>지난 날 쪽</b>으로 센다. 배치는 06:17에 도는데 이 화면을 여는 시각은 대개
     * 그 뒤라, 오늘 칸이 비어 있으면 그건 "예정"이 아니라 "안 나왔다"는 뜻일 때가 많다.
     * 새벽에 열어 잘못 놀라는 쪽이, 며칠씩 빠진 것을 "예정"으로 읽고 지나치는 쪽보다 싸다.
     */
    private AdminBatchStatus.DayState stateOf(boolean fileExists, boolean isImported,
                                              LocalDate date, LocalDate today) {
        if (fileExists) {
            if (isImported) {
                return AdminBatchStatus.DayState.IMPORTED;
            }
            return date.isAfter(today)
                    ? AdminBatchStatus.DayState.PRESET   // 앞으로의 날 = 미리 만들어 둔 몫
                    : AdminBatchStatus.DayState.WAITING; // 지난 날 = 앱을 켜면 들어온다
        }
        return date.isAfter(today)
                ? AdminBatchStatus.DayState.PLANNED
                : AdminBatchStatus.DayState.MISSING;
    }

    /**
     * 최근 30일 수확 — 만든 초안이 승인까지 갔는지.
     *
     * <p>기준 시각을 한국 날짜의 자정으로 잡는다. {@code now().minusDays(30)}으로 하면 화면을
     * 여는 시각에 따라 경계에 걸친 하루가 들어왔다 나갔다 해서, 새로고침만 했는데 숫자가
     * 달라진다. 날짜로 자르면 하루 종일 같은 답이 나온다.
     */
    private AdminBatchStatus.Harvest harvest(LocalDate today) {
        LocalDateTime since = today.minusDays(HARVEST_DAYS - 1L).atStartOfDay();
        Map<DraftStatus, Long> counts = new EnumMap<>(DraftStatus.class);
        generatedProblemDraftRepository.countByStatusSince(since)
                .forEach(row -> counts.put(row.getStatus(), row.getCnt()));

        long approved = counts.getOrDefault(DraftStatus.APPROVED, 0L);
        long rejected = counts.getOrDefault(DraftStatus.REJECTED, 0L);
        long pending = counts.getOrDefault(DraftStatus.PENDING, 0L);
        return new AdminBatchStatus.Harvest(
                HARVEST_DAYS, approved + rejected + pending, approved, rejected, pending);
    }

    /**
     * 오늘의 주기와, 그 주기가 근거로 삼을 문서가 실제로 있는지.
     *
     * <p><b>분야는 문서가 이긴다.</b> 배치도 그렇게 한다({@code DraftGeneratorCli}의
     * {@code alignDomainWithDocument}) — 주기가 가리킨 분야와 문서의 분야가 다르면 문서 쪽으로
     * 맞춘다. 화면이 주기 분야만 보여 주면 실제로 나오는 것과 달라지므로 <b>둘 다</b> 싣는다.
     */
    private AdminBatchStatus.TodayPlan planOf(LocalDate today, Path dir) {
        GenerationSchedule.Plan plan = GenerationSchedule.planFor(today, batchDomains, cycleAnchor);
        // dayInCycle을 다시 계산하지 않고 <문서 날짜와의 차이>로 얻는다. 주기 길이를 여기서 또
        // 나눠 세면 GenerationSchedule의 계산과 갈라질 수 있고, 그때 화면만 조용히 틀린다.
        int dayInCycle = (int) (today.toEpochDay() - plan.documentDate().toEpochDay());

        SourceInfo source = sourceOf(dir, plan.documentDate());
        Domain actual = source.domain() != null ? source.domain() : plan.domain();

        return new AdminBatchStatus.TodayPlan(
                dayInCycle, plan.documentDay(), actual, plan.domain(), plan.difficulty(),
                plan.documentDate(), source.slug());
    }

    /** 근거 문서에서 화면이 쓰는 두 가지. 둘 다 없을 수 있다(파일이 없거나 못 읽는 경우). */
    private record SourceInfo(String slug, Domain domain) {
        static final SourceInfo NONE = new SourceInfo(null, null);
    }

    /**
     * 근거 문서의 slug와 분야 — 파일이 없거나 못 읽으면 둘 다 {@code null}.
     *
     * <p>여기서 검수 상태(거절됐는지)까지 보지는 않는다. 그건 배치가 스냅샷 파일로 판단하는
     * 일이고, 이 화면이 답하려는 것은 <b>"근거로 삼을 파일이 있기는 한가"</b>다.
     * 없으면 그날은 폴백으로 돌고, 그 사실이 slug의 빈 값으로 드러난다.
     */
    private SourceInfo sourceOf(Path dir, LocalDate documentDate) {
        Path file = dir.resolve(DOCUMENT_SUBDIR).resolve(documentDate + ".json");
        if (!Files.exists(file)) {
            return SourceInfo.NONE;
        }
        try {
            GeneratedDocumentFile parsed = objectMapper.readValue(file.toFile(), GeneratedDocumentFile.class);
            if (parsed.document() == null) {
                return SourceInfo.NONE;
            }
            return new SourceInfo(parsed.document().slug(), parsed.domain());
        } catch (IOException e) {
            // 못 읽는 것도 "근거가 없다"와 같은 결과다 — 화면을 죽이지 않고 로그만 남긴다.
            log.warn("근거 문서를 읽지 못했습니다: {} — {}", file, e.getMessage());
            return SourceInfo.NONE;
        }
    }

    private List<AdminBatchStatus.ImportRecord> recentImports() {
        return importedDraftFileRepository.findTop15ByOrderByImportedAtDesc().stream()
                .map(f -> new AdminBatchStatus.ImportRecord(
                        f.getFilename(), f.getImportedAt(), f.getDraftCount()))
                .toList();
    }

    /**
     * 만들어졌는데 아직 안 들어온 파일 — 문제 파일과 문서 파일을 함께 본다.
     *
     * <p>이력의 열쇠는 <b>파일명</b>인데 문서는 {@code documents/} 접두가 붙는다
     * ({@code DocumentImportService}). 접두를 빠뜨리면 문서 파일이 늘 "안 들어온 것"으로 보이므로
     * 같은 규칙으로 맞춘다 — 이 화면이 <b>매번 거짓 경보를 울리면 아무도 안 보게 된다</b>.
     */
    private List<String> waitingFiles(Path dir) {
        List<String> waiting = new ArrayList<>();
        collectJson(dir).forEach(name -> {
            if (!importedDraftFileRepository.existsById(name)) {
                waiting.add(name);
            }
        });
        collectJson(dir.resolve(DOCUMENT_SUBDIR)).forEach(name -> {
            String key = DOCUMENT_SUBDIR + "/" + name;
            if (!importedDraftFileRepository.existsById(key)) {
                waiting.add(key);
            }
        });
        return waiting;
    }

    /**
     * 앞으로의 날짜 중 결과 파일이 <b>이미 있는</b> 것 — 그날 예약 실행은 아무것도 하지 않는다.
     *
     * <p>지난 날짜는 세지 않는다. 이미 지나간 날에 파일이 있는 것은 정상(그날 실제로 돌았다)이고,
     * 섞어서 보여 주면 목록이 길어져 정작 앞으로 죽을 날짜가 묻힌다.
     *
     * <p>접미사가 붙은 파일({@code 2026-09-01-match-test.json})은 세지 않는다. 접미사는 예약
     * 실행과 이름이 겹치지 않게 하려고 붙이는 것이라, 그 파일이 있어도 그날 실행은 멀쩡히 돈다.
     * <b>이 목록에 접미사 파일이 뜬다면 접미사 규칙 자체가 깨진 것이다.</b>
     */
    private List<AdminBatchStatus.BlockedDate> blockedDates(Path dir, LocalDate today) {
        List<AdminBatchStatus.BlockedDate> blocked = new ArrayList<>();
        addBlocked(blocked, collectJson(dir), today, "");
        addBlocked(blocked, collectJson(dir.resolve(DOCUMENT_SUBDIR)), today, DOCUMENT_SUBDIR + "/");
        blocked.sort((a, b) -> a.date().compareTo(b.date()));
        return blocked;
    }

    private void addBlocked(List<AdminBatchStatus.BlockedDate> out, List<String> names,
                            LocalDate today, String prefix) {
        for (String name : names) {
            var matcher = SCHEDULED_FILE.matcher(name);
            if (!matcher.matches()) {
                continue;
            }
            LocalDate date = LocalDate.parse(matcher.group(1));
            if (date.isAfter(today)) {
                out.add(new AdminBatchStatus.BlockedDate(date, prefix + name));
            }
        }
    }

    /**
     * 폴더의 <b>배치 결과</b> 파일 이름 목록. 폴더가 없으면 빈 목록 — 배포 환경이 그렇다(클래스 주석).
     *
     * <p>{@code _}로 시작하는 파일은 뺀다. 스냅샷 세 종({@code _existing-questions.json} 등)은
     * 배치가 <b>읽는</b> 보조 파일이지 만든 결과가 아니라, 들여오기 이력에도 영원히 안 남는다.
     * 거르지 않으면 이 셋이 매번 "아직 안 들어온 파일"로 떠서 목록이 늘 빨갛다 —
     * <b>늘 켜져 있는 경보는 없는 것과 같다.</b> 규칙은 {@code DraftImportRunner}의 것과 같아야 한다.
     */
    private List<String> collectJson(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".json"))
                    .filter(name -> !name.startsWith("_"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.warn("생성 결과 폴더를 읽지 못했습니다: {} — {}", dir, e.getMessage());
            return List.of();
        }
    }
}
