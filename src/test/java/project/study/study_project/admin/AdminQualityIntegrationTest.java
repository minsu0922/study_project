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
import project.study.study_project.admin.dto.AdminQualityResponse;
import project.study.study_project.admin.dto.AdminQualityResponse.Counts;
import project.study.study_project.admin.service.AdminQualityService;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.llm.domain.GeneratedProblemDraft;
import project.study.study_project.llm.repository.GeneratedProblemDraftRepository;
import project.study.study_project.user.domain.Role;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 품질 집계. DB 전체를 세므로 다른 테스트 데이터와 섞여도 맞도록 전후 차이로 확인한다.
 */
@SpringBootTest(properties = "ratelimit.enabled=false")
@AutoConfigureMockMvc
@Transactional
class AdminQualityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private AdminQualityService qualityService;
    @Autowired
    private GeneratedProblemDraftRepository draftRepository;

    private static final String LEAK = """
            [{"type":"CHOICE_CUE_LEAK","message":"보기만 봐도 고른다","detail":null}]""";
    private static final String DEFECT = """
            [{"type":"ANSWER_MISMATCH","message":"정답 의심","detail":null},
             {"type":"CHOICE_CUE_LEAK","message":"보기 단서","detail":null}]""";
    private static final String OPINION_ONLY = """
            [{"type":"OVERALL","message":"무난하다","detail":null},{"type":"NOT_A_TYPE","message":"?"}]""";

    @Test
    @DisplayName("AI 지적은 가장 무거운 등급으로 묶고, 의견만 있으면 걸린 것 없음으로 센다")
    void bucketsByWorstFindingGrade() {
        AdminQualityResponse before = qualityService.summary();

        draft(null).approve(1L);          // 검수 안 함
        draft("[]").approve(1L);          // 걸린 것 없음
        draft(OPINION_ONLY);              // 의견뿐 + 모르는 종류 → 걸린 것 없음, 대기
        draft(LEAK).approve(1L);          // 다듬을 것
        draft(DEFECT).reject("정답이 틀렸다");  // 결함 의심
        draftRepository.flush();

        AdminQualityResponse after = qualityService.summary();

        assertDelta(bucket(before, "NOT_REVIEWED"), bucket(after, "NOT_REVIEWED"), 0, 1, 0);
        assertDelta(bucket(before, "CLEAN"), bucket(after, "CLEAN"), 1, 1, 0);
        assertDelta(bucket(before, "POLISH"), bucket(after, "POLISH"), 0, 1, 0);
        assertDelta(bucket(before, "DEFECT"), bucket(after, "DEFECT"), 0, 0, 1);
        // 보기 단서 누설은 다듬을 것 한 건(승인)과 결함 초안 한 건(거절)에 붙었다
        assertDelta(finding(before, "CHOICE_CUE_LEAK"), finding(after, "CHOICE_CUE_LEAK"), 0, 1, 1);
        assertThat(after.findingTypes()).extracting(AdminQualityResponse.FindingTypeStat::type)
                .as("의견 등급은 신호가 아니라 종류 표에서 뺀다").doesNotContain("OVERALL");
    }

    @Test
    @DisplayName("거절 사유는 같은 문장끼리 묶고, 사유 없는 거절은 따로 센다")
    void groupsRejectReasons() {
        AdminQualityResponse before = qualityService.summary();
        String reason = "테스트 사유 " + UUID.randomUUID();

        draft(null).reject(reason);
        draft(null).reject(reason);
        draft(null).reject(null);
        draftRepository.flush();

        AdminQualityResponse after = qualityService.summary();

        assertThat(after.noReasonCount()).isEqualTo(before.noReasonCount() + 1);
        long listed = after.rejectReasons().stream()
                .filter(r -> r.reason().equals(reason)).mapToLong(AdminQualityResponse.ReasonCount::count).sum();
        assertThat(listed + (after.otherReasonCount() - before.otherReasonCount()))
                .as("상위 목록에 2건으로 들거나, 상위 밖이면 '그 외'가 2 늘어난다").isEqualTo(2);
    }

    @Test
    @DisplayName("난이도별·이번 주 집계에 새 초안이 잡히고, API는 12주를 준다")
    void axisAndWeekly() throws Exception {
        AdminQualityResponse before = qualityService.summary();

        draft(null).approve(1L);
        draft(null).reject("x");
        draftRepository.flush();

        AdminQualityResponse after = qualityService.summary();

        assertDelta(axis(before.byDifficulty(), "INTERMEDIATE"), axis(after.byDifficulty(), "INTERMEDIATE"), 0, 1, 1);
        assertDelta(last(before.weekly()), last(after.weekly()), 0, 1, 1);
        assertThat(last(after.weekly()).weekStart().getDayOfWeek().getValue()).isEqualTo(1);

        mockMvc.perform(get("/api/admin/quality").header("Authorization", fixtures.bearer(Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.weekly.length()").value(12))
                .andExpect(jsonPath("$.data.aiBuckets.length()").value(4));
    }

    /* ── 도우미 ── */

    private GeneratedProblemDraft draft(String findingsJson) {
        GeneratedProblemDraft d = GeneratedProblemDraft.pending(TestDomains.NETWORK, Difficulty.INTERMEDIATE,
                ProblemType.OX, "품질 테스트", "지문", "O", "해설", null, "quality-test-model", null, null, null);
        if (findingsJson != null) {
            d.recordAiFindings(findingsJson);
        }
        return draftRepository.save(d);
    }

    private static Counts bucket(AdminQualityResponse r, String key) {
        return r.aiBuckets().stream().filter(b -> b.key().equals(key)).findFirst().orElseThrow().counts();
    }

    private static Counts finding(AdminQualityResponse r, String type) {
        return r.findingTypes().stream().filter(f -> f.type().equals(type)).findFirst()
                .map(AdminQualityResponse.FindingTypeStat::counts).orElse(Counts.of(0, 0, 0));
    }

    private static Counts axis(List<AdminQualityResponse.AxisStat> list, String key) {
        return list.stream().filter(a -> a.key().equals(key)).findFirst()
                .map(AdminQualityResponse.AxisStat::counts).orElse(Counts.of(0, 0, 0));
    }

    private static AdminQualityResponse.WeekStat last(List<AdminQualityResponse.WeekStat> weeks) {
        return weeks.get(weeks.size() - 1);
    }

    private static void assertDelta(AdminQualityResponse.WeekStat before, AdminQualityResponse.WeekStat after,
                                    long pending, long approved, long rejected) {
        assertDelta(before.counts(), after.counts(), pending, approved, rejected);
    }

    private static void assertDelta(Counts before, Counts after, long pending, long approved, long rejected) {
        assertThat(after.pending() - before.pending()).as("대기").isEqualTo(pending);
        assertThat(after.approved() - before.approved()).as("승인").isEqualTo(approved);
        assertThat(after.rejected() - before.rejected()).as("거절").isEqualTo(rejected);
    }
}
