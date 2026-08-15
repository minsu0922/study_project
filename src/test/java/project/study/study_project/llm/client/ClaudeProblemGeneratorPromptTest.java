package project.study.study_project.llm.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 문제 생성 프롬프트 조립 테스트 — 2단계 근거 문서 부분(docs/15).
 *
 * <p><b>Claude를 부르지 않는다.</b> 검증 대상은 "모델이 좋은 문제를 내는가"가 아니라
 * <b>우리가 준 재료가 실제로 프롬프트에 실리는가</b>다.
 *
 * <p>이 테스트가 지키는 것은 2단계 전체가 <b>조용히 무력해지는</b> 실패다. 근거 문서를 찾아
 * 읽는 데까지 성공해 놓고 프롬프트에 안 실으면, 모델은 그냥 평소처럼 자기 지식으로 문제를
 * 만들고 파일에는 {@code documentSlug}가 멀쩡히 박힌다 — 화면에는 "근거 문서: xss-and-csrf"
 * 링크까지 뜬다. 겉으로는 완벽히 동작하는데 실제로는 문서와 아무 상관 없는 문제인 상태다.
 */
class ClaudeProblemGeneratorPromptTest {

    private final ClaudeProblemGenerator generator = new ClaudeProblemGenerator("claude-opus-5");

    private static final SourceDocument DOC = new SourceDocument(
            "xss-and-csrf", "XSS와 CSRF — 브라우저를 믿으면 생기는 일",
            "# XSS와 CSRF\n\n## 왜 필요한가\n서버는 요청을 보낸 게 진짜 그 사용자인지 볼 수 없다.");

    @Test
    @DisplayName("근거 문서를 주면 본문 전문이 프롬프트에 실린다 — 안 실으면 모델은 평소대로 지어낸다")
    void includesSourceDocumentBody() {
        String prompt = prompt(Difficulty.BEGINNER, DOC);

        assertThat(prompt).contains("[근거 문서]");
        assertThat(prompt).as("제목만이 아니라 본문이 통째로 들어가야 한다")
                .contains("서버는 요청을 보낸 게 진짜 그 사용자인지 볼 수 없다");
        assertThat(prompt).contains(DOC.title());
    }

    @Test
    @DisplayName("'문서에 없는 것을 묻지 마라'와 '베껴 쓰지 마라'가 함께 실린다 — 한쪽만 있으면 반대쪽으로 망가진다")
    void constrainsToDocumentWithoutCopying() {
        String prompt = prompt(Difficulty.INTERMEDIATE, DOC);

        assertThat(prompt).as("범위를 닫지 않으면 학습자가 문서를 읽고도 못 푸는 문제가 나온다")
                .contains("문서에 없는 사실을 끌어와 문제를 내지 마라");
        assertThat(prompt).as("범위만 닫으면 이번엔 문장을 베껴 빈칸을 뚫는 문제가 나온다")
                .contains("그대로 베껴 빈칸을 뚫는 식은 금지");
    }

    /**
     * 같은 문서로 사흘 연속 문제를 만드는 구조라, 난이도 지시가 같으면 사흘 내내 비슷한 문제가 나온다.
     * 문서의 어느 층을 캘지가 난이도마다 실제로 달라야 그 사고를 막는다.
     */
    @Test
    @DisplayName("난이도마다 문서에서 캐낼 부분이 다르다 — 같으면 사흘 내내 비슷한 문제가 나온다")
    void picksDifferentPartOfDocumentPerDifficulty() {
        String beginner = prompt(Difficulty.BEGINNER, DOC);
        String intermediate = prompt(Difficulty.INTERMEDIATE, DOC);
        String advanced = prompt(Difficulty.ADVANCED, DOC);

        assertThat(beginner).contains("정의·용어 설명");
        assertThat(intermediate).contains("왜 이렇게 설계됐는가");
        assertThat(advanced).as("문서에 면접 질문 절을 넣어 둔 것이 여기서 회수된다")
                .contains("면접에서 이렇게 물어본다");

        assertThat(List.of(beginner, intermediate, advanced))
                .as("세 프롬프트가 서로 달라야 한다").doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("근거 문서가 없으면 그 블록 자체를 넣지 않는다 — 빈 제목만 남으면 토큰 낭비이고 혼란스럽다")
    void omitsBlockWhenNoSourceDocument() {
        String prompt = prompt(Difficulty.BEGINNER, null);

        assertThat(prompt).doesNotContain("[근거 문서]");
        assertThat(prompt).doesNotContain("이번 난이도에서 쓸 부분");
        // 근거가 없어도 기본 조건은 그대로 실려야 한다
        assertThat(prompt).contains("분야:").contains("난이도:").contains("유형:");
    }

    /* ── 시스템 프롬프트의 품질 규칙 ─────────────────────────────
     * 아래 테스트들은 문구의 좋고 나쁨을 재지 않는다. 규칙이 <조용히 사라지는 것>을 막는다.
     * 프롬프트에서 한 줄이 빠져도 예외가 나지 않고 문서·문제는 멀쩡히 생성되므로,
     * 증상은 며칠 뒤 품질로만 나타난다 — 그때는 원인을 프롬프트로 되짚기가 어렵다. */

    /**
     * <b>8/14 사고를 프롬프트 쪽에서 막는 규칙.</b> 5개를 요청했는데 3개가 왔고 그중 하나가
     * 지문이 빈 껍데기였다. 코드는 그 뒤에 고쳤지만 프롬프트는 침묵하고 있었다 —
     * 모델 입장에서는 재료가 마르면 껍데기로 개수를 맞추는 것이 지시를 지키는 길이다.
     *
     * <p>우리 파이프라인에서는 정반대가 낫다. 적게 오는 것은 수확량 점검이 자동으로 잡고,
     * 껍데기는 사람이 읽어야 걸러진다. 그래서 <b>탐지 가능한 실패</b> 쪽으로 유도한다.
     */
    @Test
    @DisplayName("재료가 모자라면 적게 내라고 말한다 — 껍데기로 개수를 채우면 사람이 읽어야 걸러진다")
    void tellsModelToReturnFewerRatherThanFillers() {
        assertThat(ClaudeProblemGenerator.SYSTEM_PROMPT)
                .contains("[개수를 채우지 못할 때]")
                .as("개수를 강요하면 빈 껍데기가 나온다 — 실제로 그렇게 났다")
                .contains("만들 수 있는 만큼만 내라")
                .as("왜 그게 나은지를 알려 줘야 모델이 규칙을 따른다")
                .contains("사람이 하나씩 읽어야 걸러진다");
    }

    /**
     * 단답형 채점은 {@code QuizService.gradeShortAnswer}의 {@code trim + toLowerCase} 완전
     * 일치다. 정답이 "3-way handshake"면 학습자가 "3 way handshake"라고 써도 오답이다.
     * 프롬프트가 이 사실을 모르면 <b>채점 자체가 불가능한 답</b>이 나온다 —
     * 형식 규약({@code |} 구분)만 알려 주는 것으로는 부족하고 결과를 알려 줘야 한다.
     */
    @Test
    @DisplayName("단답형 채점이 완전 일치라는 사실을 프롬프트가 안다 — 모르면 채점 못 하는 답이 나온다")
    void explainsShortAnswerGradingRule() {
        assertThat(ClaudeProblemGenerator.SYSTEM_PROMPT)
                .contains("[단답형 문제의 조건]")
                .as("채점 방식을 알려 줘야 채점 가능한 답을 낸다")
                .contains("앞뒤 공백과 대소문자만 정규화한 뒤의 완전 일치")
                .as("갈릴 수 있는 표기는 전부 적어야 한다")
                .contains("변형을 모두 | 로 적어야 한다")
                .as("한국어 서술 답은 완전 일치로 채점할 수 없다")
                .contains("한국어 문장으로 답하는 문제는 내지 마라");
    }

    /**
     * OX·단답형에는 품질 규칙이 아예 없었다. 객관식만 [오답 보기의 조건]을 통째로 갖고 있고
     * 나머지 둘은 {@code typeRule}의 형식 한 줄이 전부였다.
     */
    @Test
    @DisplayName("OX에도 품질 규칙이 있다 — '항상·절대'가 그대로 정답 단서가 되면 지문을 안 읽어도 찍힌다")
    void hasQualityRulesForOxType() {
        assertThat(ClaudeProblemGenerator.SYSTEM_PROMPT)
                .contains("[OX 문제의 조건]")
                .contains("\"항상\", \"절대\", \"반드시\", \"모든\"")
                .as("참·거짓이 한쪽으로 몰리면 그것도 단서가 된다")
                .contains("참과 거짓을 고르게 섞는다");
    }

    /**
     * 해설 분량에 숫자를 박은 것을 지킨다. 문서 프롬프트에서 배운 것이 이 지점이다 —
     * 개수·분량을 숫자로 박은 지시만 실제로 지켜졌다. 승인된 17문제의 해설은 359~522자
     * (평균 441)였는데, 숫자가 없으면 그 품질이 유지된다는 보장이 없다.
     */
    @Test
    @DisplayName("해설에 분량 숫자가 박혀 있다 — 숫자 없는 품질 요구는 지켜지지 않는다")
    void pinsExplanationLength() {
        assertThat(ClaudeProblemGenerator.SYSTEM_PROMPT)
                .contains("400~700자")
                .as("상한을 준 대가로 되풀이 금지가 따라와야 한다")
                .contains("같은 말을 되풀이하지 마라")
                .as("틀렸을 때 어디로 돌아가면 되는지가 복습 동선이다")
                .contains("다시 읽을 절을 한 줄로 가리킨다");
    }

    /**
     * "문제끼리 같은 개념을 다른 말로 물어보는 것" 금지는 무엇이 '다른 개념'인지 기준이 없어
     * 지킬 수가 없었다. {@code buildPrompt}의 중복 회피 목록은 <b>기존 문제</b>를 막을 뿐
     * 한 배치 안의 중복은 못 막는다. 근거 문서가 있으니 모델이 스스로 확인할 수 있는
     * 기준("서로 다른 절")으로 바꿨다.
     */
    @Test
    @DisplayName("배치 안의 중복을 판정 가능한 기준으로 막는다 — 회피 목록은 기존 문제만 막는다")
    void makesIntraBatchDuplicationCheckable() {
        assertThat(ClaudeProblemGenerator.SYSTEM_PROMPT)
                .contains("[한 번에 만드는 문제들의 관계]")
                .as("'서로 다른 주제'는 판정 기준이 없다 — 문서의 절로 바꾼다")
                .contains("문서의 서로 다른 절이나 항목에서 나온다")
                .contains("한 절에서 두 문제를 뽑지 마라");
    }

    private String prompt(Difficulty difficulty, SourceDocument source) {
        return generator.buildPrompt(Domain.SECURITY, difficulty, ProblemType.MULTIPLE_CHOICE,
                5, List.of(), List.of(), source);
    }
}
