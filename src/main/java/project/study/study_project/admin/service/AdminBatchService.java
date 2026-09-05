package project.study.study_project.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.admin.dto.AdminBatchStatus;
import project.study.study_project.global.common.Domain;
import project.study.study_project.llm.dto.GeneratedDocumentFile;
import project.study.study_project.llm.repository.ImportedDraftFileRepository;
import project.study.study_project.llm.support.BatchCountRule;
import project.study.study_project.llm.support.GenerationSchedule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
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

    private final ImportedDraftFileRepository importedDraftFileRepository;
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
                recentImports(),
                waitingFiles(dir),
                blockedDates(dir, today));
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
