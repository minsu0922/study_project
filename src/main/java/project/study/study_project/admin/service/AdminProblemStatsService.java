package project.study.study_project.admin.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.admin.dto.AdminProblemStatItem;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.DomainCode;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.global.common.SearchKeyword;
import project.study.study_project.global.response.PageResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 학습 현황의 문제별 정답률 — 검색 조건과 쪽 단위로 SQL에서 거른다.
 *
 * <p>제출이 0건인 문제도 보여야 하므로 problem 기준 LEFT JOIN이다. INNER JOIN이면 아무도 안 푼 문제가 사라진다.
 *
 * <p>정답률 구간·최소 제출 수는 집계 뒤의 값이라 WHERE가 아니라 HAVING에 건다.
 * 조건 조합이 많아 JPQL 고정 쿼리 대신 조건 조각을 붙인다. 값은 전부 바인딩 파라미터로만 넣는다.
 */
@Service
@RequiredArgsConstructor
public class AdminProblemStatsService {

    /** 정답률 구간. 경계는 화면 안내문(40%·80%)과 같아야 한다. */
    public enum RateBand { NO_DATA, LOW, MID, HIGH }

    /** 정렬. 기록 없는 문제는 정답률 정렬에서 늘 맨 아래다. */
    public enum Sort { RATE_ASC, RATE_DESC, ATTEMPTS_DESC, NEWEST }

    public record Filter(String q, DomainCode domain, Difficulty difficulty, ProblemType type,
                         RateBand band, Integer minAttempts, Boolean hidden, Sort sort) {
    }

    private static final int MAX_SIZE = 100;

    private final JdbcClient jdbcClient;

    @Transactional(readOnly = true)
    public PageResponse<AdminProblemStatItem> search(Filter f, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_SIZE);
        int safePage = Math.max(page, 0);

        Map<String, Object> params = new HashMap<>();
        List<String> where = new ArrayList<>();
        if (f.domain() != null) {
            where.add("p.domain = :domain");
            params.put("domain", f.domain().value());
        }
        if (f.difficulty() != null) {
            where.add("p.difficulty = :difficulty");
            params.put("difficulty", f.difficulty().name());
        }
        if (f.type() != null) {
            where.add("p.type = :type");
            params.put("type", f.type().name());
        }
        if (f.hidden() != null) {
            where.add("p.hidden = :hidden");
            params.put("hidden", f.hidden());
        }
        String keyword = SearchKeyword.likePattern(f.q());
        if (keyword != null) {
            where.add("(LOWER(p.title) LIKE LOWER(:kw) ESCAPE '!' OR LOWER(p.question) LIKE LOWER(:kw) ESCAPE '!')");
            params.put("kw", keyword);
        }

        List<String> having = new ArrayList<>();
        if (f.minAttempts() != null && f.minAttempts() > 0) {
            having.add("COUNT(s.id) >= :minAttempts");
            params.put("minAttempts", f.minAttempts());
        }
        if (f.band() != null) {
            // 나눗셈 대신 곱셈으로 비교한다. 반올림 없이 경계가 정확히 갈린다
            having.add(switch (f.band()) {
                case NO_DATA -> "COUNT(s.id) = 0";
                case LOW -> "COUNT(s.id) > 0 AND COALESCE(SUM(s.is_correct), 0) * 100 < 40 * COUNT(s.id)";
                case MID -> "COUNT(s.id) > 0 AND COALESCE(SUM(s.is_correct), 0) * 100 >= 40 * COUNT(s.id)"
                        + " AND COALESCE(SUM(s.is_correct), 0) * 100 < 80 * COUNT(s.id)";
                case HIGH -> "COUNT(s.id) > 0 AND COALESCE(SUM(s.is_correct), 0) * 100 >= 80 * COUNT(s.id)";
            });
        }

        String grouped = """
                SELECT p.id AS problemId, p.domain, p.difficulty, p.type, p.title, p.question, p.hidden,
                       COUNT(s.id) AS attempts, COALESCE(SUM(s.is_correct), 0) AS correctCount
                FROM problem p
                LEFT JOIN submission s ON s.problem_id = p.id
                """
                + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where))
                + " GROUP BY p.id, p.domain, p.difficulty, p.type, p.title, p.question, p.hidden"
                + (having.isEmpty() ? "" : " HAVING " + String.join(" AND ", having));

        long total = jdbcClient.sql("SELECT COUNT(*) FROM (" + grouped + ") t")
                .params(params).query(Long.class).single();

        String order = switch (f.sort() == null ? Sort.RATE_ASC : f.sort()) {
            case RATE_ASC -> "attempts = 0, correctCount / attempts ASC, attempts DESC, problemId";
            case RATE_DESC -> "attempts = 0, correctCount / attempts DESC, attempts DESC, problemId";
            case ATTEMPTS_DESC -> "attempts DESC, problemId";
            case NEWEST -> "problemId DESC";
        };
        params.put("limit", safeSize);
        params.put("offset", (long) safePage * safeSize);

        List<AdminProblemStatItem> content = jdbcClient
                .sql(grouped + " ORDER BY " + order + " LIMIT :limit OFFSET :offset")
                .params(params)
                .query((rs, n) -> {
                    long attempts = rs.getLong("attempts");
                    long correct = rs.getLong("correctCount");
                    return new AdminProblemStatItem(
                            rs.getLong("problemId"), rs.getString("domain"), rs.getString("difficulty"),
                            rs.getString("type"), rs.getString("title"), rs.getString("question"),
                            rs.getBoolean("hidden"), attempts, correct,
                            attempts == 0 ? null : (int) Math.round(correct * 100.0 / attempts));
                })
                .list();

        int totalPages = (int) Math.ceil((double) total / safeSize);
        return new PageResponse<>(content, safePage, safeSize, total, totalPages, safePage + 1 < totalPages);
    }
}
