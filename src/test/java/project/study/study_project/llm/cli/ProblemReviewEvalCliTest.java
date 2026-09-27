package project.study.study_project.llm.cli;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.llm.client.GeneratedProblemItem;
import project.study.study_project.llm.client.GeneratedProblemItem.GeneratedChoice;
import project.study.study_project.llm.client.ProblemReview.Finding;
import project.study.study_project.llm.client.ProblemReview.FindingType;
import project.study.study_project.llm.cli.ProblemReviewEvalCli.Group;
import project.study.study_project.llm.cli.ProblemReviewEvalCli.GroupScore;
import project.study.study_project.llm.cli.ProblemReviewEvalCli.Mutation;
import project.study.study_project.llm.cli.ProblemReviewEvalCli.ProblemRef;
import project.study.study_project.llm.cli.ProblemReviewEvalCli.SampleFile;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 문제 검수 측정 도구의 오류 심기·채점 테스트 — Claude를 부르지 않는다. */
class ProblemReviewEvalCliTest {

    private static final GeneratedProblemItem MC = new GeneratedProblemItem("스키마란?", "", "해설", List.of(
            new GeneratedChoice("스키마", true), new GeneratedChoice("테이블", false),
            new GeneratedChoice("컬럼", false), new GeneratedChoice("행", false)));

    @Test
    @DisplayName("정답 표시를 옮기면 정답이 정확히 하나, 옮긴 자리에 있다")
    void movesAnswer() {
        GeneratedProblemItem moved = ProblemReviewEvalCli.mutate(MC, List.of(new Mutation(2, null, null)));

        assertThat(moved.choices()).extracting(GeneratedChoice::correct).containsExactly(false, false, true, false);
    }

    @Test
    @DisplayName("보기 문장을 바꿔도 정답 표시는 그대로다")
    void replacesChoiceText() {
        GeneratedProblemItem changed = ProblemReviewEvalCli.mutate(MC, List.of(new Mutation(null, 1, "데이터베이스 구조")));

        assertThat(changed.choices().get(1).text()).isEqualTo("데이터베이스 구조");
        assertThat(changed.choices()).extracting(GeneratedChoice::correct).containsExactly(true, false, false, false);
    }

    @Test
    @DisplayName("심은 문제는 기대한 종류가 나와야 적발이고, 멀쩡한 문제의 지적은 헛경보 후보다")
    void scoresGroup() {
        Group g = new Group("g", "doc.json", "BEGINNER", Difficulty.BEGINNER, List.of(
                new ProblemRef("b.json", 0, null, null, List.of(FindingType.ANSWER_MISMATCH)),
                new ProblemRef("b.json", 1, null, null, List.of(FindingType.CHOICE_CUE_LEAK)),
                new ProblemRef("b.json", 2, null, null, null)));

        GroupScore score = ProblemReviewEvalCli.score(g, List.of(
                new Finding(0, FindingType.ANSWER_MISMATCH, "m"),
                new Finding(1, FindingType.NO_SUPPORT, "다른 종류"),
                new Finding(2, FindingType.DIFFICULTY_MISMATCH, "헛경보")));

        assertThat(score.caught()).hasSize(1);
        assertThat(score.missed()).hasSize(1);
        assertThat(score.falseAlarms()).extracting(Finding::type).containsExactly(FindingType.DIFFICULTY_MISMATCH);
    }

    @Test
    @DisplayName("규칙 경고를 같은 뜻의 AI 지적 종류로 옮기고, 대응이 없는 경고는 버린다")
    void mapsRuleWarningsToFindingTypes() {
        assertThat(ProblemReviewEvalCli.ruleTypeOf("정답이 가장 긴 보기 (60자 vs 최단 30자, 2.00배 — 기준 1.5배 이하)"))
                .isEqualTo(FindingType.CHOICE_CUE_LEAK);
        assertThat(ProblemReviewEvalCli.ruleTypeOf("정답 보기가 질문을 12자 되풀이함 (\"x\" — 오답은 2자)"))
                .isEqualTo(FindingType.QUESTION_REVEALS);
        assertThat(ProblemReviewEvalCli.ruleTypeOf("초급 지문이 김 (150자, 기준 120자 — 상황 서술이 붙었을 수 있다)"))
                .isEqualTo(FindingType.DIFFICULTY_MISMATCH);
        assertThat(ProblemReviewEvalCli.ruleTypeOf("제목 없음")).isNull();
    }

    @Test
    @DisplayName("표본 파일의 모든 묶음이 지금 파일들로 준비된다 — 문제 파일이 바뀌면 여기서 먼저 깨진다")
    void realSamplesPrepare() throws Exception {
        ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        SampleFile file = mapper.readValue(Path.of("eval-samples/problem-review.json").toFile(), SampleFile.class);

        assertThat(file.groups()).isNotEmpty();
        for (Group g : file.groups()) {
            var prepared = ProblemReviewEvalCli.prepare(g);
            assertThat(prepared.problems()).hasSize(g.problems().size());
            assertThat(prepared.source().contentMd()).isNotBlank();
            for (GeneratedProblemItem p : prepared.problems()) {
                assertThat(p.choices()).as(g.id()).hasSize(4);
                assertThat(p.choices().stream().filter(GeneratedChoice::correct).count()).as(g.id()).isEqualTo(1);
            }
        }
    }
}
