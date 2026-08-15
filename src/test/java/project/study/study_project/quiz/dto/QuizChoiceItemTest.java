package project.study.study_project.quiz.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.quiz.domain.Choice;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 보기 섞기 테스트 — 2026-08-15.
 *
 * <p><b>왜 섞게 됐나.</b> 승인된 17문제의 정답 위치를 세어 보니 1번 6개·2번 6개·3번 5개·
 * <b>4번 0개</b>였다. 균등하다면 4번이 하나도 없을 확률은 {@code (3/4)^17 ≈ 0.8%}다.
 * 모델이 정답을 뒤쪽에 잘 두지 않는 성향이 그대로 찍힌 것이고, 보기는 저장 순서 그대로
 * 화면에 나가므로 문제가 쌓이면 학습자가 "4번은 아니다"를 학습하게 된다.
 *
 * <p>이 테스트가 지키는 것은 섞기 그 자체보다 <b>섞은 뒤에도 화면이 맞게 도는가</b>이다.
 * seq를 다시 매기지 않으면 보기 번호 배지가 3·1·4·2로 찍히고 키보드 단축키와 어긋난다
 * (player.js가 seq를 배지로 쓰고 배열 순서를 단축키에 쓴다). 섞기만 하고 이 부분을
 * 놓치면 조용히 깨지는 종류의 버그다 — 예외는 안 나고 화면만 이상해진다.
 */
class QuizChoiceItemTest {

    /** 정답은 <b>맨 끝</b>에 둔다 — 섞이지 않으면 항상 4번이라 실패가 분명하게 드러난다. */
    private List<Choice> fourChoices() {
        return List.of(
                Choice.of(null, "오답1", false, 1),
                Choice.of(null, "오답2", false, 2),
                Choice.of(null, "오답3", false, 3),
                Choice.of(null, "정답", true, 4));
    }

    @Test
    @DisplayName("seq를 화면 순서대로 1..N 다시 매긴다 — 저장된 seq를 그대로 주면 번호 배지와 단축키가 어긋난다")
    void renumbersSeqInDisplayOrder() {
        List<QuizChoiceItem> items = QuizChoiceItem.shuffledFrom(fourChoices());

        assertThat(items).extracting(QuizChoiceItem::seq)
                .as("배열 순서와 seq가 같아야 화면 배지와 키보드 1~4가 맞는다")
                .containsExactly(1, 2, 3, 4);
    }

    @Test
    @DisplayName("보기가 빠지거나 늘지 않는다 — 섞기는 순서만 바꾸는 일이다")
    void keepsEveryChoice() {
        List<QuizChoiceItem> items = QuizChoiceItem.shuffledFrom(fourChoices());

        assertThat(items).extracting(QuizChoiceItem::text)
                .containsExactlyInAnyOrder("오답1", "오답2", "오답3", "정답");
    }

    /**
     * 정답이 특정 자리에 고정되지 않는지 본다. 200번이면 특정 위치가 한 번도 안 나올 확률이
     * {@code (3/4)^200}으로 사실상 0이라, 무작위성에 기대는 테스트지만 깜빡이지 않는다.
     */
    @Test
    @DisplayName("정답 위치가 한 자리에 고정되지 않는다 — 고정이면 학습자가 위치로 찍는다")
    void spreadsCorrectAnswerAcrossPositions() {
        Set<Integer> positions = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            List<QuizChoiceItem> items = QuizChoiceItem.shuffledFrom(fourChoices());
            for (QuizChoiceItem item : items) {
                if ("정답".equals(item.text())) {
                    positions.add(item.seq());
                }
            }
        }

        assertThat(positions).as("네 자리 모두에 정답이 놓인 적이 있어야 한다")
                .containsExactlyInAnyOrder(1, 2, 3, 4);
    }

    /**
     * 넘겨받은 목록은 JPA가 관리하는 컬렉션일 수 있다. 그 자리에서 섞으면 영속성 컨텍스트가
     * 변경으로 오해할 수 있어, 복사본을 섞는다. 이 테스트는 그 복사가 사라지는 것을 막는다.
     */
    @Test
    @DisplayName("원본 목록을 건드리지 않는다 — JPA 컬렉션을 그 자리에서 섞으면 안 된다")
    void doesNotMutateInput() {
        List<Choice> original = new ArrayList<>(fourChoices());
        List<String> before = original.stream().map(Choice::getText).toList();

        for (int i = 0; i < 50; i++) {
            QuizChoiceItem.shuffledFrom(original);
        }

        assertThat(original.stream().map(Choice::getText).toList()).isEqualTo(before);
    }
}
