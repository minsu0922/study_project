package project.study.study_project.quiz.dto;

import project.study.study_project.quiz.domain.Choice;

import java.util.List;

/**
 * 채점 뒤에 밝혀지는 보기 하나의 진실 — 2026-08-27 신설(V15).
 *
 * <p><b>왜 text를 안 싣나.</b> 이 값은 <b>답을 이미 제출한 화면</b>으로만 간다. 그 화면은
 * 방금 보기를 그렸으므로 문장도 순번도 이미 알고 있다. 필요한 것은 "그 보기가 정답이었나"와
 * "왜 틀렸나"뿐이고, {@code id}로 짝을 맞추면 된다.
 *
 * <p><b>그리고 이것이 번호 문제를 푸는 자리다.</b> 보기는 요청마다 다시 섞여 나가므로
 * ({@code QuizChoiceItem.shuffledFrom}) 서버는 학습자 화면의 번호를 알지 못한다. 그런데
 * 번호를 아는 쪽은 화면이다 — 자기가 방금 ①②③④를 찍었다. 그래서 서버는 <b>id로만</b> 말하고
 * 번호는 화면이 붙인다. 해설에 "②번"이라고 적을 수 없었던 이유가 여기서 사라진다.
 *
 * @param id        {@code QuizChoiceItem.id}와 같은 값 — 화면이 이것으로 짝을 찾는다
 * @param correct   이 보기가 정답이었는지. 풀이 전에는 절대 나가면 안 되는 값이라
 *                  {@code QuizChoiceItem}에는 없고 여기에만 있다
 * @param rationale 이 오답이 왜 틀렸는지 한 줄. 정답 보기와 옛 문제는 {@code null}
 */
public record QuizChoiceResult(
        Long id,
        boolean correct,
        String rationale
) {
    /**
     * 저장 순서 그대로 옮긴다 — <b>섞지 않는다.</b>
     *
     * <p>화면은 {@code id}로 짝을 찾으므로 여기서 순서를 맞춰 줄 이유가 없다. 오히려 섞으면
     * "이 목록의 순서에 뜻이 있나?"라는 오해를 만든다. 순서에 뜻이 있는 목록은
     * {@code QuizChoiceItem} 하나뿐이어야 한다.
     */
    public static List<QuizChoiceResult> from(List<Choice> choices) {
        return choices.stream()
                .map(c -> new QuizChoiceResult(c.getId(), c.isCorrect(), c.getRationale()))
                .toList();
    }
}
