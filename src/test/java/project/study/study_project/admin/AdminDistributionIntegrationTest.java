package project.study.study_project.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.TestDomains;
import project.study.study_project.TestFixtures;
import project.study.study_project.admin.dto.AdminDistributionResponse;
import project.study.study_project.admin.dto.AdminDistributionResponse.Cell;
import project.study.study_project.admin.dto.AdminDistributionResponse.DocumentCount;
import project.study.study_project.admin.service.AdminDistributionService;
import project.study.study_project.document.dto.AdminDocumentRequest;
import project.study.study_project.document.service.AdminDocumentService;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.user.domain.Role;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 출제 분포 — 공개 기준으로 세는지. DB 전체를 세므로 전후 차이로 확인한다. */
@SpringBootTest(properties = "ratelimit.enabled=false")
@AutoConfigureMockMvc
@Transactional
class AdminDistributionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private AdminDistributionService distributionService;
    @Autowired
    private AdminDocumentService adminDocumentService;
    @Autowired
    private ProblemRepository problemRepository;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    @Test
    @DisplayName("칸·유형은 공개 문제만 세고 내린 문제는 따로 센다")
    void countsVisibleAndHiddenSeparately() {
        AdminDistributionResponse before = distributionService.summary();

        problem(ProblemType.OX, null, false);
        problem(ProblemType.OX, null, false);
        problem(ProblemType.OX, null, true);
        AdminDistributionResponse after = distributionService.summary();

        Cell b = cell(before), a = cell(after);
        assertThat(a.visible() - b.visible()).isEqualTo(2);
        assertThat(a.hidden() - b.hidden()).isEqualTo(1);
        assertThat(a.recent() - b.recent()).as("오늘 등록한 공개 문제").isEqualTo(2);
        assertThat(typeCount(after) - typeCount(before)).as("내린 문제는 유형 분포에서 뺀다").isEqualTo(2);
        assertThat(after.noDocumentProblems() - before.noDocumentProblems()).isEqualTo(2);
    }

    @Test
    @DisplayName("문서마다 문제 수를 세고, 문제 없는 문서가 먼저 오며, 사라진 문서를 가리키는 문제를 센다")
    void countsProblemsPerDocument() {
        String used = "dist-used-" + suffix;
        String unused = "dist-unused-" + suffix;
        adminDocumentService.create(doc(used));
        adminDocumentService.create(doc(unused));
        long missingBefore = distributionService.summary().missingDocumentProblems();

        problem(ProblemType.OX, used, false);
        problem(ProblemType.MULTIPLE_CHOICE, used, false);
        problem(ProblemType.OX, used, true);
        problem(ProblemType.OX, "ghost-" + suffix, false);

        AdminDistributionResponse after = distributionService.summary();
        List<String> order = after.documents().stream().map(DocumentCount::slug).toList();
        DocumentCount usedRow = after.documents().stream().filter(d -> d.slug().equals(used)).findFirst().orElseThrow();

        assertThat(usedRow.visible()).isEqualTo(2);
        assertThat(usedRow.hidden()).isEqualTo(1);
        assertThat(order.indexOf(unused)).isLessThan(order.indexOf(used));
        assertThat(after.missingDocumentProblems() - missingBefore).isEqualTo(1);
    }

    @Test
    @DisplayName("API는 칸·유형·문서와 빈 칸 수를 준다")
    void api() throws Exception {
        mockMvc.perform(get("/api/admin/distribution").header("Authorization", fixtures.bearer(Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recentDays").value(30))
                .andExpect(jsonPath("$.data.cells").isArray())
                .andExpect(jsonPath("$.data.plannedCells").isNumber());
    }

    /* ── 도우미 ── */

    private void problem(ProblemType type, String slug, boolean hidden) {
        Problem p = Problem.create(TestDomains.NETWORK, Difficulty.BEGINNER, type,
                "분포 " + suffix, "지문", "O", "해설", slug);
        if (hidden) {
            p.hide();
        }
        // 집계는 JdbcClient라 JPA 쓰기 지연을 못 본다. 바로 내보낸다
        problemRepository.saveAndFlush(p);
    }

    private static Cell cell(AdminDistributionResponse r) {
        return r.cells().stream()
                .filter(c -> c.domain().equals(TestDomains.NETWORK.value()) && c.difficulty().equals("BEGINNER"))
                .findFirst().orElse(new Cell("", "", 0, 0, 0));
    }

    private static long typeCount(AdminDistributionResponse r) {
        return r.types().stream()
                .filter(t -> t.domain().equals(TestDomains.NETWORK.value()) && t.type().equals("OX"))
                .mapToLong(AdminDistributionResponse.TypeCount::count).sum();
    }

    private static AdminDocumentRequest doc(String slug) {
        return new AdminDocumentRequest(TestDomains.NETWORK, "분포 테스트 문서", slug, "본문이다.", null, List.of("dist"));
    }
}
