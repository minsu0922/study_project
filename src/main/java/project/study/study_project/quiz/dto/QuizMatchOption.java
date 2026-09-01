package project.study.study_project.quiz.dto;

import project.study.study_project.quiz.domain.Choice;
import project.study.study_project.quiz.support.MatchToken;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 짝짓기의 <b>오른쪽 항목</b> 하나 — 풀이용 응답에만 실린다(2026-08-31).
 *
 * <p><b>왼쪽은 {@link QuizChoiceItem}, 오른쪽은 이 record다.</b> 둘을 한 목록으로 합치지 않은
 * 이유는 화면이 두 열을 따로 그리기 때문이다. 한 목록에 넣고 "앞의 넷은 왼쪽" 같은 관례를
 * 두면, 그 관례를 모르는 코드가 하나만 생겨도 열이 뒤섞인다.
 *
 * <p><b>{@code id}가 아니라 {@code token}인 것이 이 record의 핵심이다.</b> 짝짓기는 한
 * {@code choice} 행이 한 쌍이라(V16), 오른쪽을 행 id와 함께 내보내면 왼쪽 id와 맞춰 보는 것만으로
 * 답이 드러난다. 자세한 사정은 {@link MatchToken} 주석에 있다.
 *
 * @param token 채점 제출 시 이 항목을 가리키는 값. 사용자 답은
 *              {@code "왼쪽보기id-토큰|왼쪽보기id-토큰|…"} 형태다(docs/03)
 */
public record QuizMatchOption(
        String token,
        String text
) {
    /**
     * 오른쪽 열을 <b>섞어서</b> 내보낸다.
     *
     * <p><b>섞지 않으면 문제가 성립하지 않는다.</b> 보기 섞기({@link QuizChoiceItem#shuffledFrom})는
     * 정답 위치 편향을 지우려는 <b>개선</b>이지만, 이쪽은 안 섞으면 왼쪽 n번째와 오른쪽 n번째가
     * 그대로 짝이 되어 답이 통째로 노출된다. 같은 "섞는다"라도 성격이 다르다 —
     * 그래서 여기에는 "섞지 않는 선택지"가 없다.
     *
     * <p>{@code seq}를 다시 매기지 않는 이유: 오른쪽 항목은 키보드 단축키로 고르지 않는다.
     * 학습자는 왼쪽을 누르고 오른쪽을 누르는 두 번의 클릭으로 한 쌍을 잇는다(player.js).
     * 화면에 번호를 찍을 일이 없으니 번호를 만들지 않는다.
     *
     * @param problemId 토큰 계산에 함께 들어간다({@link MatchToken#of} 주석 참고)
     * @param choices   짝짓기 문제의 보기 행. {@code matchText}가 없는 행은 조용히 건너뛴다 —
     *                  유형이 잘못 저장된 데이터가 있어도 조회는 계속되어야 한다
     */
    public static List<QuizMatchOption> shuffledFrom(Long problemId, List<Choice> choices) {
        List<QuizMatchOption> options = new ArrayList<>(choices.size());
        for (Choice c : choices) {
            String token = MatchToken.of(problemId, c.getMatchText());
            if (token != null) {
                options.add(new QuizMatchOption(token, c.getMatchText().trim()));
            }
        }
        // 난수원을 명시하는 이유는 QuizChoiceItem.shuffledFrom 주석과 같다(정적 Random 경합 회피).
        Collections.shuffle(options, ThreadLocalRandom.current());
        return Collections.unmodifiableList(options);
    }
}
