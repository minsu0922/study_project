package project.study.study_project.llm.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.llm.client.GeneratedProblemItem;
import project.study.study_project.llm.client.QuestionKind;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 그림 자료 판별과 문법 경고. 진짜 mermaid는 브라우저에만 있어, 모델이 자주 틀리는 자리만 잰다. */
class DiagramExhibitRuleTest {

    /** 생성 프롬프트의 그림 본보기와 같은 그림. 본보기가 자기 검사에 걸리면 모델도 같은 꼴로 틀린다. */
    private static final String PROMPT_EXAMPLE = """
            sequenceDiagram
              participant A as 결제 API
              participant P as PG사
              A->>P: 결제 요청 (주문 1001)
              Note over A: 3초 시간 초과
              A->>P: 결제 요청 (주문 1001)
              P-->>A: 승인 (첫 요청)
              P-->>A: 승인 (두 번째 요청)
            """;

    private static GeneratedProblemItem withExhibit(String exhibit) {
        return new GeneratedProblemItem("지문", exhibit, "", "해설", List.of(
                new GeneratedProblemItem.GeneratedChoice("가", true),
                new GeneratedProblemItem.GeneratedChoice("나", false),
                new GeneratedProblemItem.GeneratedChoice("다", false),
                new GeneratedProblemItem.GeneratedChoice("라", false)),
                "", "제목", QuestionKind.SITUATION, "");
    }

    @Test
    @DisplayName("첫 줄이 sequenceDiagram·flowchart면 mermaid, 아니면 text로 저장한다")
    void detectsLanguageByFirstLine() {
        assertThat(withExhibit(PROMPT_EXAMPLE).exhibitLanguage()).isEqualTo("mermaid");
        assertThat(withExhibit("flowchart LR\n  A --> B").exhibitLanguage()).isEqualTo("mermaid");
        assertThat(withExhibit("graph TD\n  A --> B").exhibitLanguage()).isEqualTo("mermaid");
        assertThat(withExhibit("State  Count\nTIME-WAIT  28211").exhibitLanguage()).isEqualTo("text");
        // 방향이 없는 flowchart는 그림 종류로 보지 않는다. 화면의 isDiagramSource와 같은 규칙이다
        assertThat(withExhibit("flowchart\n  A --> B").exhibitLanguage()).isEqualTo("text");
        assertThat(withExhibit("").exhibitLanguage()).isNull();
    }

    @Test
    @DisplayName("프롬프트 본보기 그림은 경고 없이 통과한다")
    void promptExamplePasses() {
        assertThat(DiagramExhibitRule.warningsOf(withExhibit(PROMPT_EXAMPLE))).isEmpty();
    }

    @Test
    @DisplayName("시퀀스: 메시지 꼴이 아닌 줄과 ; 가 든 메시지를 잡는다")
    void flagsBrokenSequenceLines() {
        List<String> warnings = DiagramExhibitRule.warningsOf(withExhibit("""
                sequenceDiagram
                  C->>S: SYN
                  S 에게 ACK를 보낸다
                  S-->>C: ACK; 연결됨
                  loop 재시도
                  C->>S: 다시
                  end
                """));

        assertThat(warnings).hasSize(2);
        assertThat(warnings.get(0)).startsWith("그림 3번째 줄").contains("시퀀스 문법");
        assertThat(warnings.get(1)).startsWith("그림 4번째 줄").contains("; 나 #");
    }

    @Test
    @DisplayName("흐름도: 따옴표 없는 괄호와 짝 안 맞는 괄호를 잡고, 원통·따옴표 상자는 통과시킨다")
    void flagsBrokenFlowchartLines() {
        List<String> warnings = DiagramExhibitRule.warningsOf(withExhibit("""
                flowchart TD
                  A[주문 (재시도)] --> B{"재고가 있는가"}
                  B -->|예| C[("주문 DB")]
                  B -->|아니오| D["품절 (안내)"]
                  C --> E[결제
                  D --> F[(캐시)]
                """));

        assertThat(warnings).hasSize(2);
        assertThat(warnings.get(0)).startsWith("그림 2번째 줄").contains("따옴표");
        assertThat(warnings.get(1)).startsWith("그림 5번째 줄").contains("짝");
    }

    @Test
    @DisplayName("열지 않은 그림 종류는 글자로 보인다고 경고한다")
    void warnsUnsupportedDiagramKind() {
        GeneratedProblemItem item = withExhibit("stateDiagram-v2\n  [*] --> 열림");

        assertThat(item.exhibitLanguage()).isEqualTo("text");
        assertThat(ProblemItemRule.qualityWarningsOf(item, Difficulty.ADVANCED, true))
                .anySatisfy(w -> assertThat(w).contains("열지 않은 그림 종류"));
    }

    @Test
    @DisplayName("글자 자료에는 그림 문법 검사를 하지 않는다")
    void skipsTextExhibit() {
        assertThat(DiagramExhibitRule.warningsOf(withExhibit("id  type  rows\n1   ref   48210"))).isEmpty();
    }
}
