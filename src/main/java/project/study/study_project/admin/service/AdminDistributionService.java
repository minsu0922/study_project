package project.study.study_project.admin.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.admin.dto.AdminDistributionResponse;
import project.study.study_project.admin.dto.AdminDistributionResponse.Cell;
import project.study.study_project.admin.dto.AdminDistributionResponse.DocumentCount;
import project.study.study_project.admin.dto.AdminDistributionResponse.TypeCount;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.DomainCode;
import project.study.study_project.llm.service.DomainSettingService;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 출제 분포 — 어디가 비었고, 무엇에 쏠렸고, 어느 문서가 문제 없이 남았나.
 *
 * <p>빈 칸은 공개 문제로 센다. 내린 문제로 채워진 칸은 학습자에게는 빈 칸이다.
 * 대시보드의 옛 빈 칸 수는 내린 문제까지 셌다.
 */
@Service
@RequiredArgsConstructor
public class AdminDistributionService {

    static final int RECENT_DAYS = 30;

    private final JdbcClient jdbcClient;
    private final DomainSettingService domainSettingService;

    @Transactional(readOnly = true)
    public AdminDistributionResponse summary() {
        List<Cell> cells = jdbcClient.sql("""
                        SELECT domain, difficulty,
                               SUM(hidden = 0) AS visible, SUM(hidden = 1) AS hid,
                               SUM(hidden = 0 AND created_at >= :from) AS recent
                        FROM problem GROUP BY domain, difficulty
                        """)
                .param("from", LocalDate.now().minusDays(RECENT_DAYS - 1L).atStartOfDay())
                .query((rs, n) -> new Cell(rs.getString("domain"), rs.getString("difficulty"),
                        rs.getLong("visible"), rs.getLong("hid"), rs.getLong("recent")))
                .list();

        List<TypeCount> types = jdbcClient.sql("""
                        SELECT domain, type, COUNT(*) AS cnt FROM problem WHERE hidden = 0
                        GROUP BY domain, type
                        """)
                .query((rs, n) -> new TypeCount(rs.getString("domain"), rs.getString("type"), rs.getLong("cnt")))
                .list();

        List<DocumentCount> documents = jdbcClient.sql("""
                        SELECT d.slug, d.title, d.domain, d.hidden,
                               COALESCE(SUM(p.hidden = 0), 0) AS visible, COALESCE(SUM(p.hidden = 1), 0) AS hid
                        FROM document d LEFT JOIN problem p ON p.document_slug = d.slug
                        GROUP BY d.id, d.slug, d.title, d.domain, d.hidden
                        ORDER BY visible, d.domain, d.title
                        """)
                .query((rs, n) -> new DocumentCount(rs.getString("slug"), rs.getString("title"),
                        rs.getString("domain"), rs.getBoolean("hidden"), rs.getLong("visible"), rs.getLong("hid")))
                .list();

        long noDocument = jdbcClient.sql(
                        "SELECT COUNT(*) FROM problem WHERE hidden = 0 AND document_slug IS NULL")
                .query(Long.class).single();
        long missingDocument = jdbcClient.sql("""
                        SELECT COUNT(*) FROM problem p WHERE p.hidden = 0 AND p.document_slug IS NOT NULL
                          AND NOT EXISTS (SELECT 1 FROM document d WHERE d.slug = p.document_slug)
                        """)
                .query(Long.class).single();

        // 빈 칸은 조회마다 다시 센다. 관리자가 분야 설정을 바꾸면 바로 반영돼야 한다
        List<String> batchDomains = domainSettingService.batchDomains().stream().map(DomainCode::value).toList();
        Set<String> filled = cells.stream().filter(c -> c.visible() > 0)
                .map(c -> c.domain() + "|" + c.difficulty()).collect(Collectors.toSet());
        int planned = batchDomains.size() * Difficulty.values().length;
        int empty = (int) batchDomains.stream()
                .flatMap(d -> java.util.Arrays.stream(Difficulty.values()).map(lv -> d + "|" + lv.name()))
                .filter(key -> !filled.contains(key))
                .count();

        return new AdminDistributionResponse(batchDomains, planned, empty, RECENT_DAYS,
                cells, types, documents, noDocument, missingDocument);
    }
}
