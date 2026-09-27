package project.study.study_project.llm.cli;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.llm.client.ClaudeDocumentFactChecker;
import project.study.study_project.llm.client.FactCheckFinding;
import project.study.study_project.llm.client.FactCheckFinding.Confidence;
import project.study.study_project.llm.client.FactCheckFinding.Kind;
import project.study.study_project.llm.dto.GeneratedDocumentFile;
import project.study.study_project.llm.cli.ReviewEvalCli.Known;
import project.study.study_project.llm.cli.ReviewEvalCli.Planted;
import project.study.study_project.llm.cli.ReviewEvalCli.Sample;
import project.study.study_project.llm.cli.ReviewEvalCli.SampleFile;
import project.study.study_project.llm.cli.ReviewEvalCli.SampleScore;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 적발률 측정 도구의 오류 심기·채점 테스트 — Claude를 부르지 않는다.
 * 채점이 틀리면 그럴듯한 적발률이 나오고, 그 숫자로 검수기를 켤지 정하게 된다.
 */
class ReviewEvalCliTest {

    private static FactCheckFinding finding(String quote) {
        return new FactCheckFinding(quote, Kind.FACT_ERROR, "이유", "정정", Confidence.HIGH);
    }

    private static Planted planted(String id, String find, String replace, List<String> alsoAccept) {
        return new Planted(id, Kind.FACT_ERROR, null, find, replace, alsoAccept);
    }

    /* ── 오류 심기 ── */

    @Test
    @DisplayName("find를 replace로 바꿔 오류를 심는다")
    void plantsReplacement() {
        String result = ReviewEvalCli.plant("A는 1초다. B는 2초다.",
                List.of(planted("p", "A는 1초다.", "A는 1분이다.", null)));

        assertThat(result).isEqualTo("A는 1분이다. B는 2초다.");
    }

    @Test
    @DisplayName("find가 없거나 두 번 이상이면 요금을 쓰기 전에 멈춘다")
    void rejectsMissingOrAmbiguousFind() {
        assertThatThrownBy(() -> ReviewEvalCli.plant("A는 1초다.",
                List.of(planted("p", "C는 3초다.", "x", null))))
                .hasMessageContaining("0번");
        assertThatThrownBy(() -> ReviewEvalCli.plant("1초다. 1초다.",
                List.of(planted("p", "1초다.", "x", null))))
                .hasMessageContaining("2번");
    }

    /* ── 짝짓기 ── */

    @Test
    @DisplayName("인용이 목표를 품거나, 목표가 인용을 품거나, 20자 넘게 겹치면 같은 곳이다")
    void matchesOverlappingQuotes() {
        String key = "스프링은 요청마다 빈을 새로 만들어 각 요청에 따로 준다.";

        assertThat(ReviewEvalCli.matches("앞 문장. " + key + " 뒤 문장.", key)).isTrue();
        assertThat(ReviewEvalCli.matches("요청마다 빈을 새로 만들어", key)).isTrue();
        assertThat(ReviewEvalCli.matches("각 요청에 따로 준다. 의존 대상이 바뀌지 않는다는 보장", key)).isFalse();
        assertThat(ReviewEvalCli.matches("빈을 새로 만들어 각 요청에 따로 준다. 그러니 스레드마다", key)).isTrue();
    }

    @Test
    @DisplayName("너무 짧은 인용은 부분 일치로 치지 않는다")
    void rejectsTooShortQuotes() {
        assertThat(ReviewEvalCli.matches("빈을", "스프링은 요청마다 빈을 새로 만든다.")).isFalse();
    }

    /* ── 채점 ── */

    @Test
    @DisplayName("적발·놓침·헛경보를 가른다. 이미 잡은 목표를 또 가리킨 지적은 헛경보가 아니다")
    void scoresFindings() {
        Sample sample = new Sample("s", "doc.json", "BEGINNER",
                List.of(planted("p1", "원래 A", "심은 오류 첫째 문장입니다", null),
                        planted("p2", "원래 B", "심은 오류 둘째 문장입니다", List.of("짝이 되는 상대 문장"))),
                List.of(new Known("k1", Kind.INTERNAL_MISMATCH, null, List.of("원래 있던 오류 문장입니다"))));

        SampleScore score = ReviewEvalCli.score(sample, List.of(
                finding("심은 오류 첫째 문장입니다"),
                finding("심은 오류 첫째 문장입니다"),
                finding("원래 있던 오류 문장입니다"),
                finding("멀쩡한 문장을 틀렸다고 한 지적")));

        assertThat(score.caught()).containsOnlyKeys("p1", "k1");
        assertThat(score.missed()).extracting(ReviewEvalCli.Target::id).containsExactly("p2");
        assertThat(score.falseAlarms()).extracting(FactCheckFinding::quote)
                .containsExactly("멀쩡한 문장을 틀렸다고 한 지적");
    }

    @Test
    @DisplayName("불일치 오류는 상대 문장을 인용해도 적발로 친다")
    void acceptsPartnerQuoteForMismatch() {
        Sample sample = new Sample("s", "doc.json", "BEGINNER",
                List.of(planted("p", "원래", "각각 1만 번 증가시키는 예제다", List.of("i < 100_000"))),
                List.of());

        SampleScore score = ReviewEvalCli.score(sample,
                List.of(finding("for (int i = 0; i < 100_000; i++) {")));

        assertThat(score.caught()).containsOnlyKeys("p");
    }

    @Test
    @DisplayName("비용은 입력·출력(사고 포함) 단가로 계산하고, 단가를 모르는 모델은 금액을 적지 않는다")
    void rendersCost() {
        assertThat(ReviewEvalCli.renderCost("claude-opus-5", 1_000_000, 100_000)).contains("$7.50");
        assertThat(ReviewEvalCli.renderCost("unknown-model", 10, 10)).doesNotContain("$");
    }

    /* ── 실제 표본 파일 ── */

    @Test
    @DisplayName("표본 파일의 모든 오류가 지금 문서에 그대로 심기고, 원래 있던 오류 인용도 문서에 있다")
    void realSamplesStillApply() throws Exception {
        ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        SampleFile file = mapper.readValue(Path.of("eval-samples/fact-check.json").toFile(), SampleFile.class);

        assertThat(file.samples()).isNotEmpty();
        for (Sample s : file.samples()) {
            GeneratedDocumentFile doc = mapper.readValue(Path.of(s.document()).toFile(), GeneratedDocumentFile.class);
            String content = "ADVANCED".equals(s.edition())
                    ? doc.advancedDocument().contentMd() : doc.document().contentMd();

            String planted = ReviewEvalCli.plant(content, s.plantedOrEmpty());

            String normalized = ClaudeDocumentFactChecker.normalize(planted);
            for (Known k : s.knownOrEmpty()) {
                assertThat(k.quotes())
                        .as("%s/%s 인용 중 하나는 문서에 있어야 한다", s.id(), k.id())
                        .anyMatch(q -> normalized.contains(ClaudeDocumentFactChecker.normalize(q)));
            }
        }
    }
}
