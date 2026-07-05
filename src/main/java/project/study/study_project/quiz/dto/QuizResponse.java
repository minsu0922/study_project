package project.study.study_project.quiz.dto;

import java.util.List;

/**
 * GET /api/quiz 응답 — {@code { "problems": [...] }} 모양(docs/03).
 *
 * <p>배열을 바로 반환하지 않고 객체로 한 번 감싼 이유: 나중에 총 개수·필터 정보 같은
 * 메타데이터를 추가해도 응답 구조가 깨지지 않는다(배열 루트는 확장이 불가능).
 */
public record QuizResponse(
        List<QuizProblemItem> problems
) {
}
