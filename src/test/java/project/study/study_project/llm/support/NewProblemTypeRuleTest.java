package project.study.study_project.llm.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.llm.client.GeneratedProblemItem;
import project.study.study_project.llm.client.GeneratedProblemItem.GeneratedChoice;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 짝짓기·순서 배열의 초안 검증과, <b>OX에 뒤늦게 메운 구멍</b>을 지킨다 — 2026-08-31 신설.
 *
 * <p><b>왜 OX 검사가 이제야 생겼나.</b> 이전에는 OX도 단답형과 같은 검사 하나("answer 없음")만
 * 걸려 있었다. 그래서 모델이 {@code "참"}이나 {@code "True"}를 적으면 초안은 멀쩡히 통과하고
 * 검수 화면에도 정상으로 보였다. 실제 채점은 {@code equalsIgnoreCase("O")} 비교라 그런 문제는
 * 학습자가 무엇을 눌러도 틀린다. 승인 단계에서 막히긴 했지만({@code AdminProblemService}),
 * 그건 <b>요금을 다 낸 뒤</b> 승인 버튼을 누른 순간에 이유도 안 보이는 채로 터지는 실패다.
 *
 * <p>기존 {@code ProblemItemRuleTest}에 덧붙이지 않고 파일을 나눈 이유: 그쪽은 <b>객관식</b>의
 * 품질 기준(해설 분량·오답 설명·형태 쏠림)을 다루는 파일이라 전제가 다르다. 유형이 다른 검사가
 * 섞이면 "이 파일이 무엇을 지키는지"가 흐려진다.
 */
class NewProblemTypeRuleTest {

    @Nested
    @DisplayName("OX — 뒤늦게 메운 구멍")
    class Ox {

        @Test
        @DisplayName("answer가 O/X가 아니면 초안 단계에서 걸린다 — 승인 때 터지면 이미 늦다")
        void rejectsNonOxAnswer() {
            assertThat(ProblemItemRule.defectOf(ox("참"), ProblemType.OX))
                    .contains("O 또는 X");
            assertThat(ProblemItemRule.defectOf(ox("True"), ProblemType.OX))
                    .contains("O 또는 X");
        }

        @Test
        @DisplayName("소문자 o/x는 통과한다 — 채점이 대소문자를 무시하므로 여기서 막으면 헛울린다")
        void acceptsLowerCase() {
            assertThat(ProblemItemRule.defectOf(ox("o"), ProblemType.OX)).isNull();
            assertThat(ProblemItemRule.defectOf(ox(" X "), ProblemType.OX)).isNull();
        }

        @Test
        @DisplayName("OX에 보기가 딸려 오면 걸린다 — 승인 때 requireNoChoices로 터지는 것을 앞당긴다")
        void rejectsChoicesOnOx() {
            GeneratedProblemItem withChoices = new GeneratedProblemItem(
                    "지문", "O", "해설",
                    List.of(new GeneratedChoice("보기", true), new GeneratedChoice("보기2", false)));

            assertThat(ProblemItemRule.defectOf(withChoices, ProblemType.OX)).contains("보기");
        }

        @Test
        @DisplayName("정답이 한쪽으로 다 몰리면 배치 경고 — 몰리면 지문을 안 읽고도 찍힌다")
        void warnsWhenAllSameSide() {
            List<GeneratedProblemItem> allTrue = List.of(ox("O"), ox("O"), ox("O"), ox("O"));
            List<GeneratedProblemItem> mixed = List.of(ox("O"), ox("X"), ox("O"), ox("O"));

            assertThat(ProblemItemRule.batchWarningsOf(allTrue, Difficulty.BEGINNER, ProblemType.OX))
                    .anyMatch(w -> w.contains("모두 O"));
            assertThat(ProblemItemRule.batchWarningsOf(mixed, Difficulty.BEGINNER, ProblemType.OX))
                    .noneMatch(w -> w.contains("한쪽으로"));
        }

        @Test
        @DisplayName("셋 이하인 배치는 쏠림을 재지 않는다 — 우연으로 흔해서 매번 울리면 목록을 안 보게 된다")
        void ignoresTinyBatch() {
            List<GeneratedProblemItem> two = List.of(ox("O"), ox("O"));

            assertThat(ProblemItemRule.batchWarningsOf(two, Difficulty.BEGINNER, ProblemType.OX))
                    .noneMatch(w -> w.contains("한쪽으로"));
        }

        private GeneratedProblemItem ox(String answer) {
            return new GeneratedProblemItem("OX 지문입니다.", answer, "해설", List.of());
        }
    }

    @Nested
    @DisplayName("짝짓기")
    class Matching {

        @Test
        @DisplayName("네 쌍이 다 채워져 있으면 통과한다")
        void acceptsFourPairs() {
            assertThat(ProblemItemRule.defectOf(matching(
                    pair("ㄱ", "가"), pair("ㄴ", "나"), pair("ㄷ", "다"), pair("ㄹ", "라")),
                    ProblemType.MATCHING)).isNull();
        }

        @Test
        @DisplayName("오른쪽이 겹치면 차단이다 — 어느 쪽에 이어도 정답이 되어 문제가 성립하지 않는다")
        void blocksDuplicateRight() {
            assertThat(ProblemItemRule.defectOf(matching(
                    pair("ㄱ", "같은 설명"), pair("ㄴ", "같은 설명"), pair("ㄷ", "다")),
                    ProblemType.MATCHING)).contains("오른쪽이 겹친다");
        }

        @Test
        @DisplayName("왼쪽이 겹쳐도 차단이다 — 짝이 하나로 정해지지 않는다")
        void blocksDuplicateLeft() {
            assertThat(ProblemItemRule.defectOf(matching(
                    pair("ㄱ", "가"), pair("ㄱ", "나"), pair("ㄷ", "다")),
                    ProblemType.MATCHING)).contains("왼쪽이 겹친다");
        }

        @Test
        @DisplayName("오른쪽이 빈 쌍이 있으면 차단이다")
        void blocksEmptyRight() {
            assertThat(ProblemItemRule.defectOf(matching(
                    pair("ㄱ", "가"), pair("ㄴ", ""), pair("ㄷ", "다")),
                    ProblemType.MATCHING)).contains("오른쪽이 빈 쌍");
        }

        @Test
        @DisplayName("오른쪽에 왼쪽 용어가 그대로 있으면 경고 — 읽는 즉시 짝이 보인다")
        void warnsWhenRightRepeatsLeftTerm() {
            GeneratedProblemItem item = matching(
                    pair("REPEATABLE READ", "REPEATABLE READ는 같은 행을 늘 같게 읽는다"),
                    pair("SERIALIZABLE", "순차 실행과 같은 결과를 보장한다"),
                    pair("READ COMMITTED", "커밋된 값만 읽는다"),
                    pair("READ UNCOMMITTED", "커밋되지 않은 값도 읽는다"));

            assertThat(ProblemItemRule.qualityWarningsOf(item, Difficulty.BEGINNER, false, ProblemType.MATCHING))
                    .anyMatch(w -> w.contains("왼쪽 용어가 그대로"));
        }

        private GeneratedProblemItem matching(GeneratedChoice... pairs) {
            return new GeneratedProblemItem("짝지으시오.", "", "해설", List.of(pairs));
        }

        private GeneratedChoice pair(String left, String right) {
            return GeneratedChoice.pair(left, right);
        }
    }

    @Nested
    @DisplayName("순서 배열")
    class Ordering {

        @Test
        @DisplayName("answer가 1..N의 순열이면 통과한다")
        void acceptsPermutation() {
            assertThat(ProblemItemRule.defectOf(ordering("4|2|1|3"), ProblemType.ORDERING)).isNull();
        }

        @Test
        @DisplayName("번호가 겹치면 차단 — 어떤 제출로도 맞힐 수 없는 문제가 된다")
        void blocksDuplicateSeq() {
            assertThat(ProblemItemRule.defectOf(ordering("1|1|2|3"), ProblemType.ORDERING))
                    .contains("순열이 아니다");
        }

        @Test
        @DisplayName("항목 수를 넘는 번호가 있으면 차단")
        void blocksOutOfRange() {
            assertThat(ProblemItemRule.defectOf(ordering("1|2|3|9"), ProblemType.ORDERING))
                    .contains("순열이 아니다");
        }

        @Test
        @DisplayName("번호가 모자라면 차단 — 셋만 적힌 네 항목짜리 문제")
        void blocksMissingSeq() {
            assertThat(ProblemItemRule.defectOf(ordering("1|2|3"), ProblemType.ORDERING))
                    .contains("빠진 번호");
        }

        @Test
        @DisplayName("항목에 순서를 알려 주는 말이 있으면 경고 — 그게 곧 답이다")
        void warnsWhenItemLeaksOrder() {
            GeneratedProblemItem item = new GeneratedProblemItem("배열하시오.", "1|2|3|4", "해설",
                    List.of(new GeneratedChoice("먼저 값을 검증한다", false),
                            new GeneratedChoice("DB에 쓴다", false),
                            new GeneratedChoice("캐시를 지운다", false),
                            new GeneratedChoice("재시도 큐에 넣는다", false)));

            assertThat(ProblemItemRule.qualityWarningsOf(item, Difficulty.INTERMEDIATE, false, ProblemType.ORDERING))
                    .anyMatch(w -> w.contains("순서를 알려 주는 말"));
        }

        /** 네 항목짜리 순서 배열. answer만 갈아 끼워 순열 검사를 본다. */
        private GeneratedProblemItem ordering(String answer) {
            return new GeneratedProblemItem("배열하시오.", answer, "해설",
                    List.of(new GeneratedChoice("ㄱ", false), new GeneratedChoice("ㄴ", false),
                            new GeneratedChoice("ㄷ", false), new GeneratedChoice("ㄹ", false)));
        }
    }
}
