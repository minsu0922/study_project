package project.study.study_project.quiz.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 답안 제출 요청 바디 — API 스펙(docs/03 POST /api/quiz/submit).
 *
 * <p>{@code userAnswer}의 타입별 규칙(스펙):
 * 객관식=선택한 choiceId 문자열(예 "2"), OX="O"/"X", 단답형=자유 텍스트.
 * 여기서는 형식만 검증하고(비어있지 않음, 길이), 타입별 해석·판정은 서비스의 채점 로직이 한다 —
 * 요청 시점엔 어떤 문제인지(=어떤 타입인지) 아직 모르기 때문에 여기서 타입별 검증은 불가능하다.
 *
 * <p>제출자(userId)는 바디에 없다 — JWT에서 꺼낸다. 바디로 받으면 남의 id로 제출을 위조할 수 있다.
 */
public record QuizSubmitRequest(

        @NotNull(message = "problemId는 필수입니다.")
        Long problemId,

        @NotBlank(message = "userAnswer는 필수입니다.")
        @Size(max = 500, message = "답안은 500자 이하여야 합니다.") // DB 컬럼(VARCHAR 500) 초과 방지
        String userAnswer
) {
}
