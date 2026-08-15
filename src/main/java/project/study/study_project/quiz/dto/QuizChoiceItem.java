package project.study.study_project.quiz.dto;

import project.study.study_project.quiz.domain.Choice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 풀이용 보기 항목 — API 스펙(docs/03 GET /api/quiz).
 *
 * <p><b>{@code isCorrect}(정답 여부)는 의도적으로 뺐다.</b> 엔티티를 그대로 직렬화하면
 * 정답이 응답에 실려 퀴즈가 무의미해진다 — "엔티티와 API 응답을 DTO로 분리하는" 대표적인 이유.
 *
 * @param id  채점 제출 시 사용자가 고른 보기를 식별하는 값(userAnswer로 보냄)
 * @param seq 화면 표시 순서(1..N). <b>DB의 저장 순서가 아니라 이번 응답에서의 순서</b>다
 *            — 아래 {@link #shuffledFrom} 주석 참고.
 */
public record QuizChoiceItem(
        Long id,
        int seq,
        String text
) {
    /**
     * 보기 목록을 <b>섞어서</b> 내보낸다.
     *
     * <p><b>왜 섞는가.</b> 승인된 17문제의 정답 위치를 세어 보니 1번 6개·2번 6개·3번 5개·
     * <b>4번 0개</b>였다. 보기가 균등하게 흩어져 있다면 4번이 하나도 없을 확률은
     * {@code (3/4)^17 ≈ 0.8%}다. 모델이 정답을 뒤쪽에 잘 두지 않는 성향이 그대로 찍힌 것이고,
     * 보기는 {@code @OrderBy("seq ASC")}로 저장 순서 그대로 나가므로 학습자에게 노출된다.
     * 문제가 쌓이면 "4번은 아니다"를 학습하게 된다.
     *
     * <p><b>왜 프롬프트가 아니라 여기서 고치는가.</b> 위치 편향은 지시로 잘 고쳐지지 않는
     * 모델의 성향이다. "정답 위치를 흩어라"를 프롬프트에 적어 두고 지켜지길 바라는 것보다,
     * 내보내는 순간에 섞는 쪽이 확실하다. 채점은 보기 <b>id</b>로 하므로 순서를 바꿔도 안전하다
     * ({@code QuizService.gradeMultipleChoice}).
     *
     * <p><b>seq를 다시 매기는 이유.</b> 화면이 이 값을 보기 번호 배지로 찍고 키보드 1~N 단축키와
     * 짝지운다(player.js). 섞어 놓고 저장된 seq를 그대로 주면 배지가 3·1·4·2로 나오고
     * 단축키와도 어긋난다. seq는 "저장 순서"가 아니라 <b>"이번 화면에서의 순서"</b>다.
     *
     * <p><b>요청마다 다시 섞는다.</b> 같은 문제를 오답노트나 복습에서 다시 만날 때 순서가
     * 달라져야 위치를 외우는 것을 막는다 — 문제당 한 번만 섞어 고정하는 대안도 있었지만,
     * 그러면 재도전할 때 "두 번째 것"이라는 기억만으로 맞힐 수 있다.
     */
    public static List<QuizChoiceItem> shuffledFrom(List<Choice> choices) {
        // JPA가 관리하는 컬렉션을 그 자리에서 섞으면 영속성 컨텍스트가 변경으로 오해할 수 있다.
        // 복사본을 섞는다 — 어차피 DTO로 옮길 것이라 비용도 없다.
        List<Choice> copy = new ArrayList<>(choices);
        Collections.shuffle(copy);

        List<QuizChoiceItem> items = new ArrayList<>(copy.size());
        for (int i = 0; i < copy.size(); i++) {
            Choice c = copy.get(i);
            items.add(new QuizChoiceItem(c.getId(), i + 1, c.getText()));
        }
        return List.copyOf(items);
    }
}
