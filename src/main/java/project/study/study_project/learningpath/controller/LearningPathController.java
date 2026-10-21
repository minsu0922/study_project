package project.study.study_project.learningpath.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.global.response.ApiResponse;
import project.study.study_project.learningpath.dto.LearningPathDetail;
import project.study.study_project.learningpath.dto.LearningPathRecommendation;
import project.study.study_project.learningpath.dto.LearningPathSummary;
import project.study.study_project.learningpath.service.LearningPathService;
import project.study.study_project.quiz.dto.QuizResponse;

import java.util.List;

/**
 * 학습 로드맵 API — 명세는 docs/03, 설계는 docs/26.
 *
 * <p>보는 것은 공개(SecurityConfig의 {@code GET /api/learning-paths/**})다. 토큰이 있으면 내 진도가,
 * 없으면 0이 실린다. 시작·그만두기·홈 카드는 {@code /api/me/**}라 로그인이 필요하다.
 *
 * <p>코스 키에는 콜론이 든다({@code domain:NETWORK}). 경로 한 칸 안의 콜론은 URL에서 허용되는 글자다.
 */
@RestController
@RequiredArgsConstructor
public class LearningPathController {

    private final LearningPathService service;

    @GetMapping("/api/learning-paths")
    public ApiResponse<List<LearningPathSummary>> list(@AuthenticationPrincipal Long userId) {
        return ApiResponse.ok(service.list(userId));
    }

    @GetMapping("/api/learning-paths/{key}")
    public ApiResponse<LearningPathDetail> detail(@AuthenticationPrincipal Long userId, @PathVariable String key) {
        return ApiResponse.ok(service.detail(userId, key));
    }

    /** 단계 문제 세트 — 플레이어가 그대로 받는 모양이다. 로그인했으면 이 코스를 "시작한" 것으로 적는다 */
    @GetMapping("/api/learning-paths/{key}/steps/{seq}/problems")
    public ApiResponse<QuizResponse> stepProblems(@AuthenticationPrincipal Long userId,
                                                  @PathVariable String key, @PathVariable int seq) {
        return ApiResponse.ok(service.stepProblems(userId, key, seq));
    }

    /** 홈의 "이어서 하기". 시작한 코스가 없으면 data가 null이다 */
    @GetMapping("/api/me/learning-paths/current")
    public ApiResponse<LearningPathSummary> current(@AuthenticationPrincipal Long userId) {
        return ApiResponse.ok(service.current(userId).orElse(null));
    }

    /** 홈의 추천 코스. 이어서 하기 카드에 나온 코스는 빠진다. size는 1~6 */
    @GetMapping("/api/me/learning-paths/recommended")
    public ApiResponse<List<LearningPathRecommendation>> recommended(@AuthenticationPrincipal Long userId,
                                                                     @RequestParam(defaultValue = "3") int size) {
        return ApiResponse.ok(service.recommend(userId, Math.max(1, Math.min(size, 6))));
    }

    @PostMapping("/api/me/learning-paths/{key}")
    public ApiResponse<Void> start(@AuthenticationPrincipal Long userId, @PathVariable String key) {
        service.start(userId, key);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/api/me/learning-paths/{key}")
    public ApiResponse<Void> stop(@AuthenticationPrincipal Long userId, @PathVariable String key) {
        service.stop(userId, key);
        return ApiResponse.ok(null);
    }
}
