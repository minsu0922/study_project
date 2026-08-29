package project.study.study_project.quiz.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.response.ApiResponse;
import project.study.study_project.global.response.PageResponse;
import project.study.study_project.quiz.dto.ProblemListItem;
import project.study.study_project.quiz.dto.StudySummaryResponse;
import project.study.study_project.quiz.service.ProblemListService;

/**
 * 학습자 문제 목록 화면 API — docs/18, 2026-08-29 신설.
 *
 * <h2>왜 로그인을 요구하나</h2>
 *
 * <p>이 목록의 모든 줄이 <b>"나와의 관계"</b>를 달고 있다 — 맞혔나, 언제 풀었나, 복습할 때인가.
 * 그것을 뺀 목록은 그냥 문제 카탈로그이고, 그건 이미 {@code /api/quiz}가 하는 일이다.
 * {@code SecurityConfig}의 {@code anyRequest().authenticated()}가 기본으로 막으므로
 * 여기에 따로 규칙을 적지 않는다(권한을 경로 한 곳에서 통제하는 이 프로젝트의 규칙).
 *
 * <h2>목록과 요약을 두 엔드포인트로 가른 이유</h2>
 *
 * <p>목록은 필터·쪽을 바꿀 때마다 다시 부르지만 통계는 그때마다 바뀌지 않는다. 한 응답에
 * 담으면 필터를 만질 때마다 집계 쿼리 넷이 같이 돈다.
 */
@RestController
@RequiredArgsConstructor
public class ProblemListController {

    private final ProblemListService problemListService;

    /**
     * 목록 한 판 — 분야·난이도·상태·복습 대기로 좁힌다. 전부 선택이다.
     *
     * <p>예: {@code GET /api/problems?domain=NETWORK&state=UNSOLVED&page=0&size=20}
     *
     * <p><b>정렬은 고정</b>(분야 → 난이도 → id)이고 파라미터로 받지 않는다. 스펙의 첫 안은
     * "미풀이 우선"이었는데, 그러면 한 문제를 풀고 돌아올 때마다 그 줄이 뒤로 밀려 목록
     * 전체가 한 칸 당겨진다 — 무한 스크롤을 뺀 이유와 같은 문제다. 그리고 그 정렬이 하려던
     * 일은 {@code state=UNSOLVED} 필터가 이미 한다(자세히는 {@code findListForUser} 주석).
     *
     * @param state   {@code UNSOLVED}/{@code CORRECT}/{@code WRONG}. 없으면 전체
     * @param reviewDue {@code true}면 지금 복습 차례인 문제만
     */
    @GetMapping("/api/problems")
    public ApiResponse<PageResponse<ProblemListItem>> list(
            @AuthenticationPrincipal Long userId,
            @RequestParam(required = false) Domain domain,
            @RequestParam(required = false) Difficulty difficulty,
            @RequestParam(required = false) ProblemListItem.SolveState state,
            @RequestParam(required = false, defaultValue = "false") boolean reviewDue,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ApiResponse.ok(
                problemListService.getList(userId, domain, difficulty, state, reviewDue, pageable));
    }

    /** 통계 카드 + 분야별 진척. 화면을 열 때 한 번 부른다(필터를 바꿔도 다시 부르지 않는다). */
    @GetMapping("/api/me/study-summary")
    public ApiResponse<StudySummaryResponse> summary(@AuthenticationPrincipal Long userId) {
        return ApiResponse.ok(problemListService.getSummary(userId));
    }
}
