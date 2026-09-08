package project.study.study_project.quiz.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 기록 없이 정답만 확인할 때의 요청 바디 — {@code POST /api/quiz/{problemId}/check}.
 *
 * <p>{@code userAnswer}의 타입별 규칙은 {@link QuizSubmitRequest}와 <b>완전히 같다</b>.
 * 같은 채점 로직({@code QuizService.grade})이 읽기 때문이다.
 *
 * <p><b>왜 {@link QuizSubmitRequest}를 재사용하지 않았나.</b> 그쪽에는 {@code problemId}가
 * 바디에 있는데 여기서는 <b>경로</b>에 있다({@code /api/quiz/12/check}). 재사용하면 같은 값을
 * 두 곳에서 받게 되고, 둘이 다를 때 어느 쪽이 이기는지를 <b>컨트롤러가 정해야</b> 한다 —
 * 답이 하나로 정해지지 않는 규칙은 언젠가 반대로 구현된다. 필드가 하나뿐인 record를
 * 하나 더 두는 값은 그 모호함을 없애는 값이다.
 *
 * <p>제출자(userId)가 없는 것은 위조 방지 때문이 아니라 <b>기록 자체를 안 하기 때문</b>이다.
 * 이 요청은 비로그인도 부를 수 있다(SecurityConfig의 check 경로 규칙).
 */
public record QuizCheckRequest(

        @NotBlank(message = "userAnswer는 필수입니다.")
        @Size(max = 500, message = "답안은 500자 이하여야 합니다.")
        String userAnswer
) {
}
