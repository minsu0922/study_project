package project.study.study_project.admin.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.admin.dto.AdminQualityResponse;
import project.study.study_project.admin.dto.AdminQualityResponse.AxisStat;
import project.study.study_project.admin.dto.AdminQualityResponse.Bucket;
import project.study.study_project.admin.dto.AdminQualityResponse.Counts;
import project.study.study_project.admin.dto.AdminQualityResponse.FindingTypeStat;
import project.study.study_project.admin.dto.AdminQualityResponse.ReasonCount;
import project.study.study_project.admin.dto.AdminQualityResponse.WeekStat;
import project.study.study_project.llm.client.ProblemReview.FindingType;
import project.study.study_project.llm.client.ProblemReview.Grade;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * AI 품질 화면의 집계. 대상은 문제 초안이다. 문서 초안은 나흘에 한 편이라 표본이 너무 작다.
 *
 * <p>AI 지적은 JSON 칸이라 SQL로 세지 않고 읽어서 센다. 초안이 수천 건이 되기 전까지는 이쪽이 싸다.
 */
@Service
@RequiredArgsConstructor
public class AdminQualityService {

    static final int TOP_REASONS = 8;
    static final int WEEKS = 12;

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public AdminQualityResponse summary() {
        return summary(LocalDate.now());
    }

    private AdminQualityResponse summary(LocalDate today) {
        // ── 거절 사유 ──
        List<ReasonCount> reasons = new ArrayList<>();
        long noReason = 0;
        long other = 0;
        for (ReasonCount r : jdbcClient.sql("""
                        SELECT reject_reason AS reason, COUNT(*) AS cnt FROM generated_problem_draft
                        WHERE status = 'REJECTED' GROUP BY reject_reason ORDER BY cnt DESC, reject_reason
                        """)
                .query((rs, n) -> new ReasonCount(rs.getString("reason"), rs.getLong("cnt"))).list()) {
            if (r.reason() == null || r.reason().isBlank()) {
                noReason += r.count();
            } else if (reasons.size() < TOP_REASONS) {
                reasons.add(r);
            } else {
                other += r.count();
            }
        }

        // ── AI 검수 묶음과 지적 종류 ──
        Map<String, Tally> buckets = new LinkedHashMap<>();
        for (String key : List.of("NOT_REVIEWED", "CLEAN", "POLISH", "DEFECT")) {
            buckets.put(key, new Tally());
        }
        Map<FindingType, Tally> byFinding = new EnumMap<>(FindingType.class);
        jdbcClient.sql("SELECT status, ai_findings_json FROM generated_problem_draft")
                .query((rs, n) -> {
                    String status = rs.getString("status");
                    String json = rs.getString("ai_findings_json");
                    if (json == null) {
                        buckets.get("NOT_REVIEWED").add(status, 1);
                        return null;
                    }
                    Set<FindingType> types = readTypes(json);
                    boolean defect = types.stream().anyMatch(t -> t.grade() == Grade.DEFECT);
                    boolean polish = types.stream().anyMatch(t -> t.grade() == Grade.POLISH);
                    buckets.get(defect ? "DEFECT" : polish ? "POLISH" : "CLEAN").add(status, 1);
                    // 의견(총평·풀이 등)은 거의 모든 초안에 붙어 신호가 아니다
                    types.stream().filter(t -> t.grade() != Grade.OPINION)
                            .forEach(t -> byFinding.computeIfAbsent(t, k -> new Tally()).add(status, 1));
                    return null;
                })
                .list();

        List<FindingTypeStat> findingTypes = byFinding.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().total(), a.getValue().total()))
                .map(e -> new FindingTypeStat(e.getKey().name(), e.getKey().label(),
                        e.getKey().grade().name(), e.getValue().counts()))
                .toList();

        // ── 분야·난이도·유형 ──
        Map<String, Tally> domain = new TreeMap<>();
        Map<String, Tally> difficulty = new LinkedHashMap<>();
        Map<String, Tally> type = new TreeMap<>();
        for (String d : List.of("BEGINNER", "INTERMEDIATE", "ADVANCED")) {
            difficulty.put(d, new Tally());
        }
        jdbcClient.sql("""
                        SELECT domain, difficulty, type, status, COUNT(*) AS cnt FROM generated_problem_draft
                        GROUP BY domain, difficulty, type, status
                        """)
                .query((rs, n) -> {
                    String status = rs.getString("status");
                    long cnt = rs.getLong("cnt");
                    domain.computeIfAbsent(rs.getString("domain"), k -> new Tally()).add(status, cnt);
                    difficulty.computeIfAbsent(rs.getString("difficulty"), k -> new Tally()).add(status, cnt);
                    type.computeIfAbsent(rs.getString("type"), k -> new Tally()).add(status, cnt);
                    return null;
                })
                .list();

        // ── 주별 ──
        LocalDate thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate firstMonday = thisMonday.minusWeeks(WEEKS - 1L);
        Map<LocalDate, Tally> weeks = new TreeMap<>();
        Map<LocalDate, Set<String>> weekModels = new TreeMap<>();
        for (LocalDate w = firstMonday; !w.isAfter(thisMonday); w = w.plusWeeks(1)) {
            weeks.put(w, new Tally());
            weekModels.put(w, new TreeSet<>());
        }
        jdbcClient.sql("""
                        SELECT DATE(created_at) AS d, status, model, COUNT(*) AS cnt FROM generated_problem_draft
                        WHERE created_at >= :from GROUP BY DATE(created_at), status, model
                        """)
                .param("from", firstMonday.atStartOfDay())
                .query((rs, n) -> {
                    LocalDate w = rs.getDate("d").toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                    Tally t = weeks.get(w);
                    if (t != null) {
                        t.add(rs.getString("status"), rs.getLong("cnt"));
                        weekModels.get(w).add(rs.getString("model"));
                    }
                    return null;
                })
                .list();

        return new AdminQualityResponse(
                reasons, other, noReason,
                buckets.entrySet().stream().map(e -> new Bucket(e.getKey(), e.getValue().counts())).toList(),
                findingTypes,
                axis(domain), axis(difficulty), axis(type),
                weeks.entrySet().stream()
                        .map(e -> new WeekStat(e.getKey(), e.getValue().counts(), List.copyOf(weekModels.get(e.getKey()))))
                        .toList());
    }

    /** 모르는 종류(옛 이름·새 이름)는 건너뛴다. 화면 하나 때문에 집계 전체가 깨지면 안 된다. */
    private Set<FindingType> readTypes(String json) {
        Set<FindingType> types = EnumSet.noneOf(FindingType.class);
        try {
            List<Map<String, Object>> list = objectMapper.readValue(json, new TypeReference<>() {
            });
            for (Map<String, Object> f : list) {
                try {
                    types.add(FindingType.valueOf(String.valueOf(f.get("type"))));
                } catch (IllegalArgumentException ignored) {
                    // 모르는 종류
                }
            }
        } catch (Exception ignored) {
            // 깨진 JSON은 "걸린 것 없음"으로 센다
        }
        return types;
    }

    private static List<AxisStat> axis(Map<String, Tally> map) {
        return map.entrySet().stream()
                .filter(e -> e.getValue().total() > 0)
                .map(e -> new AxisStat(e.getKey(), e.getValue().counts()))
                .toList();
    }

    private static final class Tally {
        long pending;
        long approved;
        long rejected;

        void add(String status, long n) {
            switch (status) {
                case "APPROVED" -> approved += n;
                case "REJECTED" -> rejected += n;
                default -> pending += n;
            }
        }

        long total() {
            return pending + approved + rejected;
        }

        Counts counts() {
            return Counts.of(pending, approved, rejected);
        }
    }
}
