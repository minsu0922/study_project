package project.study.study_project.llm.client;

import java.util.List;

/**
 * 오답 설명 생성기 추상화 — 실제 구현은 {@link ClaudeRationaleGenerator}(Claude API).
 *
 * <p>{@link TitleGenerator}와 같은 이유로 인터페이스를 둔다: 채우기 서비스가 하는 일
 * (보기 id로 짝짓기·자르기·빈 값 거르기·이미 채워진 것 건너뛰기)을 검증할 때마다 실제 API를
 * 부르면 돈이 들고 결과가 매번 달라 단정할 수가 없다. 테스트는 정해진 설명을 돌려주는 가짜를
 * 주입하고, 그래야 "모델이 한 건을 빠뜨리면 어떻게 되는가" 같은 <b>일부러 만들기 어려운 상황</b>도
 * 시험할 수 있다.
 */
public interface RationaleGenerator {

    /**
     * 문제들의 오답 보기에 붙일 설명을 만든다.
     *
     * @param problems 오답 설명이 빠진 문제들. 여러 건을 한 번에 넘긴다
     * @return 보기별 설명. <b>요청보다 적거나 순서가 다를 수 있다</b> — 짝짓기는 호출부가
     *         {@code choiceId}로 한다(그 이유는 {@link GeneratedRationale} 주석)
     */
    List<GeneratedRationale> generateRationales(List<ProblemWithoutRationale> problems);

    /**
     * 설명을 채울 대상 문제 하나.
     *
     * <p>엔티티({@code Problem})를 그대로 넘기지 않는 이유는 {@link TitleGenerator.UntitledProblem}과
     * 같다 — 클라이언트 계층이 도메인 엔티티에 묶이면 테스트에서 JPA 엔티티를 조립해야 한다.
     *
     * <p><b>제목 짓기와 달리 해설을 함께 준다.</b> 저쪽은 정답이 보이면 그걸 요약해 버려서
     * 일부러 감췄지만, 여기서는 반대다. 오답이 왜 틀렸는지는 <b>정답이 왜 맞는지를 알아야</b>
     * 쓸 수 있다. 해설을 감추면 모델이 정답을 자기 나름대로 추측하고, 그 추측이 틀리면
     * 오답 설명 전체가 엉뚱해진다.
     *
     * @param explanation 이 문제의 기존 해설. <b>모델은 이것을 고치지 않는다</b> —
     *                    돌려받는 스키마에 해설을 담을 자리가 아예 없다
     * @param wrongChoices 설명이 필요한 <b>오답</b> 보기들. 정답 보기는 {@link #correctChoiceText}로
     *                     따로 주는데, 섞이면 모델이 정답에도 설명을 달기 때문이다
     */
    record ProblemWithoutRationale(
            long problemId,
            String question,
            String explanation,
            String correctChoiceText,
            List<WrongChoice> wrongChoices
    ) {
        /** 오답 보기 하나 — id는 짝짓기 열쇠라 반드시 함께 다닌다. */
        public record WrongChoice(long choiceId, String text) {
        }
    }
}
