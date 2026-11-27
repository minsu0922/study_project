package project.study.study_project.llm.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.llm.client.GeneratedProblemItem.GeneratedChoice;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratedProblemItemTypeTest {

    private static GeneratedProblemItem item() {
        return new GeneratedProblemItem("질문", "", "", "해설",
                List.of(new GeneratedChoice("가", true), new GeneratedChoice("나", false)),
                "", "제목", null, "");
    }

    @Test
    @DisplayName("유형이 없으면 넘겨준 유형을 쓴다")
    void typeOrFallsBackWhenNull() {
        assertThat(item().type()).isNull();
        assertThat(item().typeOr(ProblemType.MULTIPLE_CHOICE)).isEqualTo(ProblemType.MULTIPLE_CHOICE);
        assertThat(item().withType(ProblemType.OX).typeOr(ProblemType.MULTIPLE_CHOICE)).isEqualTo(ProblemType.OX);
    }

    @Test
    @DisplayName("유형 칸이 없는 옛 JSON은 유형 null로 읽힌다")
    void oldJsonWithoutTypeReadsNull() throws Exception {
        String json = """
                {"question":"q","exhibit":"","answer":"O","explanation":"e","choices":[],
                 "sourceQuote":"","title":"t","questionKind":null,"skipReason":""}
                """;
        GeneratedProblemItem read = new ObjectMapper().readValue(json, GeneratedProblemItem.class);
        assertThat(read.type()).isNull();
        assertThat(read.question()).isEqualTo("q");
    }

    @Test
    @DisplayName("지문이나 해설·보기만 바꾼 사본도 유형을 지킨다")
    void copiesKeepType() {
        GeneratedProblemItem ox = item().withType(ProblemType.ORDERING);

        assertThat(ox.withQuestion("새 질문").type()).isEqualTo(ProblemType.ORDERING);
        assertThat(ox.withQuestion("새 질문").question()).isEqualTo("새 질문");
        GeneratedProblemItem edited = ox.withExplanationAndChoices("새 해설", List.of(new GeneratedChoice("다", false)));
        assertThat(edited.type()).isEqualTo(ProblemType.ORDERING);
        assertThat(edited.explanation()).isEqualTo("새 해설");
        assertThat(edited.choices()).extracting(GeneratedChoice::text).containsExactly("다");
    }
}
