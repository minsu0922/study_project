package project.study.study_project.llm.cli;

import project.study.study_project.global.common.DomainCode;
import project.study.study_project.llm.client.GeneratedProblemItem;
import project.study.study_project.llm.client.RejectionNote;
import project.study.study_project.llm.dto.GeneratedBatchFile;
import project.study.study_project.llm.dto.RejectionNotesFile;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 지난 기록 읽기. 중복 회피 목록, 거절 사례, 스냅샷이 낡았는지 본다. 클라우드에 DB가 없어 스냅샷 파일로 대신한다.
 *
 * <p>{@link DraftGeneratorCli}에서 역할별로 떼어 냈다(2026-09-29). 동작은 그대로다.
 */
final class BatchHistory {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BatchHistory() {
    }

    /** 중복 회피 목록에 넣을 지문 수 상한 — 프롬프트 입력 토큰과의 균형점(서비스의 값과 동일). */
    static final int AVOID_LIST_SIZE = 50;

    /** 정식 문제 지문을 내보내 둔 파일. 클라우드에는 DB가 없어 이 스냅샷으로 대신한다. */
    static final String EXISTING_QUESTIONS_FILE = "_existing-questions.json";

    /** 검수자의 거절 사례 스냅샷. 로컬 앱(RejectionNotesExporter)이 쓰고 여기서 읽는다. */
    static final String REJECTION_NOTES_FILE = "_rejection-notes.json";

    /* ── 중복 회피 목록 ───────────────────────────────────────── */

    /**
     * 같은 분야의 기존 지문을 모은다 — 정식 문제 스냅샷 + 이전에 생성된 배치 파일들.
     *
     * <p>DB를 볼 수 있던 시절에는 problem 테이블과 PENDING 초안을 직접 조회했다. 클라우드에는
     * DB가 없으므로 <b>저장소에 있는 것</b>으로 대신한다. 정확도가 약간 떨어지는 대목은
     * "승인/거절 결과"를 모른다는 점인데, 어차피 거절된 문제도 다시 만들면 안 되는 것이므로
     * 생성된 전부를 회피 목록에 넣는 편이 오히려 안전하다.
     *
     * <p>최신 것부터 {@link #AVOID_LIST_SIZE}개까지만 넣는다 — 파일이 쌓일수록 프롬프트가
     * 무한정 길어지면 입력 토큰 비용이 계속 오르기 때문. 파일명이 날짜라 이름 역순 정렬이
     * 곧 최신순이다.
     *
     * <p><b>왜 {@code private}이 아니라 패키지 전용인가</b>(최종 리뷰 Important 2). 이 메서드의
     * 분야 비교가 {@code ==}라 이전 날짜 파일에서 <b>한 문제도 못 모으고</b> 있었는데,
     * {@code private static}이라 테스트가 부를 길이 없어 아무도 못 잡았다. 같은 패키지의
     * {@code DraftGeneratorCliTest}가 직접 부를 수 있게 열어 둔다 — 이 저장소가 프롬프트 조립
     * 테스트에서 이미 쓰는 방식이다. 검사할 수 없는 코드는 <b>검사하지 않는 코드</b>가 된다.
     */
    static List<String> buildAvoidList(Path outDir, DomainCode domain) throws Exception {
        List<String> avoid = new ArrayList<>();

        // (1) 이전에 생성된 배치 파일 — 최신 날짜부터
        if (Files.isDirectory(outDir)) {
            List<Path> batchFiles;
            try (var stream = Files.list(outDir)) {
                batchFiles = stream
                        .filter(p -> p.getFileName().toString().endsWith(".json"))
                        .filter(p -> !p.getFileName().toString().startsWith("_")) // 스냅샷 등 특수 파일 제외
                        .sorted(java.util.Comparator.reverseOrder())
                        .toList();
            }
            for (Path file : batchFiles) {
                if (avoid.size() >= AVOID_LIST_SIZE) {
                    break;
                }
                GeneratedBatchFile batch = MAPPER.readValue(file.toFile(), GeneratedBatchFile.class);
                // Objects.equals다 — 아래 1149행(스냅샷 쪽)과 같은 이유이고, 그 주석이 붙어
                // 있는데도 이 갈래만 ==로 남아 있었다(최종 리뷰 Important 2). batch.domain()은
                // Jackson이 파일에서 만든 인스턴스라 인수로 받은 domain과 같은 물건일 수 없고,
                // ==면 이 continue가 <항상> 걸려 이전 날짜 파일에서 한 문제도 못 모은다.
                // 증상은 조용하다: 회피 목록이 비어 모델이 이미 낸 문제를 다시 내고, 그 요금은
                // 실제로 나간다. 회귀 테스트는 DraftGeneratorCliTest.avoidListMatchesDomainByValue.
                if (!Objects.equals(batch.domain(), domain) || batch.problems() == null) {
                    continue;
                }
                batch.problems().stream().map(GeneratedProblemItem::question).forEach(avoid::add);
            }
        }

        // (2) 정식 문제 스냅샷 — 시드 문제처럼 파일로만 알 수 있는 것들
        Path existing = outDir.resolve(EXISTING_QUESTIONS_FILE);
        if (Files.exists(existing) && avoid.size() < AVOID_LIST_SIZE) {
            ExistingQuestions snapshot = MAPPER.readValue(existing.toFile(), ExistingQuestions.class);
            if (snapshot.questions() != null) {
                snapshot.questions().stream()
                        .filter(q -> Objects.equals(q.domain(), domain)) // == 이면 같은 분야를 하나도 못 거른다
                        .map(ExistingQuestion::question)
                        .limit(AVOID_LIST_SIZE - avoid.size())
                        .forEach(avoid::add);
            }
        }
        return avoid;
    }

    /* ── 거절 사례 되먹이기 ───────────────────────────────────── */

    /**
     * 검수자의 거절 사례 스냅샷을 읽는다({@code generated/_rejection-notes.json}, docs/14).
     *
     * <p>이 파일은 로컬 앱이 내보내고({@code RejectionNotesExporter}) 사용자가 커밋한 것이다.
     * 클라우드에는 DB가 없으니 <b>사람의 검수 판단이 여기까지 오는 유일한 경로</b>다.
     *
     * <p><b>없어도 그냥 진행한다</b>: 거절 이력이 아직 없거나 사용자가 아직 커밋하지 않았을 수 있다.
     * 이건 정상 상황이지 오류가 아니므로, 파일이 없다고 배치를 실패시키면 안 된다
     * (되먹임은 품질 개선 장치이지 생성의 전제 조건이 아니다).
     */
    static List<RejectionNote> readRejectionNotes(Path dir) {
        Path file = dir.resolve(REJECTION_NOTES_FILE);
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            RejectionNotesFile snapshot = MAPPER.readValue(file.toFile(), RejectionNotesFile.class);
            return snapshot.notes() == null ? List.of() : snapshot.notes();
        } catch (Exception e) {
            // 손으로 고치다 깨졌을 수 있다 — 되먹임을 포기할 뿐 생성 자체는 계속한다
            System.out.println("거절 사례 파일을 읽지 못해 건너뜁니다: " + e.getMessage());
            return List.of();
        }
    }

    /* ── 스냅샷 낡음 경고 ─────────────────────────────────────── */

    /**
     * 스냅샷이 이 일수보다 오래되면 경고한다.
     *
     * <p>왜 14일인가. 스냅샷은 <b>내용이 바뀔 때만</b> 갱신되므로, 며칠 그대로인 것은 정상이다
     * (검수를 안 했으면 거절 사례도 안 늘어난다). 반대로 2주 넘게 그대로면 "바뀔 게 없었다"보다
     * "앱을 켜고도 커밋을 안 했다" 또는 "앱 자체를 안 켰다"일 가능성이 훨씬 높다.
     * 너무 짧게 잡으면 매일 경고가 떠서 <b>사람이 경고를 무시하게 된다</b> — 그게 더 나쁘다.
     */
    static final int SNAPSHOT_STALE_DAYS = 14;

    /**
     * 스냅샷 하나가 낡았는지 판정한다.
     *
     * <p><b>읽을 수 없으면 "낡지 않음"으로 본다.</b> 날짜 형식이 이상하거나 필드가 없는 것은
     * 스냅샷이 오래됐다는 증거가 아니다 — 여기서 낡음으로 처리하면 파일이 깨진 날마다
     * 엉뚱한 경고가 뜨고, 진짜 경고까지 같이 무시당한다. 경고는 <b>확실할 때만</b> 울려야 한다.
     *
     * @param exportedAt 스냅샷의 {@code exportedAt}("2026-08-12" 또는 ISO 시각). null·공백 허용
     * @param today      기준 날짜
     */
    static boolean isStaleSnapshot(String exportedAt, LocalDate today) {
        if (exportedAt == null || exportedAt.isBlank() || exportedAt.length() < 10) {
            return false;
        }
        try {
            LocalDate exported = LocalDate.parse(exportedAt.substring(0, 10));
            return exported.isBefore(today.minusDays(SNAPSHOT_STALE_DAYS));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 커밋되지 않아 낡은 스냅샷이 있으면 로그와 <b>Actions 요약 화면</b>에 경고한다.
     *
     * <p><b>왜 여기(클라우드)에서 알리나.</b> 문제는 "로컬에서 스냅샷을 갱신하고도 커밋하지
     * 않는 것"인데, 앱이 그걸 알려면 앱 안에 git을 심어야 한다 — docs/14에서 <b>일부러 피한
     * 방향</b>이다(권한·인증·충돌이 줄줄이 따라온다). 반면 배치는 저장소에 커밋된 파일을
     * 그대로 읽으므로, <b>파일의 {@code exportedAt}이 곧 마지막으로 커밋된 시점</b>이다.
     * git 없이도 정확히 같은 것을 알 수 있고, 게다가 <b>문제가 실제로 터지는 자리</b>에서 알린다.
     *
     * <p>기동 로그에 한 줄 더 얹는 대안을 버린 이유도 같다 — 애초에 놓치고 있는 로그에
     * 한 줄을 더 보태는 것은 해결이 아니다.
     *
     * <p><b>실패시키지 않는다.</b> 낡은 스냅샷은 중복 문제가 나올 수 있다는 뜻이지 생성이
     * 불가능하다는 뜻이 아니다. 여기서 job을 죽이면 "귀찮아서 껐다"로 끝난다.
     */
    static void warnIfSnapshotsAreStale(Path outDir, LocalDate today) {
        List<String> stale = new ArrayList<>();
        for (String name : List.of(EXISTING_QUESTIONS_FILE, REJECTION_NOTES_FILE, DocumentBatch.EXISTING_DOCUMENTS_FILE)) {
            Path file = outDir.resolve(name);
            if (!Files.exists(file)) {
                continue; // 아직 한 번도 안 내보낸 것 — 첫 실행에서는 정상이다
            }
            String exportedAt = readExportedAt(file);
            if (isStaleSnapshot(exportedAt, today)) {
                stale.add("`" + name + "` (" + exportedAt + ")");
            }
        }
        if (stale.isEmpty()) {
            return;
        }

        String message = """
                ⚠️ **스냅샷이 %d일 넘게 그대로입니다** — %s

                %s

                이 파일들은 로컬 앱이 기동할 때 갱신되고, **커밋해야** 다음 배치에 반영됩니다.
                낡은 채로 두면 배치가 옛 목록으로 중복 회피를 하므로 **이미 있는 문제가 또 나올 수 있습니다.**
                → 로컬에서 앱을 한 번 켜고 `git add generated/` 후 커밋하세요.
                """.formatted(SNAPSHOT_STALE_DAYS, today, String.join("\n", stale.stream().map(s -> "- " + s).toList()));

        System.out.println(message);
        BatchReports.appendToStepSummary(message);
    }

    /**
     * 스냅샷 파일에서 {@code exportedAt} 필드만 꺼낸다.
     *
     * <p>파일마다 형태가 다른데({@code questions}/{@code notes}/{@code titles}) 필요한 건 날짜
     * 하나뿐이라, 전용 record로 파싱하는 대신 트리로 읽어 필드 하나만 본다. 이렇게 하면
     * 나중에 스냅샷이 하나 더 늘어도 이 함수는 그대로 쓸 수 있다.
     */
    static String readExportedAt(Path file) {
        try {
            var node = MAPPER.readTree(file.toFile()).get("exportedAt");
            return node == null ? null : node.asText(null);
        } catch (Exception e) {
            return null; // 못 읽으면 판단하지 않는다(위 isStaleSnapshot과 같은 원칙)
        }
    }

    /** {@code generated/_existing-questions.json}의 형태 — 이 CLI만 읽으므로 여기 둔다. */
    record ExistingQuestions(String note, String exportedAt, List<ExistingQuestion> questions) {
    }

    record ExistingQuestion(DomainCode domain, String question) {
    }
}
