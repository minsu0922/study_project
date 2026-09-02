package project.study.study_project.admin;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import project.study.study_project.auth.jwt.JwtTokenProvider;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.llm.domain.DraftStatus;
import project.study.study_project.llm.domain.GeneratedProblemDraft;
import project.study.study_project.llm.repository.GeneratedProblemDraftRepository;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 일괄 승인 통합 테스트 — <b>부분 성공이 진짜로 커밋되는지</b>를 본다.
 *
 * <h2>왜 단위 테스트로는 부족한가</h2>
 *
 * <p>{@code LlmDraftBulkApproverTest}는 "실패해도 다음 건을 계속 시도한다"까지만 확인한다.
 * 정작 이 기능이 조용히 망가지는 자리는 그 아래, <b>트랜잭션 경계</b>다. 반복문을
 * {@code LlmProblemService} 안으로 옮기거나 {@code approveAll}에 {@code @Transactional}을
 * 붙이는 순간, 실패 한 건이 바깥 트랜잭션에 rollback-only 표시를 남겨 <b>성공한 건까지 전부</b>
 * 사라진다. 그런데 목(mock)을 쓰는 단위 테스트에는 트랜잭션 자체가 없어 그 회귀를 못 본다.
 * 실제 DB에 붙어 커밋 뒤의 상태를 읽어야만 잡힌다.
 *
 * <h2>왜 클래스에 {@code @Transactional}을 붙이지 않았나</h2>
 *
 * <p>다른 통합 테스트들은 {@code @Transactional}로 자동 롤백을 받지만, 여기서는 그것이
 * <b>검증 대상을 지워 버린다</b> — 테스트가 트랜잭션을 하나 열어 두면 그 안의 모든 승인이
 * 그 트랜잭션에 합류해, 확인하려던 "건별 커밋"이 애초에 일어나지 않는다. 그래서 롤백을
 * 포기하고 {@link #cleanUp()}에서 만든 데이터를 직접 지운다.
 *
 * <p>MySQL이 필요하다(다른 통합 테스트와 같은 전제).
 */
/*
 * llm.import.enabled=false 인 이유: 이 테스트는 승인을 <b>진짜로 커밋</b>하는데, 커밋 뒤에는
 * 스냅샷 내보내기가 깨어나 generated/_existing-questions.json을 다시 쓴다(ReviewCompleted).
 * 그 파일은 git이 추적하는 파일이라, 끄지 않으면 <b>테스트를 돌릴 때마다 저장소가 더러워지고</b>
 * 테스트용 지문("일괄 승인 테스트용 지문 …")이 배치의 중복 회피 목록에 섞여 들어간다.
 * 실제로 한 번 그렇게 됐고 되돌렸다. 내보내기는 이 테스트의 검증 대상이 아니므로 꺼도 손실이 없다.
 */
@SpringBootTest(properties = {"ratelimit.enabled=false", "llm.import.enabled=false"})
@AutoConfigureMockMvc
class AdminLlmBulkApproveIntegrationTest {

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
    @Autowired
    private ProblemRepository problemRepository;

    /** 자동 롤백이 없으므로 만든 것을 직접 기억해 뒀다가 지운다. */
    private final List<Long> createdDraftIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        // 승인으로 만들어진 정식 문제부터 지운다 — 보기는 cascade로 함께 사라진다(Problem 엔티티)
        draftRepository.findAllById(createdDraftIds).stream()
                .map(GeneratedProblemDraft::getApprovedProblemId)
                .filter(java.util.Objects::nonNull)
                .forEach(problemRepository::deleteById);
        draftRepository.deleteAllById(createdDraftIds);
        userRepository.deleteAllById(createdUserIds);
    }

    /**
     * 이 테스트의 본체. 가운데 한 건이 규칙 위반(정답 보기 2개)이라 승인에 실패하는데,
     * 앞뒤 두 건은 <b>커밋된 채로 남아야</b> 한다.
     */
    @Test
    @DisplayName("한 건이 규칙 위반이어도 나머지는 실제로 등록된다 — 건별 트랜잭션")
    void partialSuccessIsActuallyCommitted() throws Exception {
        Long ok1 = savePendingDraft(validChoicesJson()).getId();
        Long bad = savePendingDraft(twoCorrectChoicesJson()).getId();
        Long ok2 = savePendingDraft(validChoicesJson()).getId();

        String body = approveBatch(List.of(ok1, bad, ok2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.approved.length()").value(2))
                .andExpect(jsonPath("$.data.failed.length()").value(1))
                .andExpect(jsonPath("$.data.failed[0].draftId").value(bad))
                .andReturn().getResponse().getContentAsString();

        // 실패 사유가 사람이 읽을 문장 그대로 실려 오는지 — 화면이 이걸 그대로 보여 준다
        assertThat(JsonPath.<String>read(body, "$.data.failed[0].message"))
                .contains("정답 보기가 정확히 1개");

        // 여기가 핵심 — 응답만 믿지 않고 DB를 다시 읽는다.
        // 한 트랜잭션으로 묶여 있었다면 이 셋 다 PENDING으로 되돌아가 있다.
        assertThat(reload(ok1).getStatus()).isEqualTo(DraftStatus.APPROVED);
        assertThat(reload(ok2).getStatus()).isEqualTo(DraftStatus.APPROVED);
        assertThat(reload(bad).getStatus())
                .as("실패한 건만 검수 대기로 남아야 다시 손볼 수 있다")
                .isEqualTo(DraftStatus.PENDING);

        // 상태만 바뀌고 문제가 안 만들어지는 반쪽 승인이 아닌지도 확인한다
        assertThat(problemRepository.findById(reload(ok1).getApprovedProblemId())).isPresent();
        assertThat(problemRepository.findById(reload(ok2).getApprovedProblemId())).isPresent();
    }

    @Test
    @DisplayName("이미 승인된 초안을 다시 넣으면 그 건만 실패한다(LLM_002)")
    void alreadyApprovedDraftFailsAlone() throws Exception {
        Long first = savePendingDraft(validChoicesJson()).getId();
        Long second = savePendingDraft(validChoicesJson()).getId();

        approveBatch(List.of(first)).andExpect(status().isOk());

        // 같은 목록을 다시 보낸다 — 실수로 두 번 누른 상황
        approveBatch(List.of(first, second))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.approved.length()").value(1))
                .andExpect(jsonPath("$.data.approved[0].draftId").value(second))
                .andExpect(jsonPath("$.data.failed[0].draftId").value(first));
    }

    /**
     * 빈 목록을 400으로 막는 이유: 아무 일도 안 일어나는 요청이 200으로 성공하면,
     * 화면에 "0건 승인 완료"가 뜨면서 <b>선택이 안 된 줄 모르고</b> 넘어간다.
     */
    @Test
    @DisplayName("빈 목록은 400 — 아무것도 안 하는 요청이 성공으로 보이면 안 된다")
    void emptyListIsRejected() throws Exception {
        approveBatch(List.of()).andExpect(status().isBadRequest());
    }

    /* ── 도우미 ──────────────────────────────────────────────── */

    private org.springframework.test.web.servlet.ResultActions approveBatch(List<Long> ids) throws Exception {
        String json = ids.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
        return mockMvc.perform(post("/api/admin/llm-problems/approve-batch")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[" + json + "]}"));
    }

    private GeneratedProblemDraft reload(Long id) {
        return draftRepository.findById(id).orElseThrow();
    }

    /** 정답 보기 1개 — 승인이 통과하는 정상 초안. */
    private String validChoicesJson() {
        return """
                [{"text":"보기1","correct":true},{"text":"보기2","correct":false}]""";
    }

    /**
     * 정답 보기 2개 — {@code AdminProblemService}가 QUIZ_004로 막는다.
     *
     * <p>저장 경로({@code saveDrafts})를 거쳤다면 애초에 걸러졌을 초안이라, 저장소에 직접 넣어
     * 만든다. 실제로도 이런 초안은 생길 수 있다 — 규칙이 나중에 엄해지면 이미 쌓여 있던
     * 초안이 승인 시점에 걸린다. 일괄 승인이 감당해야 하는 것이 바로 그 상황이다.
     */
    private String twoCorrectChoicesJson() {
        return """
                [{"text":"보기1","correct":true},{"text":"보기2","correct":true}]""";
    }

    private GeneratedProblemDraft savePendingDraft(String choicesJson) {
        GeneratedProblemDraft draft = draftRepository.save(GeneratedProblemDraft.pending(
                Domain.NETWORK, Difficulty.INTERMEDIATE, ProblemType.MULTIPLE_CHOICE,
                "일괄 승인 테스트용 제목",
                "일괄 승인 테스트용 지문 " + UUID.randomUUID(),
                null, "해설입니다.", choicesJson, "test-model", null, null, null));
        createdDraftIds.add(draft.getId());
        return draft;
    }

    private String adminToken() {
        User admin = userRepository.save(User.builder()
                .username("bulkapv" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash(passwordEncoder.encode("admin-pw1"))
                .role(Role.ADMIN)
                .build());
        createdUserIds.add(admin.getId());
        return jwtTokenProvider.createToken(admin.getId(), Role.ADMIN);
    }
}
