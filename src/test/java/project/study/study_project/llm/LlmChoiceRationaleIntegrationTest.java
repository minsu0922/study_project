package project.study.study_project.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.admin.dto.AdminProblemDetail;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.llm.domain.GeneratedProblemDraft;
import project.study.study_project.llm.repository.GeneratedProblemDraftRepository;
import project.study.study_project.llm.service.LlmProblemService;
import project.study.study_project.quiz.domain.Choice;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.repository.ProblemRepository;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 오답 설명({@code Choice.rationale})이 <b>초안에서 정식 문제까지 살아남는지</b> — 2026-08-27 신설.
 *
 * <h2>왜 단위 테스트로는 부족한가</h2>
 *
 * <p>이 값은 자리를 네 번 갈아탄다: 모델의 구조화 출력({@code GeneratedChoice.rationale}) →
 * 초안의 JSON 문자열({@code choices_json}) → 등록 요청({@code AdminProblemRequest.ChoiceItem}) →
 * DB 컬럼({@code choice.rationale}, V15). <b>어느 한 칸에서 빠뜨려도 컴파일은 통과한다</b> —
 * 전부 같은 자리에 다른 이름으로 있는 문자열이라, 옮기는 코드를 한 줄 빼먹으면
 * 조용히 {@code null}이 될 뿐이다. 그리고 화면에서야 "왜 오답 분석이 안 나오지?"로 나타난다.
 *
 * <p>이 저장소가 같은 종류의 사고를 이미 겪었다: {@code title}을 컬럼까지 만들어 두고
 * 옮기는 코드가 한 칸 빠져 있었다면 33건이 제목 없이 승인됐을 것이다. 그래서 배관은
 * <b>끝에서 끝까지</b> 한 번 흘려 봐야 한다.
 *
 * <p>MySQL이 필요하다(다른 통합 테스트와 같은 전제). 클래스 {@code @Transactional}로
 * 자동 롤백되므로 만든 데이터를 따로 지우지 않는다.
 */
@SpringBootTest(properties = {"ratelimit.enabled=false", "llm.import.enabled=false"})
@Transactional
class LlmChoiceRationaleIntegrationTest {

    @Autowired
    private LlmProblemService llmProblemService;
    @Autowired
    private GeneratedProblemDraftRepository draftRepository;
    @Autowired
    private ProblemRepository problemRepository;

    /**
     * 본체. 오답 셋에는 설명이 붙고, <b>정답 보기의 설명은 버려져야</b> 한다
     * ({@code AdminProblemService.normalizeRationale}).
     */
    @Test
    @DisplayName("오답 설명이 초안 JSON을 지나 정식 보기까지 살아남는다 — 정답 쪽은 버려진다")
    void rationaleSurvivesApproval() {
        String choicesJson = """
                [{"text":"정답 보기","correct":true,"rationale":"여기 적은 것은 버려져야 한다"},
                 {"text":"오답 하나","correct":false,"rationale":"읽기 차단과 쓰기 차단을 혼동한 설명이다"},
                 {"text":"오답 둘","correct":false,"rationale":"Lax의 조건을 None에 잘못 붙인 설명이다"},
                 {"text":"오답 셋","correct":false,"rationale":"사전 요청을 거치는 줄 안 오해다"}]""";

        GeneratedProblemDraft draft = draftRepository.save(GeneratedProblemDraft.pending(
                Domain.SECURITY, Difficulty.INTERMEDIATE, ProblemType.MULTIPLE_CHOICE,
                "오답 설명 배관 테스트",
                "오답 설명 배관 테스트용 지문 " + UUID.randomUUID(),
                null, "정답인 이유를 적은 해설입니다.", choicesJson, "test-model", null, null));

        AdminProblemDetail created = llmProblemService.approve(draft.getId());

        Problem problem = problemRepository.findById(created.id()).orElseThrow();
        List<Choice> choices = problem.getChoices();

        assertThat(choices).hasSize(4);
        assertThat(choices)
                .filteredOn(Choice::isCorrect)
                .singleElement()
                .extracting(Choice::getRationale)
                .as("정답의 근거는 해설이 맡는다 — 보기 쪽에 적힌 값은 저장 전에 버려져야 한다")
                .isNull();
        assertThat(choices)
                .filteredOn(c -> !c.isCorrect())
                .extracting(Choice::getRationale)
                .as("오답 셋은 화면의 '오답 분석' 칸을 채우는 값이라 하나도 빠지면 안 된다")
                .containsExactlyInAnyOrder(
                        "읽기 차단과 쓰기 차단을 혼동한 설명이다",
                        "Lax의 조건을 None에 잘못 붙인 설명이다",
                        "사전 요청을 거치는 줄 안 오해다");
    }

    /**
     * <b>이 필드가 생기기 전에 만들어진 초안이 승인을 통과해야 한다.</b>
     *
     * <p>검수함에는 {@code rationale} 키가 아예 없는 JSON이 쌓여 있다. 역직렬화하면
     * {@code null}이 되는데, 만약 {@code AdminProblemRequest.ChoiceItem}에 {@code @NotBlank}를
     * 걸었다면 <b>그 초안들이 승인 순간 400으로 튕겨</b> 영영 못 나가게 된다.
     * 그 판단이 유지되는지를 여기서 지킨다.
     */
    @Test
    @DisplayName("설명이 없는 옛 초안도 그대로 승인된다 — 검수함에 갇히면 안 된다")
    void oldDraftsWithoutRationaleStillApprove() {
        String legacyJson = """
                [{"text":"정답 보기","correct":true},{"text":"오답 보기","correct":false}]""";

        GeneratedProblemDraft draft = draftRepository.save(GeneratedProblemDraft.pending(
                Domain.NETWORK, Difficulty.BEGINNER, ProblemType.MULTIPLE_CHOICE,
                "옛 초안 승인 테스트",
                "옛 초안 승인 테스트용 지문 " + UUID.randomUUID(),
                null, "해설입니다.", legacyJson, "test-model", null, null));

        AdminProblemDetail created = llmProblemService.approve(draft.getId());

        assertThat(problemRepository.findById(created.id()).orElseThrow().getChoices())
                .extracting(Choice::getRationale)
                .as("값이 없는 것은 정상이다 — 화면이 옛 형식으로 알아보고 통짜 해설을 그린다")
                .containsOnlyNulls();
    }
}
