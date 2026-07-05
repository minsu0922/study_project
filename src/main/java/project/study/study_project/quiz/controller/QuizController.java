package project.study.study_project.quiz.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.global.response.ApiResponse;
import project.study.study_project.quiz.dto.QuizResponse;
import project.study.study_project.quiz.service.QuizService;

/**
 * 퀴즈 API — 풀이용 문제 조회. 명세는 docs/03. 인증 없이 공개(SecurityConfig의 GET /api/quiz).
 *
 * <p>제출/채점(POST /api/quiz/submit)은 다음 단계에서 이 컨트롤러에 추가한다.
 */
@RestController
@RequestMapping("/api/quiz")
@RequiredArgsConstructor
public class QuizController {

    private final QuizService quizService;

    /**
     * 필터로 문제 N개 조회. 예: {@code GET /api/quiz?domain=NETWORK&level=BEGINNER&type=MULTIPLE_CHOICE&size=10}
     *
     * <p>enum 파라미터(domain/level/type)는 스프링이 상수명 문자열로 자동 변환하고,
     * 잘못된 값(예 {@code domain=FOO})은 전역 예외처리기가 400으로 응답한다.
     * 파라미터명 {@code level}은 스펙(docs/03) 표기를 따랐다(내부 명칭은 difficulty).
     */
    @GetMapping
    public ApiResponse<QuizResponse> getQuiz(
            @RequestParam(required = false) Domain domain,
            @RequestParam(required = false) Difficulty level,
            @RequestParam(required = false) ProblemType type,
            @RequestParam(required = false, defaultValue = "" + QuizService.DEFAULT_SIZE) int size
    ) {
        return ApiResponse.ok(quizService.getQuiz(domain, level, type, size));
    }
}
