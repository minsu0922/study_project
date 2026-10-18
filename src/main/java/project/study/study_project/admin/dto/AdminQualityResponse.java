package project.study.study_project.admin.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * AI 품질 화면 — 문제 초안이 <b>왜</b> 떨어지고 <b>어디서</b> 약한지.
 * 승인율 합계만으로는 프롬프트의 어느 곳을 고칠지 정해지지 않는다.
 *
 * @param rejectReasons         같은 문장끼리 묶은 거절 사유, 많은 순 상위 몇 개
 * @param otherReasonCount      상위 밖 사유가 붙은 거절 수
 * @param noReasonCount         사유 없이 거절한 수. 사유가 없으면 다음 생성에 되먹임되지 않는다
 * @param aiBuckets             AI 검수 결과로 나눈 초안 묶음별 검수 결과
 * @param findingTypes          지적 종류별로, 그 지적이 붙은 초안의 검수 결과(의견 등급은 뺀다)
 * @param byDomain              분야별 검수 결과
 * @param byDifficulty          난이도별
 * @param byType                유형별
 * @param weekly                최근 주별(월요일 시작). 생성 주 기준이다
 */
public record AdminQualityResponse(
        List<ReasonCount> rejectReasons,
        long otherReasonCount,
        long noReasonCount,
        List<Bucket> aiBuckets,
        List<FindingTypeStat> findingTypes,
        List<AxisStat> byDomain,
        List<AxisStat> byDifficulty,
        List<AxisStat> byType,
        List<WeekStat> weekly
) {

    /**
     * 검수 결과 셋. 승인율은 검수를 마친 것(승인+거절) 중 비율이고, 마친 것이 없으면 null.
     * 대기를 분모에 넣으면 검수를 미루기만 해도 떨어져 품질이 아니라 속도를 재게 된다.
     */
    public record Counts(long pending, long approved, long rejected, Integer approvalRate) {
        public static Counts of(long pending, long approved, long rejected) {
            long reviewed = approved + rejected;
            return new Counts(pending, approved, rejected,
                    reviewed == 0 ? null : (int) Math.round(approved * 100.0 / reviewed));
        }
    }

    public record ReasonCount(String reason, long count) {
    }

    /**
     * @param key NOT_REVIEWED(검수 안 돌림) · CLEAN(걸린 것 없음, 의견만 있는 것 포함) ·
     *            POLISH(다듬을 것만) · DEFECT(결함 의심이 하나라도)
     */
    public record Bucket(String key, Counts counts) {
    }

    public record FindingTypeStat(String type, String label, String grade, Counts counts) {
    }

    public record AxisStat(String key, Counts counts) {
    }

    /** @param models 그 주에 초안을 만든 모델들. 모델이 바뀐 주를 찾는 데 쓴다 */
    public record WeekStat(LocalDate weekStart, Counts counts, List<String> models) {
    }
}
