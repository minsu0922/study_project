package project.study.study_project.admin.dto;

import java.time.LocalDate;

/**
 * 이용 추이 머리의 요약 카드 — 고른 기간과 그 바로 앞 같은 길이 기간을 나란히 준다.
 *
 * <p>날짜별 막대만으로는 "많은지 적은지"를 판단할 기준이 없다. 직전 기간이 그 기준이다.
 */
public record AdminUsageSummary(Period current, Period previous) {

    /**
     * @param activeUsers 기간 안에 한 번이라도 제출한 사람 수(중복 없이). 날짜별 값을 더한 것과 다르다
     * @param correct     기간 안 제출 중 정답 수 — 정답률 계산용
     */
    public record Period(LocalDate from, LocalDate to, long signups, long activeUsers,
                         long submissions, long correct) {
    }
}
