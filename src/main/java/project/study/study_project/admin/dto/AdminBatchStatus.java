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
 * @param enabled       배치 스위치. 꺼져 있으면 아래 주기가 계산돼도 실제로는 아무것도 안 만든다
 * @param batchType     auto | problem | document — 문서일에도 문제를 만들지 등을 정한다
 * @param count         한 번에 만드는 문제 수
 * @param today         이 응답을 계산한 기준 날짜(한국 날짜)
 * @param plan          오늘의 주기 — 며칠차이고 무엇이 나올 차례인가
 * @param recentImports 최근 들여오기 이력(최신 순)
 * @param waitingFiles  {@code generated/}에 있는데 아직 안 읽힌 파일 — 앱을 안 켠 동안 쌓인 것
 * @param blockedDates  결과 파일이 이미 있어 <b>예약 실행이 건너뛸</b> 앞으로의 날짜
 */
public record AdminBatchStatus(
        boolean enabled,
        String batchType,
        int count,
        LocalDate today,
        TodayPlan plan,
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
