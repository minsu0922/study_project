package project.study.study_project.llm.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.llm.client.GeneratedProblemItem;
import project.study.study_project.llm.client.SourceDocument;
import project.study.study_project.llm.support.ProblemItemRule;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 평가 하네스의 <b>채점</b> 테스트 — Claude를 부르지 않는다.
 *
 * <p>이 도구에서 정작 틀리기 쉬운 곳은 모델 호출이 아니라 채점이다. 호출이 실패하면 예외가
 * 나서 곧바로 알지만, 채점이 틀리면 <b>그럴듯한 숫자가 나온다</b>. 그리고 그 숫자를 근거로
 * 프롬프트를 고치게 되므로, 틀린 자를 들고 재는 것이 아예 안 재는 것보다 나쁘다.
 */
class PromptEvalCliTest {

    /**
     * 배경 서술 세기 — 초급 상한을 재는 유일한 잣대다.
     *
     * <p>2026-08-16 초급 5문제로 맞춰 본 것이라, 그 다섯 개의 실제 지문으로 못 박는다.
     * 눈으로 읽은 판정(45자짜리 하나만 상황 없음)과 같은 답이 나와야 한다.
     */
    @Test
    @DisplayName("배경 서술이 붙은 지문만 센다 — 08-16 초급 5문제의 눈 판정과 같아야 한다")
    void countsBackgroundSentencesLikeAHumanWould() {
        // 질문만 있는 지문 — 목표 형태
        assertThat(PromptEvalCli.countDeclarativeSentences(
                "MVCC가 읽기를 대기 없이 처리하는 대신 치르는 대가는?")).isZero();
        assertThat(PromptEvalCli.countDeclarativeSentences(
                "TCP 연결을 먼저 닫은 쪽이 일정 시간 머무는 상태의 이름은?")).isZero();

        // 배경이 붙은 지문 — 초급에서는 나오면 안 되는 형태
        assertThat(PromptEvalCli.countDeclarativeSentences(
                "재고 관리 트랜잭션에서 같은 조회를 두 번 실행했다. 이 상황에 해당하는 이상 현상은?"))
                .isEqualTo(1);
        assertThat(PromptEvalCli.countDeclarativeSentences(
                "두 트랜잭션이 각각 재고 10을 읽고 9를 계산해 저장했다. "
                        + "실제로는 2개가 팔렸지만 재고는 9로 남았다. 이 현상은 무엇인가?"))
                .isEqualTo(2);
    }

    /**
     * 괄호로 닫힌 서술도 세야 한다. 08-16 1번이 실제로 그 형태였다 —
     * "…들어 있었다(다른 트랜잭션이 그 사이 새 행을 삽입하고 <b>커밋했다).</b>"
     * 괄호를 놓치면 가장 전형적인 배경 서술을 못 센다.
     */
    @Test
    @DisplayName("괄호로 닫힌 서술도 센다 — 08-16 1번이 그 형태였고, 놓치면 정반대 결론이 나온다")
    void countsSentencesClosedByParenthesis() {
        // 괄호 안은 앞 문장의 부연이므로 문장 하나다. 여는 괄호 앞의 "있었다("는 세면 안 되고,
        // 닫는 괄호 뒤의 "커밋했다)."는 세야 한다.
        assertThat(PromptEvalCli.countDeclarativeSentences(
                "두 번째 결과에는 없던 행이 더 있었다(다른 트랜잭션이 새 행을 삽입하고 커밋했다). "
                        + "이 상황에 해당하는 이상 현상은?"))
                .isEqualTo(1);
    }

    /**
     * "…에 해당하는 현상은 무엇인가?" 처럼 <b>'다'로 끝나지만 질문인</b> 문장을 세면 안 된다.
     * 오탐이 나면 초급이 잘 나온 날에도 "배경 서술 3건"이 찍혀, 이 도구를 못 믿게 된다.
     */
    @Test
    @DisplayName("질문 문장은 서술로 세지 않는다 — 오탐이 나면 도구 자체를 못 믿는다")
    void doesNotCountQuestionsAsBackground() {
        assertThat(PromptEvalCli.countDeclarativeSentences("무엇인가?")).isZero();
        assertThat(PromptEvalCli.countDeclarativeSentences("팬텀 리드의 정의로 옳은 것은?")).isZero();
        assertThat(PromptEvalCli.countDeclarativeSentences(null)).isZero();
    }

    /**
     * 껍데기(지문·해설이 빈 항목)는 길이 평균에 넣으면 안 된다. 2026-08-14 배치에 실제로
     * 하나 있었는데, 세어 넣었다면 그날 지문 평균이 <b>3분의 1로 떨어져</b> "지문이 짧아졌다"는
     * 정반대 결론이 나온다.
     */
    @Test
    @DisplayName("껍데기는 길이 평균에서 뺀다 — 넣으면 '지문이 짧아졌다'는 반대 결론이 나온다")
    void excludesHusksFromLengthStats() {
        List<GeneratedProblemItem> problems = List.of(
                item("지문이 백 자쯤 되는 멀쩡한 문제로 길이를 재는 데 쓴다. 무엇인가?", explanation()),
                new GeneratedProblemItem("", "", "", choices())); // 껍데기

        PromptEvalCli.DifficultyReport r =
                PromptEvalCli.score(Difficulty.BEGINNER, 2, problems);

        assertThat(r.received()).as("응답 개수는 온 그대로 센다").isEqualTo(2);
        assertThat(r.usable()).as("껍데기는 쓸 수 없다").isEqualTo(1);
        assertThat(r.questionLength().size()).as("길이 표본에는 유효한 것만 들어간다").isEqualTo(1);
        assertThat(r.questionLength().min()).isEqualTo(r.questionLength().max());
    }

    /**
     * 경고를 <b>종류별로 묶는다</b>. 숫자까지 키로 쓰면 "해설이 짧음 (382자…)"와
     * "해설이 짧음 (395자…)"가 다른 항목이 되어, 표가 한 줄짜리로 가득 차 한눈에 안 들어온다.
     */
    @Test
    @DisplayName("경고는 숫자를 떼고 종류로 묶는다 — 안 그러면 표가 한 줄짜리로 가득 찬다")
    void groupsWarningsByKind() {
        List<GeneratedProblemItem> problems = List.of(
                item("첫 문제는?", "짧".repeat(300)),
                item("둘째 문제는?", "짧".repeat(310)));

        PromptEvalCli.DifficultyReport r =
                PromptEvalCli.score(Difficulty.INTERMEDIATE, 2, problems);

        // 재료가 글자만 채운 해설이라 오답 인용 경고에도 걸린다(2026-08-25 신설).
        // 이 테스트가 재는 것은 "묶이는가"이지 "몇 종류가 나오는가"가 아니므로 종류 수는 세지 않는다 —
        // 세면 검사가 하나 늘 때마다 상관없는 테스트가 따라 깨진다.
        assertThat(r.warnings())
                .as("숫자가 다른 두 건이 한 항목으로 묶여야 표가 읽힌다")
                .containsEntry("해설이 짧음", 2);
    }

    @Test
    @DisplayName("초급 지문에 배경이 붙은 개수를 따로 센다 — 초급 상한이 지켜지는지의 핵심 지표")
    void reportsHowManyBeginnerQuestionsCarryBackground() {
        List<GeneratedProblemItem> problems = List.of(
                item("팬텀 리드의 정의는?", explanation()),
                item("트랜잭션을 두 번 실행했다. 이 현상은?", explanation()));

        PromptEvalCli.DifficultyReport r =
                PromptEvalCli.score(Difficulty.BEGINNER, 2, problems);

        assertThat(r.withBackground()).isEqualTo(1);
        assertThat(r.usable()).isEqualTo(2);
    }

    /**
     * 근거 문서에 그 난이도가 캘 절이 없으면 <b>숫자를 곧이곧대로 읽으면 안 된다</b> —
     * 모델이 다른 절에서 캔 결과라, 프롬프트가 아니라 문서의 상태를 재고 있는 셈이다.
     * 2026-08-15 문서가 정확히 그 상태여서, 그걸로 평가하면 중급 숫자가 거짓말을 한다.
     */
    @Test
    @DisplayName("문서에 없는 절을 보고서 머리에 적는다 — 없는 줄 모르고 숫자를 믿으면 엉뚱한 걸 고친다")
    void warnsAboutSectionsMissingFromTheSourceDocument() {
        String withoutIntermediate = """
                # 격리 수준

                ## 바탕이 되는 개념
                상위 개념을 처음부터 설명한다.

                ## 무엇인가
                - **트랜잭션(transaction)** — 작업 묶음이다.

                ## 언제 깨지는가
                - 조건 하나.

                ## 면접에서 이렇게 물어본다
                **Q. 무엇인가?**
                """;

        assertThat(PromptEvalCli.missingSections(withoutIntermediate))
                .containsExactlyInAnyOrder("### 왜 이렇게 설계됐는가", "## 실무에서는 이렇게 쓴다");

        String complete = withoutIntermediate
                + "\n### 왜 이렇게 설계됐는가\n- 근거.\n\n## 실무에서는 이렇게 쓴다\n- 이렇게.\n";
        assertThat(PromptEvalCli.missingSections(complete)).isEmpty();
    }

    /**
     * 보고서는 <b>두 실행을 나란히 놓고 보려고</b> 만든 것이다. 비교의 축이 되는 항목이
     * 빠지면 도구의 목적 자체가 사라지므로, 표의 뼈대를 여기서 못 박는다.
     */
    @Test
    @DisplayName("보고서에 비교의 축이 전부 들어간다 — 하나라도 빠지면 전후 비교가 안 된다")
    void reportCarriesEverythingNeededForComparison() {
        List<PromptEvalCli.DifficultyReport> reports = List.of(
                PromptEvalCli.score(Difficulty.BEGINNER, 1,
                        List.of(item("팬텀 리드의 정의는?", explanation()))),
                PromptEvalCli.score(Difficulty.INTERMEDIATE, 1,
                        List.of(item("두 번 실행했다. 원인은?", explanation()))),
                PromptEvalCli.score(Difficulty.ADVANCED, 1,
                        List.of(item("이미 늘렸는데도 반복된다. 대응은?", explanation()))));

        String rendered = PromptEvalCli.render(reports, "claude-opus-5", Domain.DATABASE,
                Path.of("generated/documents/2026-08-15.json"),
                new PromptEvalCli.LoadedDocument(Domain.DATABASE,
                        new SourceDocument("iso", "격리 수준", "## 무엇인가\n정의.")));

        assertThat(rendered)
                .as("어떤 조건으로 잰 결과인지 없으면 두 보고서를 비교할 수 없다")
                .contains("claude-opus-5").contains("DATABASE").contains("2026-08-15")
                .as("세 난이도가 모두 표에 있어야 사다리가 보인다")
                .contains("BEGINNER").contains("INTERMEDIATE").contains("ADVANCED")
                .as("초급 상한의 핵심 지표")
                .contains("배경 서술이 붙은 문항")
                .as("숫자로 못 재는 것은 사람이 보게 표본을 뽑아 준다")
                .contains("눈으로 볼 표본").contains("(정답)")
                .as("이 문서에 없는 절은 머리에 적어 숫자를 잘못 읽지 않게 한다")
                .contains("이 문서에 없는 절");
    }

    /**
     * 보고서가 경고의 <b>걸린 자리</b>까지 싣는지.
     *
     * <p>2026-08-17 첫 실행에서 "ADVANCED: 해설이 보기를 번호로 가리킴 1건"만 찍혔다.
     * 표본 절에는 지문과 보기만 넣어 뒀는데 정작 경고는 해설에서 났으므로,
     * <b>무엇이 걸렸는지 볼 방법이 아예 없었다</b>. 종류만 아는 경고는 결국 사람이
     * 해설 500자를 처음부터 다시 읽게 만든다.
     */
    @Test
    @DisplayName("보고서가 경고의 걸린 자리까지 싣는다 — 종류만 찍으면 결국 전문을 다시 읽어야 한다")
    void reportShowsWhatTrippedEachWarning() {
        // 걸린 자리를 싣는지 재는 것이 목적이라, 조각을 달고 나오는 경고면 무엇이든 된다.
        // 전에는 해설의 보기 번호 지칭을 썼는데 2026-08-25에 그것이 차단으로 올라갔다 —
        // 차단된 항목은 아예 버려져 경고 목록에 오르지 않으므로 재료가 될 수 없다.
        // 지문의 근거 문서 지칭으로 바꿨다. 같은 방식(contextOf)으로 조각을 만든다.
        String explanation = "해".repeat(420);

        String rendered = PromptEvalCli.render(
                List.of(PromptEvalCli.score(Difficulty.ADVANCED, 1,
                        List.of(item("MVCC가 대가로 문서가 든 것은?", explanation)))),
                "claude-opus-5", Domain.DATABASE, Path.of("generated/documents/2026-08-15.json"),
                new PromptEvalCli.LoadedDocument(Domain.DATABASE,
                        new SourceDocument("iso", "격리 수준", "## 무엇인가\n정의.")));

        assertThat(rendered)
                .as("몇 번 문항인지 있어야 표본과 맞춰 볼 수 있다")
                .contains("1번")
                .as("걸린 표현이 있어야 고칠지 말지가 한눈에 정해진다")
                .contains("문서가 든")
                .as("해설 전문을 통째로 실으면 경고 하나가 화면을 먹는다")
                .doesNotContain("해".repeat(60));
    }

    /* ── 도우미 ──────────────────────────────────────────────── */

    private static String explanation() {
        return "해".repeat(ProblemItemRule.EXPLANATION_MIN + 20);
    }

    /**
     * 제목까지 갖춘 정상 항목. 제목을 비워 두면 "제목 없음" 경고가 모든 항목에 붙어,
     * 이 테스트들이 재려던 경고(해설 길이 등)의 집계가 흐려진다.
     */
    private static GeneratedProblemItem item(String question, String explanation) {
        return new GeneratedProblemItem(question, "", explanation, choices(), "", "제목");
    }

    private static List<GeneratedProblemItem.GeneratedChoice> choices() {
        return List.of(
                new GeneratedProblemItem.GeneratedChoice("정답 보기", true),
                new GeneratedProblemItem.GeneratedChoice("오답 1", false),
                new GeneratedProblemItem.GeneratedChoice("오답 2", false),
                new GeneratedProblemItem.GeneratedChoice("오답 3", false));
    }
}
