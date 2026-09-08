package project.study.study_project.admin.dto;

import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 배치 현황 한 덩어리 — {@code GET /api/admin/batch-status}의 응답(2026-09-01 신설).
 *
 * <h2>이 화면이 답하는 질문</h2>
 *
 * <p>이 프로젝트가 실제로 두 번 당한 사고가 있다. <b>"왜 요즘 문제가 안 들어오지?"</b>를 몇 주 뒤에야
 * 알아차린 것이다(docs/14). 원인은 그때그때 달랐다 — 배치가 꺼져 있었고, 앱을 안 켜서 파일이 쌓여만
 * 있었고, 사람이 손으로 채운 날짜와 예약 실행의 파일명이 겹쳐 그날이 조용히 죽었다.
 * 셋 다 <b>어딘가에는 적혀 있었지만 한 화면에 모여 있지 않았다</b>. 설정 파일·워크플로·
 * {@code generated/} 폴더·DB 이력 넷을 사람이 머릿속에서 맞춰 봐야 알 수 있었다.
 *
 * <h2>왜 조작 버튼이 없나</h2>
 *
 * <p>배치는 GitHub Actions에서 돌고 스위치({@code batch-enabled})는 {@code application.yml}에 있다.
 * 앱은 그것을 켤 수도, 즉시 실행할 수도 없다. 있는 척하는 버튼을 두면 눌러 놓고 안 돌아간 이유를
 * 다시 찾게 되므로 <b>읽기 전용</b>으로 못 박는다. 배치를 켜고 끄는 것이 커밋으로 남아야 하는
 * 결정이라는 판단은 {@code AdminStatsService} 주석에 이미 있다 — 그 판단을 화면이 뒤집지 않는다.
 *
 * <h2>2026-09-08 — 설정만 답하고 결과는 못 답했다</h2>
 *
 * <p>처음 판은 <b>"오늘 무엇이 나올 차례인가"</b>는 잘 답했는데 <b>"그래서 잘 돌고 있나"</b>는
 * 답하지 못했다. 며칠부터 빠졌는지 알려면 "안 들어온 파일"·"막힌 주기"·"최근 들여오기"
 * 세 표를 사람이 머릿속에서 날짜로 맞춰 봐야 했다 — 이 화면이 없애려던 바로 그 수고다.
 * 그래서 셋을 하루 한 칸의 {@link DayCell 달력}으로 합치고, 만든 것이 쓸 만했는지를
 * {@link Harvest 수확 집계}로 따로 답한다.
 *
 * @param enabled        배치 스위치. 꺼져 있으면 아래 주기가 계산돼도 실제로는 아무것도 안 만든다
 * @param batchType      auto | problem | document — 문서일에도 문제를 만들지 등을 정한다
 * @param count          한 번에 만드는 문제 수
 * @param today          이 응답을 계산한 기준 날짜(한국 날짜)
 * @param plan           오늘의 주기 — 며칠차이고 무엇이 나올 차례인가
 * @param calendar       지난 4주기 + 앞으로 1주기의 하루하루(과거→미래 순, 주기 경계에 정렬)
 * @param harvest        최근 30일 수확 — 만든 초안이 승인까지 갔는지
 * @param pendingProblems 지금 검수를 기다리는 문제 초안 수(기간 무관 = 실제 할 일의 양)
 * @param pendingDocuments 지금 검수를 기다리는 문서 초안 수
 * @param recentImports  최근 들여오기 이력(최신 순)
 * @param waitingFiles   {@code generated/}에 있는데 아직 안 읽힌 파일 — 앱을 안 켠 동안 쌓인 것
 * @param blockedDates   결과 파일이 이미 있어 <b>예약 실행이 건너뛸</b> 앞으로의 날짜
 */
public record AdminBatchStatus(
        boolean enabled,
        String batchType,
        int count,
        LocalDate today,
        TodayPlan plan,
        List<DayCell> calendar,
        Harvest harvest,
        long pendingProblems,
        long pendingDocuments,
        List<ImportRecord> recentImports,
        List<String> waitingFiles,
        List<BlockedDate> blockedDates
) {

    /**
     * 오늘 무엇이 나올 차례인가 — {@code GenerationSchedule.planFor}의 결과를 화면 말로 옮긴 것.
     *
     * @param dayInCycle   주기 안에서 며칠째(0=문서일, 1·2·3=초·중·고급 문제일)
     * @param documentDay  오늘이 문서일인가
     * @param domain       <b>실제로 나올 분야.</b> 근거 문서가 있으면 그 문서의 분야다
     * @param cycleDomain  날짜 주기가 계산한 분야. {@code domain}과 다르면 화면이 둘 다 보여 준다
     * @param difficulty   문제일의 난이도. 문서일이면 {@code null}
     * @param documentDate 근거로 삼을 문서의 날짜(= 이번 주기 0일차)
     * @param documentSlug 그 문서의 slug. <b>파일이 없으면 {@code null}</b>이고, 그때 배치는
     *                     근거 없이 모델 지식으로 만든다(폴백). 이 칸이 비어 있는 것이
     *                     "문서 기반이 헛돌고 있다"는 가장 빠른 신호다
     */
    public record TodayPlan(
            int dayInCycle,
            boolean documentDay,
            Domain domain,
            Domain cycleDomain,
            Difficulty difficulty,
            LocalDate documentDate,
            String documentSlug
    ) {
        // 둘이 다른지는 화면이 두 값을 비교해 판단한다. 여기에 boolean 메서드를 두면
        // 레코드 컴포넌트가 아니라 JSON에 실리지 않아, 있는 줄 알고 쓴 화면이 조용히 틀린다.
        //
        // 왜 이 칸이 필요한가 — 만들자마자 실물에서 걸렸다. 2026-09-01 기준 주기는 언어·런타임을
        // 가리키는데 근거 문서는 네트워크 문서였다. 배치는 문서 쪽으로 맞추므로
        // (DraftGeneratorCli.alignDomainWithDocument) 실제로는 네트워크 문제가 나온다.
        // 화면이 주기 분야만 보여 주면 <화면이 말하는 것과 실제로 나오는 것이 다른>,
        // 이 화면이 없애려던 바로 그 종류의 어긋남이 된다.
    }

    /**
     * 달력 한 칸의 상태 — <b>그날 몫이 어디까지 왔나</b>.
     *
     * <p>다섯으로 나눈 기준은 "사람이 할 일이 다른가"다. {@code WAITING}은 앱을 켜면 끝나고,
     * {@code MISSING}은 손으로 돌릴지 판단해야 하며, 나머지 셋은 할 일이 없다.
     * 색이나 아이콘은 화면이 정한다 — 여기서 정하면 뜻과 표현이 한 덩이가 되어
     * 화면을 바꿀 때마다 서버를 고쳐야 한다.
     */
    public enum DayState {
        /** 결과 파일이 있고 DB에도 들어왔다 — 정상적으로 끝난 날. */
        IMPORTED,
        /** 파일은 있는데 아직 안 들어왔다 — 앱을 켜면 들어온다. */
        WAITING,
        /** 지나간 날인데 파일이 없다 — 그날 몫이 나오지 않았다. */
        MISSING,
        /** 앞으로의 날인데 파일이 이미 있다 — 미리 만들어 둔 몫이라 그날 실행은 건너뛴다. */
        PRESET,
        /** 앞으로의 날이고 파일도 없다 — 예정대로다. */
        PLANNED
    }

    /**
     * 달력 한 칸 = 하루.
     *
     * <p><b>왜 주기 경계에 맞춰 4칸씩 끊는가.</b> 흔한 달력처럼 7칸으로 끊으면 요일이 보이는데,
     * 이 배치에 요일은 아무 뜻이 없다 — 매일 같은 시각에 돌고 주말도 쉬지 않는다. 뜻이 있는
     * 것은 <b>4일 주기</b>다. 한 줄이 곧 한 주기(문서 → 초급 → 중급 → 고급)면 "문서는 나왔는데
     * 문제가 한 건도 안 붙은 주기"가 줄 하나로 눈에 들어온다. 그게 이 배치의 최악의 상태다.
     *
     * @param filename   그날 예약 실행이 쓸(썼을) 결과 파일 이름. 문서일에는 {@code documents/} 접두가 붙는다
     * @param draftCount 들어온 초안 수. {@code IMPORTED}가 아니면 {@code null}
     * @param fallback   문제일인데 근거 문서 파일이 없다 = 그날은 근거 없이(폴백) 만든다.
     *                   문서일에는 항상 {@code false}
     */
    public record DayCell(
            LocalDate date,
            int dayInCycle,
            boolean documentDay,
            Domain domain,
            Difficulty difficulty,
            DayState state,
            String filename,
            Integer draftCount,
            boolean fallback
    ) {
    }

    /**
     * 최근 N일의 수확 — <b>만든 것이 쓸 만했나</b>.
     *
     * <p>들여오기 이력은 "몇 건 들어왔나"까지만 답한다. 그런데 열 건이 들어와도 아홉이 거절되면
     * 배치는 돈만 쓰고 아무것도 못 늘린 것이다. 그 구분이 화면 어디에도 없었다.
     *
     * <p>비율을 서버에서 계산하지 않는 이유는 {@code countGroupByModelAndStatus}의 판단과 같다 —
     * 검수한 게 0건일 때 "0%"와 "아직 안 봄"이 구분되지 않는다. 개수만 주고 화면이 판단한다.
     *
     * <p>문서 초안은 세지 않는다. 4일에 한 편이라 30일에 7~8건이고, 문제(하루 3~7건)와 합치면
     * 문서 쪽 변화가 숫자에 묻힌다(문서·문제 승인율을 나눠 둔 것과 같은 이유).
     *
     * @param days      집계 기간(일)
     * @param generated 그 기간에 만들어진 문제 초안 수 = 승인 + 거절 + 대기
     */
    public record Harvest(int days, long generated, long approved, long rejected, long pending) {
    }

    /**
     * 파일 하나가 언제 몇 건으로 들어왔는지.
     *
     * @param draftCount 저장된 초안 수. <b>0건도 기록으로 남는다</b> — 규약을 어겨 전부 버려진
     *                   파일을 매 부팅마다 다시 읽지 않기 위해서다({@code ImportedDraftFile} 주석)
     */
    public record ImportRecord(String filename, LocalDateTime importedAt, int draftCount) {
    }

    /**
     * 예약 실행이 죽은 날짜와 그 원인이 된 파일.
     *
     * <p><b>왜 이걸 세어 주나.</b> 배치는 결과 파일이 이미 있으면 요금을 아끼려고 건너뛴다(멱등).
     * 두 번 눌렀을 때는 옳은 동작인데, 사람이 그 날짜 이름으로 손수 채워 둔 경우에도 똑같이
     * 침묵한다 — 그래서 예약 실행이 며칠씩 아무것도 안 하는 것을 아무도 몰랐다(2026-08-29).
     * 지금 그 사실은 {@code generated/} 파일명을 사람이 세어 봐야 알 수 있다. 화면이 대신 센다.
     *
     * <p>고치는 방법은 없다. 파일명이 곧 들여오기 이력의 도장이라 지워도 되살아나지 않는다.
     * 그래서 이 목록은 "고쳐라"가 아니라 <b>"그날은 안 나온다는 것을 알고 있으라"</b>는 뜻이다.
     */
    public record BlockedDate(LocalDate date, String filename) {
    }
}
