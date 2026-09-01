package project.study.study_project.quiz.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 답안 제출 요청 바디 — API 스펙(docs/03 POST /api/quiz/submit).
 *
 * <p>{@code userAnswer}의 타입별 규칙(스펙):
 * 객관식=선택한 choiceId 문자열(예 "2"), OX="O"/"X", 단답형=자유 텍스트,
 * 순서 배열=배열한 순서대로 choiceId를 {@code |}로 이은 것(예 "12|9|11|10"),
 * 짝짓기=<b>왼쪽 choiceId {@code -} 오른쪽 토큰</b> 쌍을 {@code |}로 이은 것
 * (예 "12-0f674f17c20b|9-d83a2e175f5e|…", 토큰은 {@code MatchToken}).
 *
 * <p>새 유형 둘은 <b>답이 하나의 배치</b>라 값이 아니라 나열로 온다. 길이 상한 500자는
 * 그대로 두는데, 항목이 넷이면 id·토큰을 다 합쳐도 100자를 넘지 않아 여유가 있다.
 *
 * <p>여기서는 형식만 검증하고(비어있지 않음, 길이), 타입별 해석·판정은 서비스의 채점 로직이 한다 —
 * 요청 시점엔 어떤 문제인지(=어떤 타입인지) 아직 모르기 때문에 여기서 타입별 검증은 불가능하다.
 * 그래서 나열이 깨진 제출(개수가 다름·같은 항목 두 번·남의 항목 id)은 <b>오답이 아니라</b>
 * {@code COMMON_001}(400)이다 — 형식이 틀린 것과 답이 틀린 것을 섞으면 오답노트가 오염된다.
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
