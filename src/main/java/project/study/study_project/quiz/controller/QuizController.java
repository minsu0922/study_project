package project.study.study_project.quiz.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.global.response.ApiResponse;
import project.study.study_project.quiz.dto.QuizResponse;
import project.study.study_project.quiz.dto.QuizSubmitRequest;
import project.study.study_project.quiz.dto.QuizSubmitResponse;
import project.study.study_project.quiz.service.QuizService;

/**
 * 퀴즈 API — 풀이용 문제 조회(공개) + 답안 제출·채점(인증 필요). 명세는 docs/03.
 * 경로별 인증 규칙은 SecurityConfig: GET /api/quiz는 permitAll, POST /api/quiz/submit은 authenticated.
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

    /**
     * 답안 제출 → 즉시 채점 + 해설 반환. 예: {@code POST /api/quiz/submit} (Bearer 토큰 필수)
     *
     * <p>{@code @AuthenticationPrincipal Long userId}: JWT 필터가 SecurityContext에 심어 둔
     * principal(사용자 id, JwtTokenProvider 참고)을 꺼낸다. 제출자를 요청 바디가 아니라
     * <b>서명된 토큰에서</b> 가져오므로 다른 사람 명의로 제출을 위조할 수 없다.
     */
    @PostMapping("/submit")
    public ApiResponse<QuizSubmitResponse> submit(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody QuizSubmitRequest request
    ) {
        return ApiResponse.ok(quizService.submit(userId, request));
    }
}
