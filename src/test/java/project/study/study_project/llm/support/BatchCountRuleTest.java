package project.study.study_project.llm.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import project.study.study_project.global.common.Difficulty;

import java.io.InputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 난이도별 생성 개수 규칙 — 2026-09-05 신설.
 *
 * <p>여기서 지키는 것은 셋이다: <b>배분이 실제로 읽히는가</b>, <b>망가진 설정이 배치를 죽이지
 * 않는가</b>, <b>요금이 걸린 숫자는 그래도 막히는가</b>. 셋째와 둘째가 부딪히는 자리가 이
 * 규칙의 핵심이라 특히 촘촘히 잰다 — 오타는 넘어가고 큰 숫자는 막아야 한다.
 */
class BatchCountRuleTest {

    @Nested
    @DisplayName("배분 읽기")
    class Parsing {

        @Test
        @DisplayName("세 난이도를 각각 읽는다")
        void readsAllThree() {
            Map<Difficulty, Integer> counts =
                    BatchCountRule.parse("BEGINNER=7,INTERMEDIATE=5,ADVANCED=3");

            assertThat(counts)
                    .containsEntry(Difficulty.BEGINNER, 7)
                    .containsEntry(Difficulty.INTERMEDIATE, 5)
                    .containsEntry(Difficulty.ADVANCED, 3);
        }

        @Test
        @DisplayName("공백과 소문자를 받아 준다 — 설정 파일을 손으로 고치는 사람이 있다")
        void toleratesSpacingAndCase() {
            assertThat(BatchCountRule.parse(" beginner = 7 , ADVANCED=3 "))
                    .containsEntry(Difficulty.BEGINNER, 7)
                    .containsEntry(Difficulty.ADVANCED, 3);
        }

        @Test
        @DisplayName("비었거나 null이면 빈 맵 — 부르는 쪽이 폴백으로 간다")
        void emptyMeansNoOpinion() {
            assertThat(BatchCountRule.parse(null)).isEmpty();
            assertThat(BatchCountRule.parse("  ")).isEmpty();
        }
    }

    /**
     * <b>망가진 항목 하나가 그날 배치를 통째로 죽이면 안 된다.</b>
     *
     * <p>{@code batch-enabled} 키가 없을 때 켜진 것으로 보는 판단과 같은 성질이다 — 설정 오타로
     * 배치가 멈추면 "왜 요즘 문제가 안 들어오지?"를 몇 주 뒤에 알게 되는데, 이 저장소가 실제로
     * 두 번 당한 사고가 그것이다(docs/14). 못 읽은 항목만 버리고 나머지는 살린다.
     */
    @Nested
    @DisplayName("망가진 설정")
    class Broken {

        @Test
        @DisplayName("못 읽는 항목만 버리고 나머지는 살린다")
        void skipsOnlyTheBrokenEntry() {
            assertThat(BatchCountRule.parse("BEGINNER=7,BOGUS=4,INTERMEDIATE=다섯,ADVANCED=3"))
                    .as("없는 난이도와 숫자 아닌 값은 건너뛴다")
                    .containsEntry(Difficulty.BEGINNER, 7)
                    .containsEntry(Difficulty.ADVANCED, 3)
                    .doesNotContainKey(Difficulty.INTERMEDIATE);
        }

        @Test
        @DisplayName("등호가 없는 조각도 건너뛴다")
        void skipsEntriesWithoutEquals() {
            assertThat(BatchCountRule.parse("BEGINNER,ADVANCED=3"))
                    .containsExactly(Map.entry(Difficulty.ADVANCED, 3));
        }

        /**
         * 오타는 넘어가면서 <b>범위 밖 숫자는 던지는</b> 것이 이 규칙에서 가장 미묘한 자리다.
         * 갈리는 기준은 "사람이 무엇을 의도했는지 아는가"다 — {@code BOGUS=4}는 무엇을 뜻하는지
         * 알 수 없어 버릴 수밖에 없지만, {@code BEGINNER=100}은 뜻이 또렷하고 <b>요금이 걸려
         * 있다</b>. 조용히 7로 잘라 쓰면 100을 적은 사람은 100이 나온 줄 안다
         * ({@code GenerationLimits} 주석의 판단 그대로).
         */
        @Test
        @DisplayName("범위 밖 숫자는 던진다 — 오타는 넘어가도 요금이 걸린 값은 못 넘어간다")
        void rejectsOutOfRangeNumbers() {
            assertThatThrownBy(() -> BatchCountRule.parse("BEGINNER=100"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .as("어느 난이도를 고쳐야 하는지 메시지에 있어야 한다")
                    .hasMessageContaining("BEGINNER")
                    .hasMessageContaining("batch-count-by-difficulty");

            assertThatThrownBy(() -> BatchCountRule.parse("ADVANCED=0"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("오늘 개수 고르기")
    class Choosing {

        @Test
        @DisplayName("난이도별 값이 있으면 그것, 없으면 폴백")
        void picksPerDifficultyThenFallback() {
            String spec = "BEGINNER=7,ADVANCED=3";

            assertThat(BatchCountRule.countFor(spec, Difficulty.BEGINNER, 5)).isEqualTo(7);
            assertThat(BatchCountRule.countFor(spec, Difficulty.INTERMEDIATE, 5)).isEqualTo(5);
            assertThat(BatchCountRule.countFor(null, Difficulty.BEGINNER, 5)).isEqualTo(5);
        }

        @Test
        @DisplayName("난이도가 없으면(문서일) 폴백 — 개수와 무관한 경로가 이 규칙 때문에 죽으면 안 된다")
        void documentDayGetsTheFallback() {
            assertThat(BatchCountRule.countFor("BEGINNER=7", null, 5)).isEqualTo(5);
        }
    }

    /**
     * <b>설정 파일과 코드의 기본값이 갈라지지 않게 대조한다.</b>
     *
     * <p>{@code application.yml}이 진짜 출처인데 기본값 문자열이 {@link BatchCountRule#DEFAULT_SPEC}과
     * {@code AdminBatchService}의 {@code @Value} 두 곳에 더 있다. 같은 값이 세 곳에 적혀 있으면
     * 언젠가 한쪽만 바뀌고, 그때 <b>화면이 말하는 개수와 배치가 만드는 개수가 달라진다</b> —
     * 이 저장소가 반복해 겪은 실패 방식이라({@code ProblemItemRule} 클래스 주석) 기계가 잡는다.
     *
     * <p>세 벌을 한 벌로 합칠 수 없는 이유는 {@code @Value}가 상수 표현식만 받기 때문이다.
     * 합칠 수 없으면 <b>어긋났을 때 울리게</b> 하는 것이 다음으로 좋은 수다.
     */
    @Test
    @DisplayName("application.yml의 배분과 코드 기본값이 같다 — 갈라지면 화면과 배치가 다른 말을 한다")
    void configAndCodeDefaultAgree() throws Exception {
        String configured;
        try (InputStream in = BatchCountRuleTest.class.getResourceAsStream("/application.yml")) {
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> llm = (Map<String, Object>) root.get("llm");
            Map<String, Object> generation = (Map<String, Object>) llm.get("generation");
            configured = (String) generation.get("batch-count-by-difficulty");
        }

        assertThat(configured)
                .as("설정 키가 사라지면 배치가 조용히 옛 개수로 돌아간다")
                .isNotNull();
        assertThat(BatchCountRule.parse(configured))
                .as("설정과 코드 기본값이 같은 배분을 말해야 한다")
                .isEqualTo(BatchCountRule.parse(BatchCountRule.DEFAULT_SPEC));

        assertThat(BatchCountRule.parse(configured))
                .as("초급이 가장 많고 고급이 가장 적다 — 이 순서가 이번 변경의 목적 자체다")
                .hasSize(3)
                .satisfies(counts -> assertThat(counts.get(Difficulty.BEGINNER))
                        .isGreaterThan(counts.get(Difficulty.INTERMEDIATE)))
                .satisfies(counts -> assertThat(counts.get(Difficulty.INTERMEDIATE))
                        .isGreaterThan(counts.get(Difficulty.ADVANCED)));
    }
}
