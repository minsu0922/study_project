package project.study.study_project.llm.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.llm.support.ProblemItemRule;

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

    /**
     * <b>난이도 지시가 가리키는 절이 실제로 있는 절인지</b> 본다.
     *
     * <p>2026-08-15에 실제로 어긋나 있었다. 고급이 "문서의 '실무에서 터지는 지점과 한계' 절"을
     * 지목했는데 그런 이름의 절은 문서에 없었다 — 실제 고급 재료는 {@code ## 언제 깨지는가}다.
     * 같은 날 {@code ## 실무에서는 이렇게 쓴다}를 신설하면서, 없는 이름을 가리키던 지시가
     * <b>비슷한 이름의 엉뚱한 절</b>을 가리키게 되어 더 나빠졌다.
     *
     * <p>이런 어긋남은 조용하다. 문제는 정상적으로 생성되고 스키마도 통과하며, 며칠 뒤
     * "고급인데 왜 쉽지?"로만 드러난다. 원인이 두 파일에 흩어진 문자열이라 되짚기도 어렵다.
     * 그래서 절 이름을 {@link ClaudeProblemGenerator#SOURCE_SECTIONS} 한 곳으로 모으고,
     * 그 이름들이 <b>문서 생성 프롬프트가 실제로 시키는 제목</b>인지 여기서 대조한다.
     */
    @Test
    @DisplayName("난이도 지시가 가리키는 절이 문서에 실제로 있는 절이다 — 없는 절을 지목해도 조용히 지나간다")
    void difficultyFocusPointsAtRealSections() {
        assertThat(prompt(Difficulty.BEGINNER, DOC))
                .as("초급 재료는 정의·용어 절이다")
                .contains("## 무엇인가");
        assertThat(prompt(Difficulty.ADVANCED, DOC))
                .as("고급 재료는 '언제 깨지는가'다 — 여기가 어긋나 고급 날 재료가 마를 뻔했다")
                .contains("## 언제 깨지는가")
                .contains("## 면접에서 이렇게 물어본다");
        assertThat(prompt(Difficulty.INTERMEDIATE, DOC))
                .as("중급 재료는 설계 판단 — 소제목 1개로 줄었으므로 본문 문장까지 함께 지목해야 한다")
                .contains("### 왜 이렇게 설계됐는가")
                .contains("## 실무에서는 이렇게 쓴다");
    }

    /**
     * 지목한 절 이름이 <b>문서 생성 프롬프트가 실제로 시키는 제목</b>인지 — 이게 진짜 안전장치다.
     *
     * <p>위 테스트는 "프롬프트 문장 안에 그 글자가 들어갔나"만 본다. 그건 {@code sourceFocus}가
     * 스스로를 확인하는 것이라, 두 파일이 갈라지는 원래 사고는 못 잡는다.
     * 문서 쪽 프롬프트에 대조해야 <b>문서에 존재할 수 없는 절을 지목하는 상태</b>가 걸린다.
     *
     * <p>{@code REQUIRED_SECTIONS}가 아니라 {@code SYSTEM_PROMPT}에 대조하는 이유:
     * 중급이 지목하는 {@code ### 왜 이렇게 설계됐는가}는 본론 안의 소제목이라
     * {@code REQUIRED_SECTIONS}(문서 최상위 필수 절 목록)에는 없다. 두 종류를 모두 덮으려면
     * "그 제목을 쓰라고 시키는 곳" 하나에 대조하는 것이 맞다.
     */
    @Test
    @DisplayName("지목한 절 이름을 문서 프롬프트가 실제로 시킨다 — 문서에 없을 절을 지목하면 매일 폴백으로 떨어진다")
    void sourceSectionsExistInDocumentPrompt() {
        assertThat(ClaudeProblemGenerator.SOURCE_SECTIONS)
                .as("세 난이도 모두 캘 곳이 정해져 있어야 한다")
                .containsOnlyKeys(Difficulty.BEGINNER, Difficulty.INTERMEDIATE, Difficulty.ADVANCED);

        ClaudeProblemGenerator.SOURCE_SECTIONS.forEach((difficulty, sections) -> {
            assertThat(sections).as("%s가 캘 절이 하나도 없다", difficulty).isNotEmpty();
            assertThat(sections).allSatisfy(section ->
                    assertThat(ClaudeDocumentGenerator.SYSTEM_PROMPT)
                            .as("%s가 지목한 '%s'를 문서 프롬프트가 시키지 않는다 — 문서에 그 절이 안 생긴다",
                                    difficulty, section)
                            .contains(section));
        });
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
                .as("프롬프트가 요구하는 분량과 검증기가 재는 분량은 같아야 한다")
                .contains("%d~%d자".formatted(
                        ProblemItemRule.EXPLANATION_MIN, ProblemItemRule.EXPLANATION_MAX))
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
    /**
     * <b>절 개수가 요청 개수를 못 채운다.</b> 초급이 캘 절은 하나뿐이고 중급도 둘뿐인데
     * 배치는 5개를 요청한다. 예전 문구는 "한 절에서 두 문제를 뽑지 마라"에 초급 예외만
     * 달아 두어, 중급이 규칙을 지키려면 <b>다른 절로 넘어가는 수밖에</b> 없었다 —
     * 그게 바로 다음 날 재료를 먹어 치우는 경로다.
     *
     * <p>그래서 규칙을 "절을 나눠라"가 아니라 <b>"항목을 나눠라"</b>로 바꿨다.
     * 절이 모자라면 그 절을 더 잘게 쪼개는 것이 답이지, 옆 절로 가는 것이 답이 아니다.
     */
    @Test
    @DisplayName("절이 모자라면 옆 절이 아니라 그 절을 쪼개게 한다 — 중급도 캘 절이 둘뿐이다")
    void makesIntraBatchDuplicationCheckable() {
        assertThat(ClaudeProblemGenerator.SYSTEM_PROMPT)
                .contains("[한 번에 만드는 문제들의 관계]")
                .as("'서로 다른 주제'는 판정 기준이 없다 — 문서의 항목으로 바꾼다")
                .contains("문서의 서로 다른 <항목>에서 나온다")
                .as("초급만 예외를 두면 중급이 갈 곳을 잃는다")
                .contains("초급이 캘 절은 하나뿐이고 중급도 둘뿐이라")
                .as("모자랄 때 어디로 갈지를 알려 줘야 옆 절로 새지 않는다")
                .contains("<그 절을 더 잘게 쪼개는> 것이 맞다");
    }

    /**
     * <b>실물 22문항 중 18개(82%)에서 정답이 가장 긴 보기였다.</b> 균등하면 25%다.
     * 지문을 읽지 않고 제일 긴 것만 골라도 맞는 상태였는데, 프롬프트에는 이미
     * "정답만 유독 길면 읽지 않고도 찍힌다"가 <b>있었다</b> — 숫자 없는 요구는 지켜지지 않는다.
     *
     * <p>검증기의 문턱과 같은 숫자여야 한다. 갈라지면 지시대로 쓴 문제가 매번 경고를 단다.
     */
    @Test
    @DisplayName("보기 길이 배수가 프롬프트와 검증기에 같은 숫자로 있다 — 82%가 정답 최장이었다")
    void pinsChoiceLengthRatio() {
        assertThat(ClaudeProblemGenerator.SYSTEM_PROMPT)
                .contains("%.1f배를 넘지 마라".formatted(ProblemItemRule.CHOICE_LENGTH_RATIO))
                .as("배수만 주면 정답이 최장인 상태는 그대로 남는다")
                .contains("<정답이 넷 중 가장 길면 안 된다>")
                .as("어느 쪽을 손볼지까지 정해 줘야 오답을 늘려 맞추지 않는다")
                .contains("정답에서 덜어내라")
                .as("자기 확인 목록에도 있어야 다 만든 뒤 대조한다")
                .contains("네 보기 중 정답이 가장 길면 고쳐라");
    }

    /**
     * 고급 오답이 자꾸 '오해'로 흐른 원인은 규칙끼리 싸운 것이었다.
     * [오답 보기의 조건]은 "흔히 저지르는 오해에 대응시켜라"라고 했고,
     * [난이도가 뜻하는 것]의 고급은 "조건이 달랐다면 옳았을 대응"이라고 했다.
     * 둘은 다른 것이다 — 오해는 틀린 믿음이고, 다른 상황의 정답은 틀리지 않았다.
     *
     * <p>2·3차 평가에서 고급 오답에 "격리 수준을 READ COMMITTED로 내린다"가 연속으로
     * 나왔다. 전형적인 오해형 오답이고, 어떤 조건에서도 정답이 되기 어렵다.
     */
    @Test
    @DisplayName("오답이 무엇을 담는지는 난이도가 정한다 — 고급 오답은 오해가 아니다")
    void splitsDistractorRuleByDifficulty() {
        assertThat(ClaudeProblemGenerator.SYSTEM_PROMPT)
                .contains("오답이 <무엇을 담는지는 난이도가 정한다>")
                .as("초·중급에는 오해형이 맞다 — 통째로 지우면 안 된다")
                .contains("초급·중급의 오답은 \"학습자가 흔히 저지르는 오해\"")
                .as("고급에만 예외를 명시해야 규칙이 안 싸운다")
                .contains("고급의 오답은 오해가 아니다")
                .as("모델이 스스로 판정할 기준을 줘야 지켜진다")
                .contains("\"이 대응이 정답이 되는 상황\"을 하나씩 떠올릴 수 있어야 한다");
    }

    /**
     * {@code [금지]}의 "지문에 정답 용어가 그대로 등장" 규칙이 <b>더 나은 초급 형태를 막고
     * 있었다</b>. 3차 평가에서 "MVCC에 대한 설명으로 옳은 것은?"이 나왔는데, 이건 앞선
     * 두 실행의 "…하는 기법의 이름은?"보다 낫다 — 용어를 지문에 두고 설명을 고르게 하면
     * 답이 새지 않는다. 규칙을 "답이 새는 경우"로 좁혔다.
     */
    @Test
    @DisplayName("설명 고르기 형태는 막지 않는다 — 금지 규칙이 더 나은 형태를 막고 있었다")
    void allowsPickTheDescriptionForm() {
        assertThat(ClaudeProblemGenerator.SYSTEM_PROMPT)
                .contains("정답 용어를 지문에 쓰고 그 정의를 빈칸으로 두는 식")
                .as("예외를 명시하지 않으면 모델이 안전한 쪽으로 피한다")
                .contains("<설명을 고르게> 하는")
                .as("왜 괜찮은지까지 있어야 판단이 선다")
                .contains("답이 용어가 아니라 설명이라");
    }

    /**
     * 지문·보기·해설은 {@code player.js}가 {@code escapeHtml}로 <b>평문</b>을 넣는다.
     * 백틱과 별표는 글자로 보인다. 반면 줄바꿈과 하이픈 목록은 CSS로 살렸으므로
     * (해설 22개 중 17개가 그 형태다) 함께 금지하면 해설이 오히려 나빠진다.
     */
    @Test
    @DisplayName("살릴 수 없는 마크다운만 막는다 — 목록까지 금지하면 해설이 나빠진다")
    void forbidsOnlyTheMarkdownThatCannotBeRendered() {
        assertThat(ClaudeProblemGenerator.SYSTEM_PROMPT)
                .contains("백틱(`)이나 별표(**)를 쓰는 것")
                .as("왜 안 되는지를 알려 줘야 지켜진다")
                .contains("화면에 평문 그대로 나가므로")
                .as("대신 무엇을 쓸지가 있어야 코드 인용을 아예 피하지 않는다")
                .contains("따옴표로 감싸라")
                .as("목록은 허용한다는 것을 명시해야 모델이 과잉 회피하지 않는다")
                .contains("줄바꿈과 하이픈 목록은 괜찮다");
    }

    /**
     * <b>2026-08-18 사용자 지적에서 나온 절이다.</b> 8/18 고급 5문제 중 셋의 보기에
     * {@code "SELECT ... FOR UPDATE"}·낙관적 잠금·갭 락이 들어 있었는데, 근거 문서 어디에도
     * 그 뜻이 없었다. 문서 쪽도 같은 날 고쳤지만 여기에도 한 겹이 필요하다 — 학습자는 보기를
     * 먼저 읽고, 문서로 돌아가는 것은 해설을 본 뒤다.
     *
     * <p><b>이 테스트가 진짜로 지키는 것은 예외 두 줄이다.</b> "정답인 용어에는 붙이지 마라"가
     * 빠지면 {@code [금지]}의 "지문이 답을 알려 주는 문제"와 정면으로 부딪히고, "문제를 쉽게
     * 만들라는 말이 아니다"가 빠지면 모델이 <b>난이도를 낮추는</b> 쪽으로 지시를 읽는다.
     * 규칙을 새로 넣을 때 기존 규칙과 부딪히는 자리를 같이 적지 않으면 모델은 둘 중 하나를
     * 버리는데, 어느 쪽을 버렸는지는 문제를 읽어야만 보인다.
     */
    @Test
    @DisplayName("문서에 없는 용어를 들여오지 않고, 어려운 용어는 괄호로 푼다 — 난이도는 그대로 둔 채")
    void requiresPlainLanguageForHardTerms() {
        assertThat(ClaudeProblemGenerator.SYSTEM_PROMPT)
                .as("학습자가 뜻을 확인할 곳은 근거 문서뿐이다")
                .contains("문서에 없는 용어를 새로 들여오지 마라")
                .as("무엇을 하는지가 없으면 이름만 외우게 된다")
                .contains("괄호로 <무엇을 하는지> 한 줄 붙인다")
                .as("이게 빠지면 [금지]의 '지문이 답을 알려 주는 문제'와 부딪힌다")
                .contains("그 용어가 곧 정답인 문제에는 붙이지 마라")
                .as("이게 빠지면 모델이 난이도를 낮추는 쪽으로 읽는다")
                .contains("<용어의 문턱만> 낮춘다");
    }

    /* ── 난이도 재정의 ────────────────────────────────────────
     * 출발점은 "초급이 초급 같지 않다"였다. 실측해 보니 2026-08-16 초급 5문제 중 4개가
     * 상황 서술로 시작했고, 하나는 두 격리 수준을 나란히 놓고 비교시켰다(중급의 일).
     * 원인은 초급 규칙이 약해서가 아니라 <다른 규칙이 초급 규칙을 덮어썼기> 때문이다 —
     * 근거 문서 블록의 "문서가 설명한 원리를 다른 상황에 적용하게 만들어야 한다"가
     * 모든 난이도에 걸려 있었는데, 그 문장은 중급의 정의 그 자체다.
     * 아래 테스트들은 그 덮어쓰기가 되살아나는 것을 막는다. */

    @Test
    @DisplayName("세 난이도가 '무엇을 묻는가'로 갈린다 — '얼마나 어려운가'로는 중급과 고급이 구분되지 않았다")
    void definesDifficultyByWhatIsAsked() {
        assertThat(ClaudeProblemGenerator.SYSTEM_PROMPT)
                .contains("[난이도가 뜻하는 것")
                .as("초급은 용어를 묻고 상황이 없다 — 이 한 줄이 초급 문제의 상한이다")
                .contains("<용어가 무엇인가>를 묻는다. 지문에 상황이 없다")
                .as("중급은 원리를 새 상황에 적용시킨다")
                .contains("<원리를 처음 보는 상황에 적용>하게 한다")
                .as("고급은 대응들 사이의 선택이다")
                .contains("<여러 대응 중 무엇을 고를지>를 묻는다")
                .as("칸을 넘으면 다음 날 재료가 사라진다는 이유까지 줘야 지켜진다")
                .contains("다음 날 문제가 쓸 재료를 미리 먹어 치운다");
    }

    /**
     * <b>중급과 고급을 가르는 실제 기준.</b> 지문 형태로는 둘이 구분되지 않았다 —
     * 2026-08-13(중급)과 08-14(고급) 모두 "상황 서술 → 원인과 대응으로 가장 적절한 것은?"이었다.
     * 갈린 곳은 오답이었다. 중급 오답에는 명백히 틀린 것이 섞여 있었고("시각 동기화가 어긋났다"),
     * 고급 오답은 넷 다 다른 상황에서는 옳은 대응이었다(stampede 대응·샤딩·TTL 연장).
     *
     * <p>이건 모델이 <b>스스로 판정할 수 있는</b> 기준이라는 점이 중요하다. "더 어렵게 내라"는
     * 지킬 수도 확인할 수도 없지만, "명백히 틀린 보기가 있는가"는 세어 볼 수 있다.
     */
    @Test
    @DisplayName("중급·고급은 오답 설계로 갈린다 — 지문 형태로는 둘이 똑같았다(08-13과 08-14 실측)")
    void separatesIntermediateFromAdvancedByDistractorDesign() {
        assertThat(ClaudeProblemGenerator.SYSTEM_PROMPT)
                .as("중급 오답은 원리를 이해했으면 틀렸음을 아는 것")
                .contains("오답은 원리를 잘못 적용한 판단이다")
                .as("고급 오답은 조건이 달랐다면 옳았을 대응 — 넷 다 그럴듯해야 한다")
                .contains("오답은 조건이 달랐다면 옳았을 대응이다")
                .as("판정 기준이 있어야 모델이 스스로 고칠 수 있다")
                .contains("중급인데 네 보기가 전부 그럴듯하면 그건 고급이다")
                .contains("고급인데 명백히 틀린 보기가 하나라도 있으면 그 문제는 고급이 아니다");
    }

    /**
     * 초급 지문 길이를 <b>프롬프트에도 숫자로</b> 적었는지 — 검증기만 숫자를 알면 반쪽이다.
     *
     * <p>이 저장소에서 숫자로 박은 지시만 실제로 지켜졌다(해설 분량, 문서 절 개수).
     * "한두 문장"은 모델도 사람도 서로 다르게 읽는다. 그리고 두 숫자가 갈라지면
     * <b>지시대로 쓴 지문이 매번 경고를 달고 나온다</b> — 해설 분량에 건 것과 같은 자물쇠다.
     */
    /**
     * <b>반쪽짜리 규칙이 만든 사고.</b> [해설] 절은 "나머지 보기가 왜 틀렸는지 짚어라"라고
     * 시키면서 <b>가리키는 방법</b>은 안 알려 줬다. 모델은 지시를 지키려고 순서로 가리켰고,
     * 2026-08-17 평가 실행에서 중급 3문제가 <b>전부</b> "두 번째 보기는"으로 나왔다.
     *
     * <p>내보낼 때 보기를 섞으므로(커밋 9a4fd6b) 그대로 두면 학습자만 틀린 해설을 읽는다.
     * 검수자는 섞이기 전 화면을 보기 때문에 사람 눈으로는 영영 안 걸린다 —
     * 검증기가 세지 않았으면 9개 중 3개가 그대로 나갔다.
     *
     * <p>금지만 적지 않고 <b>대신 무엇을 하라</b>까지 예시로 붙인 이유: 이 저장소에서
     * 예시가 규칙을 이긴다는 것을 이미 확인했다. "쓰지 마라"만 있으면 모델은 보기를
     * 아예 안 짚는 쪽으로 도망갈 수 있는데, 그건 해설의 값어치를 깎는다.
     */
    @Test
    @DisplayName("보기는 순서가 아니라 내용으로 가리키게 한다 — 섞으면 학습자만 어긋난 해설을 읽는다")
    void tellsModelToQuoteChoicesInsteadOfNumberingThem() {
        assertThat(ClaudeProblemGenerator.SYSTEM_PROMPT)
                .as("무엇을 하라는지가 있어야 보기를 안 짚는 쪽으로 도망가지 않는다")
                .contains("<순서가 아니라 내용>으로 가리켜라")
                .as("왜 그런지를 줘야 지켜진다 — 이 프롬프트의 오랜 방식")
                .contains("보기 순서를 섞기 때문에")
                .as("예시가 규칙을 이긴다 — 나쁜 예와 좋은 예를 짝으로 준다")
                .contains("(X) 두 번째 보기는")
                .contains("(O) \"MVCC도 읽기에 공유 락을 건다\"는 보기는");
    }

    @Test
    @DisplayName("초급 지문 길이가 프롬프트와 검증기에 같은 숫자로 있다 — 한쪽만 알면 반쪽이다")
    void pinsBeginnerQuestionLength() {
        assertThat(prompt(Difficulty.BEGINNER, DOC))
                .contains("%d자 이내".formatted(ProblemItemRule.BEGINNER_QUESTION_MAX));
        assertThat(prompt(Difficulty.INTERMEDIATE, DOC))
                .as("중급은 상황이 한 문단 있는 것이 정상이라 길이를 걸지 않는다")
                .doesNotContain("자 이내");
    }

    @Test
    @DisplayName("초급이 중급으로 넘어가는 두 경로를 이름 붙여 막는다 — 실제로 그 둘로 넘어갔다")
    void blocksTheTwoWaysBeginnerDriftsUpward() {
        assertThat(ClaudeProblemGenerator.SYSTEM_PROMPT)
                .as("08-16 초급 5문제 중 4개가 배경 서술로 시작했다")
                .contains("초급인데 \"~하고 있다\", \"~했다\"로 배경을 서술했다면")
                .as("READ COMMITTED와 REPEATABLE READ를 나란히 놓고 비교시킨 문제가 초급으로 나왔다")
                .contains("초급인데 두 개념을 나란히 놓고");
    }

    /**
     * 초급이 어려워진 <b>직접 원인</b>을 막는다. 근거 문서 블록에 "문서가 설명한 원리를
     * 다른 상황에 적용하게 만들어야 이해를 확인할 수 있다"가 난이도와 무관하게 실려 있었다.
     * 그 문장은 중급의 정의라, 초급 규칙과 부딪히면 이쪽이 이겼다.
     */
    @Test
    @DisplayName("근거 문서 블록이 모든 난이도에 '새 상황에 적용하라'를 걸지 않는다 — 이게 초급을 밀어 올렸다")
    void doesNotForceApplicationOnEveryDifficulty() {
        assertThat(prompt(Difficulty.BEGINNER, DOC))
                .as("초급에까지 걸리면 상황을 지어내게 된다 — 실제로 그렇게 났다")
                .doesNotContain("다른 상황에 적용하게 만들어야")
                .as("적용 여부는 난이도가 정한다는 것을 그 자리에서 알려 줘야 한다")
                .contains("초급에는 상황을 붙이지 마라");
    }

    /**
     * 예시를 지문 한 줄에서 <b>보기까지 갖춘 통 문항</b>으로 바꾼 것을 지킨다.
     *
     * <p>난이도를 실제로 가르는 것은 오답인데, 지문 한 줄짜리 예시로는 오답 설계가
     * 전달되지 않는다. 그리고 이 저장소는 <b>예시가 규칙을 이긴다</b>는 것을 이미 확인했다 —
     * 문서 프롬프트의 "핵심 용어를 하이픈 목록으로" 규칙이 바로 아래 예시에 하이픈이
     * 없다는 이유로 무시됐다. 그렇다면 예시 쪽에 투자하는 것이 맞다.
     */
    @Test
    @DisplayName("난이도 예시가 보기와 판정 근거까지 갖췄다 — 지문 한 줄로는 오답 설계가 전달되지 않는다")
    void difficultyExamplesShowDistractorsNotJustQuestions() {
        String beginner = prompt(Difficulty.BEGINNER, null);
        String intermediate = prompt(Difficulty.INTERMEDIATE, null);
        String advanced = prompt(Difficulty.ADVANCED, null);

        assertThat(List.of(beginner, intermediate, advanced)).allSatisfy(p ->
                assertThat(p).as("보기 없이 지문만 보여 주면 난이도의 핵심(오답 설계)이 빠진다")
                        .contains("보기:")
                        .as("예시 주제를 그대로 베껴 가면 매일 TCP 문제만 나온다")
                        .contains("주제(TCP)는 가져다 쓰지 마라"));

        // 왜 그 난이도인지를 예시에 붙여야 모델이 자기 결과와 대조할 수 있다.
        assertThat(beginner).contains("왜 초급인가");
        assertThat(intermediate).contains("왜 중급인가");
        assertThat(advanced).contains("왜 고급인가");

        // 한 요청에는 그 난이도의 예시만 실린다 — 나머지 둘이 보이면 넘어갈 빌미가 된다.
        assertThat(beginner).as("초급 요청에 고급 예시가 함께 실리면 안 된다")
                .doesNotContain("왜 고급인가");
    }

    private String prompt(Difficulty difficulty, SourceDocument source) {
        return generator.buildPrompt(Domain.SECURITY, difficulty, ProblemType.MULTIPLE_CHOICE,
                5, List.of(), List.of(), source);
    }
}
