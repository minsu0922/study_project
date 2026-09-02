package project.study.study_project.admin;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.auth.jwt.JwtTokenProvider;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.llm.domain.GeneratedProblemDraft;
import project.study.study_project.llm.repository.GeneratedProblemDraftRepository;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 초안 <b>승인율</b> 집계 통합 테스트 — 관리자 대시보드.
 *
 * <p><b>왜 이 기능이 뒤늦게 생겼나.</b> {@code model} 컬럼은 처음부터 "모델을 바꿨을 때 품질이
 * 올랐는지 비교하려고" 기록해 왔고, 설계 문서에도 <b>"측정 없이 바꾸지 않는다"</b>고 적어
 * 두었다. 그런데 정작 <b>승인율을 볼 수단이 없었다</b> — 재료만 모으고 저울이 없던 셈이다.
 * 자동화 전체를 최종 점검하다가 드러났다.
 *
 * <p><b>왜 통합 테스트인가.</b> 이 기능이 조용히 고장 날 수 있는 자리가 셋인데
 * 단위 테스트로는 하나도 못 본다.
 * <ol>
 *   <li>JPQL의 {@code group by}가 틀리면 — 부팅은 되지만 숫자가 엉킨다
 *   <li>프로젝션 인터페이스의 별칭({@code as model})이 게터 이름과 어긋나면 — 런타임에만 터진다
 *   <li>승인율 {@code null}이 JSON으로 나가면서 0으로 뭉개지면 — "아직 검수 전"과
 *       "전부 거절당함"이 화면에서 같아 보인다
 * </ol>
 *
 * <p>MySQL이 필요하다(다른 통합 테스트와 같은 전제). 클래스 {@code @Transactional}로 롤백된다.
 */
@SpringBootTest(properties = "ratelimit.enabled=false")
@AutoConfigureMockMvc
@Transactional
class AdminLlmReviewStatsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private GeneratedProblemDraftRepository draftRepository;

    /**
     * 이 테스트만의 모델 이름을 쓴다. 대시보드는 <b>DB 전체</b>를 집계하므로 실제 초안이 남아
     * 있는 개발 DB에서도 돌아야 하는데, 흔한 이름을 쓰면 그 행과 섞여 숫자가 어긋난다.
     */
    private static final String MODEL = "test-model-" + UUID.randomUUID();

    @Test
    @DisplayName("모델별로 승인·거절을 세고 승인율을 낸다 — 검수 대기는 분모에서 빠진다")
    void aggregatesApprovalRatePerModel() throws Exception {
        // 승인 2 · 거절 1 · 대기 3  →  승인율은 2/(2+1) = 67%,  대기는 분모에 없다.
        // 대기를 분모에 넣으면 2/6 = 33%가 되어, 부지런히 만들수록 승인율이 떨어지는
        // 이상한 지표가 된다 — 그래서 "검수한 것들 중 승인 비율"로 정의했다.
        saveDraft().approve(1L);
        saveDraft().approve(2L);
        saveDraft().reject("부정형 문제라 검수 비용이 크다");
        saveDraft();
        saveDraft();
        saveDraft();
        draftRepository.flush();

        Map<String, Object> stat = findStat(dashboard(), MODEL);

        assertThat(stat).containsEntry("pending", 3);
        assertThat(stat).containsEntry("approved", 2);
        assertThat(stat).containsEntry("rejected", 1);
        assertThat(stat)
                .as("2/(2+1) = 66.7 → 반올림 67")
                .containsEntry("approvalRate", 67);
    }

    /**
     * <b>0%와 null은 정반대 신호다.</b> "만든 것이 전부 거절당했다"와 "아직 아무도 안 봤다"를
     * 같은 화면 값으로 보여 주면, 검수를 미룬 것이 품질 문제로 오해된다.
     */
    @Test
    @DisplayName("검수한 게 하나도 없으면 승인율은 0%가 아니라 null이다")
    void approvalRateIsNullWhenNothingReviewedYet() throws Exception {
        saveDraft();
        saveDraft();
        draftRepository.flush();

        Map<String, Object> stat = findStat(dashboard(), MODEL);

        assertThat(stat).containsEntry("pending", 2);
        assertThat(stat)
                .as("0으로 뭉개면 '전부 거절당함'과 구분되지 않는다")
                .containsEntry("approvalRate", null);
    }

    @Test
    @DisplayName("전부 거절당하면 승인율 0% — null과 달리 이건 진짜 품질 신호다")
    void approvalRateIsZeroWhenEverythingRejected() throws Exception {
        saveDraft().reject("사실 오류");
        saveDraft().reject("정답이 두 개");
        draftRepository.flush();

        assertThat(findStat(dashboard(), MODEL)).containsEntry("approvalRate", 0);
    }

    /**
     * 문서와 문제를 <b>나눠서</b> 담는지 확인한다. 합치면 표본이 큰 문제 쪽 숫자에 문서가 묻혀서
     * "모델을 바꿨더니 문서만 나빠졌다" 같은 판단을 할 수 없다.
     */
    @Test
    @DisplayName("응답이 문제/문서 두 묶음으로 나뉘어 있다")
    void separatesProblemsAndDocuments() throws Exception {
        String body = dashboard();

        assertThat(JsonPath.<List<Object>>read(body, "$.data.llmReview.problems")).isNotNull();
        assertThat(JsonPath.<List<Object>>read(body, "$.data.llmReview.documents")).isNotNull();
    }

    /* ── 도우미 ──────────────────────────────────────────────── */

    private String dashboard() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/admin/dashboard")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    /**
     * 응답에서 이 테스트가 만든 모델의 행만 찾는다.
     *
     * <p>JsonPath 필터({@code [?(@.model=='...')]})를 쓰지 않는 이유: 걸리는 게 없으면 빈
     * 배열을 돌려줘서 실패 메시지가 "expected but was null"로만 나온다. 자바에서 찾으면
     * "이 모델이 목록에 없다"는 말이 그대로 찍혀 원인을 바로 알 수 있다.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> findStat(String body, String model) {
        List<Map<String, Object>> problems = JsonPath.read(body, "$.data.llmReview.problems");
        return problems.stream()
                .filter(row -> model.equals(row.get("model")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "모델 " + model + "의 집계가 응답에 없습니다. 실제 목록: " + problems));
    }

    /**
     * 초안을 하나 만들어 저장한다.
     *
     * <p>{@code approve}에 넘기는 id는 실제 problem을 가리키지 않아도 된다 — 초안의
     * {@code approved_problem_id}는 <b>FK가 아니라 이력</b>이라 정합성 검사가 없다(V6 설계).
     * 승인 흐름 전체를 태우면 문제·보기까지 만들어야 해서, 상태 집계만 보는 이 테스트에는 과하다.
     */
    private GeneratedProblemDraft saveDraft() {
        return draftRepository.save(GeneratedProblemDraft.pending(
                Domain.NETWORK, Difficulty.INTERMEDIATE, ProblemType.MULTIPLE_CHOICE,
                "승인율 집계 테스트용 제목",
                "승인율 집계 테스트용 지문 " + UUID.randomUUID(),
                "1", "해설", "[]", MODEL, null, null, null));
    }

    private String adminToken() {
        User admin = userRepository.save(User.builder()
                .username("llmstats" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash(passwordEncoder.encode("admin-pw1"))
                .role(Role.ADMIN)
                .build());
        return jwtTokenProvider.createToken(admin.getId(), Role.ADMIN);
    }
}
