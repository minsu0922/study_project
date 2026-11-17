package project.study.study_project.llm.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.llm.client.GeneratedProblemItem;
import project.study.study_project.llm.client.ProblemReview.Finding;
import project.study.study_project.llm.client.ProblemReview.FindingType;
import project.study.study_project.llm.client.ProblemReview.FixTarget;
import project.study.study_project.llm.client.ProblemReview.Suggestion;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DraftAiFindingTest {

    private static final List<GeneratedProblemItem> PROBLEMS = List.of(new GeneratedProblemItem("문제?", "", "해설", List.of()));

    @Test
    @DisplayName("AI가 고쳐 넣은 지적은 처리된 것으로 저장한다")
    void insertedRewritesAreResolved() {
        var grouped = DraftAiFinding.groupByQuestion(PROBLEMS, List.of(
                new Finding(0, FindingType.EXPLANATION_REWRITTEN, "고쳐 넣음", "옛 해설:\n해설"),
                new Finding(0, FindingType.RATIONALE_REWRITTEN, "고쳐 넣음", "옛 오답 설명: 짧다")));

        assertThat(grouped.of("문제?")).extracting(DraftAiFinding::resolution)
                .containsOnly(DraftAiFinding.AI_INSERTED);
    }

    @Test
    @DisplayName("제안으로 남은 해설 지적은 미처리다")
    void suggestionsStayOpen() {
        var grouped = DraftAiFinding.groupByQuestion(PROBLEMS, List.of(
                new Finding(0, FindingType.EXPLANATION_SUGGESTED, "고친 안", "넣지 않은 이유: [B5]",
                        new Suggestion(FixTarget.EXPLANATION, null, "새 해설"))));

        assertThat(grouped.of("문제?")).singleElement().satisfies(f -> {
            assertThat(f.resolution()).isNull();
            assertThat(f.suggestion()).isNotNull();
        });
    }

    @Test
    @DisplayName("옛 검수가 낸 지적 종류가 담긴 JSON도 읽힌다")
    void readsLegacyFindingTypes() throws Exception {
        String json = """
                [{"type":"CHOICE_CUE_LEAK","message":"m","detail":null},
                 {"type":"DIFFICULTY_MISMATCH","message":"m","detail":null},
                 {"type":"AWKWARD_SENTENCE","message":"m","detail":"d"},
                 {"type":"QUESTION_REVEALS","message":"m","detail":null,"resolution":"영향 없음"}]""";

        List<DraftAiFinding> read = new ObjectMapper().readValue(json, new TypeReference<>() {
        });

        assertThat(read).extracting(DraftAiFinding::type).containsExactly(FindingType.CHOICE_CUE_LEAK,
                FindingType.DIFFICULTY_MISMATCH, FindingType.AWKWARD_SENTENCE, FindingType.QUESTION_REVEALS);
    }
}
