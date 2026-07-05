package project.study.study_project.quiz.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.response.ApiResponse;
import project.study.study_project.global.response.PageResponse;
import project.study.study_project.quiz.dto.WrongAnswerItem;
import project.study.study_project.quiz.service.WrongAnswerService;

/**
 * 오답노트 API — 명세는 docs/03. 인증 필수(SecurityConfig의 /api/me/** authenticated).
 *
 * <p>경로가 {@code /api/me/...}인 이유: "누구의" 오답노트인지 URL 파라미터로 받지 않는다.
 * {@code /api/users/3/wrong-answers} 같은 설계면 남의 id를 넣어볼 수 있지만,
 * {@code /api/me/...}는 항상 <b>토큰의 주인</b> 것만 반환하므로 권한 검사 실수 여지가 없다.
 */
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class WrongAnswerController {

    private final WrongAnswerService wrongAnswerService;

    /**
     * 내 오답노트(문제당 최신 오답 1건, 최신순). 예:
     * {@code GET /api/me/wrong-answers?domain=NETWORK&page=0&size=20}
     *
     * <p>정렬은 스펙에 없어 쿼리(JPQL)의 최신순으로 고정 — {@code sort} 파라미터는 받지 않는다
     * (@Query에 order by가 이미 있어 Pageable의 sort와 섞이면 혼란만 생긴다).
     */
    @GetMapping("/wrong-answers")
    public ApiResponse<PageResponse<WrongAnswerItem>> wrongAnswers(
            @AuthenticationPrincipal Long userId,
            @RequestParam(required = false) Domain domain,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ApiResponse.ok(wrongAnswerService.getWrongAnswers(userId, domain, pageable));
    }
}
