package project.study.study_project.admin.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.admin.dto.AdminUsageDay;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 날짜별 이용 추이 — 가입·푼 사람·제출.
 *
 * <p>대시보드의 누적 합계는 "지금 얼마"만 말한다. 늘고 있는지 줄고 있는지는 날짜로 펴야 보인다.
 *
 * <p>제출을 날짜로만 거르는 조회라 사용자 선두 인덱스({@code idx_submission_user_correct})를
 * 타지 못하고 기간 안의 행을 다 읽는다. 관리자 한 명이 대시보드를 열 때 한 번 도는 조회라
 * 지금 규모에서는 그대로 둔다. 느려지면 재 보고 {@code submitted_at} 인덱스를 건다(docs/08의 규칙).
 */
@Service
@RequiredArgsConstructor
public class AdminUsageService {

    public static final int DEFAULT_DAYS = 30;
    private static final int MAX_DAYS = 90;

    private final JdbcClient jdbcClient;

    /** 오늘을 포함한 최근 {@code days}일. 아무 일도 없던 날도 0으로 채워 준다. */
    @Transactional(readOnly = true)
    public List<AdminUsageDay> trend(int days) {
        int span = Math.min(Math.max(days, 1), MAX_DAYS);
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(span - 1L);
        LocalDateTime fromTime = from.atStartOfDay();

        Map<LocalDate, Long> signups = new HashMap<>();
        jdbcClient.sql("""
                        SELECT DATE(created_at) AS d, COUNT(*) AS cnt
                        FROM `user` WHERE created_at >= :from
                        GROUP BY DATE(created_at)
                        """)
                .param("from", fromTime)
                .query((rs, n) -> signups.put(rs.getDate("d").toLocalDate(), rs.getLong("cnt")))
                .list();

        Map<LocalDate, long[]> activity = new HashMap<>();
        jdbcClient.sql("""
                        SELECT DATE(submitted_at) AS d, COUNT(DISTINCT user_id) AS users, COUNT(*) AS cnt
                        FROM submission WHERE submitted_at >= :from
                        GROUP BY DATE(submitted_at)
                        """)
                .param("from", fromTime)
                .query((rs, n) -> activity.put(rs.getDate("d").toLocalDate(),
                        new long[]{rs.getLong("users"), rs.getLong("cnt")}))
                .list();

        List<AdminUsageDay> result = new ArrayList<>(span);
        for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) {
            long[] a = activity.getOrDefault(d, new long[]{0, 0});
            result.add(new AdminUsageDay(d, signups.getOrDefault(d, 0L), a[0], a[1]));
        }
        return result;
    }
}
