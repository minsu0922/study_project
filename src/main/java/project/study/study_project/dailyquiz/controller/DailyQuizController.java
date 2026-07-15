package project.study.study_project.dailyquiz.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.dailyquiz.dto.DailyQuizResponse;
import project.study.study_project.dailyquiz.service.DailyQuizService;
import project.study.study_project.global.response.ApiResponse;

/**
 * 오늘의 퀴즈 API — 명세는 docs/12. 인증 필수(SecurityConfig의 /api/me/** authenticated).
 *
 * <p><b>조회 API 하나뿐이다</b> — 제출은 기존 {@code POST /api/quiz/submit}을 그대로 쓴다.
 * 채점·사다리·세트 진행률이 전부 그 한 경로에서 갱신된다(복습 API와 같은 설계, docs/12).
 */
@RestController
@RequestMapping("/api/me/daily-quiz")
@RequiredArgsConstructor
public class DailyQuizController {

    private final DailyQuizService dailyQuizService;

    /**
     * 오늘 세트 조회 — 없으면 만들어서 반환(멱등, 지연 생성의 입구. ADR-0005).
     *
     * <p><b>catch가 여기 있는 이유(동시 첫 조회 경합)</b>: 탭 2개가 같은 순간 세트를 만들면
     * 진 쪽이 UNIQUE(user_id, quiz_date) 위반으로 실패한다. 그 트랜잭션은 이미 롤백 확정이라
     * <b>트랜잭션 안(서비스)에서는 재시도할 수 없고</b>, 경계 밖인 여기서 한 번 더 부르면
     * 새 트랜잭션이 승자가 만든 세트를 읽어 온다. 사용자는 경합이 있었는지도 모른다.
     * 재시도가 1번인 이유: 두 번째 호출은 생성이 아니라 조회라 같은 예외가 다시 날 수 없다.
     */
    @GetMapping
    public ApiResponse<DailyQuizResponse> today(@AuthenticationPrincipal Long userId) {
        try {
            return ApiResponse.ok(dailyQuizService.getToday(userId));
        } catch (DataIntegrityViolationException e) {
            return ApiResponse.ok(dailyQuizService.getToday(userId)); // 경합 패자 — 승자의 세트를 읽는다
        }
    }
}
