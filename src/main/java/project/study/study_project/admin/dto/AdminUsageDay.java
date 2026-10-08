package project.study.study_project.admin.dto;

import java.time.LocalDate;

/**
 * 이용 추이의 하루.
 *
 * @param signups     그날 가입한 계정 수. 탈퇴한 계정은 행이 지워져 세어지지 않는다
 * @param activeUsers 그날 한 문제라도 제출한 사람 수
 * @param submissions 그날의 제출 수
 */
public record AdminUsageDay(LocalDate date, long signups, long activeUsers, long submissions) {
}
