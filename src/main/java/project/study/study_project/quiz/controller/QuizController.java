package project.study.study_project.quiz.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.global.response.ApiResponse;
import project.study.study_project.quiz.dto.QuizCheckRequest;
import project.study.study_project.quiz.dto.QuizResponse;
import project.study.study_project.quiz.dto.QuizSubmitRequest;
import project.study.study_project.quiz.dto.QuizSubmitResponse;
import project.study.study_project.quiz.service.QuizService;

import java.util.List;

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
     * 지금 <b>고를 수 있는</b> 문제 유형 — 자유 퀴즈의 유형 칸을 채운다(2026-09-08).
     *
     * <p>경로가 {@code /{problemId}}보다 <b>앞에</b> 놓여 있지만 순서 때문은 아니다. 스프링은 글자
     * 그대로인 경로를 변수 경로보다 먼저 맞춰 보므로 {@code /types}가 id로 해석될 일은 없다.
     * 사람이 읽을 때 "목록을 채우는 것 → 하나를 꺼내는 것" 차례가 자연스러워 여기에 둔다.
     *
     * <p>공개다. 비로그인도 자유 퀴즈를 풀 수 있으므로(익명 채점) 필터도 함께 열려 있어야 한다.
     */
    @GetMapping("/types")
    public ApiResponse<List<ProblemType>> types() {
        return ApiResponse.ok(quizService.availableTypes());
    }

    /**
     * 문제 하나만 — 목록 화면에서 "이걸 풀자"고 눌러 들어올 때(docs/18).
     *
     * <p>{@code /api/quiz}와 같은 {@link QuizResponse}(한 칸짜리 세트)로 돌려준다 —
     * 풀이 화면이 이미 배열을 받아 도는 구조라, 전용 형태를 만들면 화면에 분기가 하나 생긴다.
     *
     * <p>목록과 마찬가지로 <b>공개</b>다. 로그인 없이도 문제 자체는 볼 수 있어야 하고
     * (SecurityConfig의 {@code GET /api/quiz/**} permitAll), 채점만 로그인을 요구한다.
     */
    @GetMapping("/{problemId}")
    public ApiResponse<QuizResponse> getOne(@PathVariable Long problemId) {
        return ApiResponse.ok(quizService.getOne(problemId));
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

    /**
     * <b>기록 없이 정답만 확인</b> — 비로그인도 부를 수 있다. 예: {@code POST /api/quiz/12/check}
     *
     * <p>왜 열려 있는지와 무엇이 달라졌는지는 {@link QuizService#check}에 적어 뒀다.
     * 한 줄로: 로그인이 여는 것은 정답 확인이 아니라 <b>이력·복습·오늘의 퀴즈</b>다.
     *
     * <p><b>왜 {@code /submit}에 플래그를 더하지 않았나.</b> 같은 경로에 "저장할까요" 스위치를
     * 두면 <b>인증 규칙을 경로가 아니라 바디로</b> 가르게 된다 — SecurityConfig가 못 읽는 값이라
     * 컨트롤러가 직접 판단해야 하고, 그 판단이 한 번 어긋나면 인증 없이 저장되는 길이 열린다.
     * 경로를 나누면 그 사고가 있을 자리가 없다.
     *
     * <p>문제 id를 <b>경로</b>에서 받는 것은 {@code GET /api/quiz/{id}}와 짝을 맞춘 것이다 —
     * 화면은 "이 문제를 가져와서 이 문제를 확인한다"를 같은 id로 잇는다.
     */
    @PostMapping("/{problemId}/check")
    public ApiResponse<QuizSubmitResponse> check(
            @PathVariable Long problemId,
            @Valid @RequestBody QuizCheckRequest request
    ) {
        return ApiResponse.ok(quizService.check(problemId, request.userAnswer()));
    }
}
