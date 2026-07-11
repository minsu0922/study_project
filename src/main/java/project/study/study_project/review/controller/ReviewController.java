package project.study.study_project.review.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.global.response.ApiResponse;
import project.study.study_project.global.response.PageResponse;
import project.study.study_project.review.domain.ReviewStatus;
import project.study.study_project.review.dto.ReviewListItem;
import project.study.study_project.review.dto.ReviewTodayItem;
import project.study.study_project.review.service.ReviewService;

/**
 * 복습 추천 API — 명세는 docs/10. 인증 필수(SecurityConfig의 /api/me/** authenticated).
 *
 * <p>경로가 {@code /api/me/...}인 이유는 오답노트와 동일 — "누구의" 복습인지 URL로 받지 않고
 * 항상 토큰의 주인 것만 반환하므로 권한 검사 실수 여지가 없다(WrongAnswerController 주석 참고).
 *
 * <p><b>조회 API만 있다</b> — 복습 "제출"은 기존 {@code POST /api/quiz/submit}을 그대로 쓴다.
 * 채점·상태 전이 경로를 하나로 유지하기 위한 의도적 설계(docs/10 "쓰기 경로는 제출 API 하나뿐").
 */
@RestController
@RequestMapping("/api/me/reviews")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    /**
     * 오늘의 복습 — 복습할 때가 된 문제만, 오래 밀린 순. 예:
     * {@code GET /api/me/reviews/today?page=0&size=20}
     *
     * <p>정렬은 스펙 고정(nextReviewAt asc)이라 {@code sort} 파라미터는 받지 않는다.
     * size 상한(50) 보정은 서비스가 한다(정책은 서비스 책임).
     */
    @GetMapping("/today")
    public ApiResponse<PageResponse<ReviewTodayItem>> today(
            @AuthenticationPrincipal Long userId,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ApiResponse.ok(reviewService.getTodayReviews(userId, pageable));
    }

    /**
     * 내 복습 현황 전체(졸업 포함) — 진척 확인용. 예:
     * {@code GET /api/me/reviews?status=LEARNING&page=0&size=20}
     *
     * @param status 상태 필터(선택) — 잘못된 값은 enum 바인딩 실패로 400(전역 처리기 담당)
     */
    @GetMapping
    public ApiResponse<PageResponse<ReviewListItem>> myReviews(
            @AuthenticationPrincipal Long userId,
            @RequestParam(required = false) ReviewStatus status,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ApiResponse.ok(reviewService.getMyReviews(userId, status, pageable));
    }
}
