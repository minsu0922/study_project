package project.study.study_project.report.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.global.response.ApiResponse;
import project.study.study_project.report.dto.ProblemReportItem;
import project.study.study_project.report.dto.ProblemReportRequest;
import project.study.study_project.report.service.ProblemReportService;

/**
 * 학습자의 문제 오류 제보 API — 인증 필수.
 *
 * <p>경로가 {@code /api/me/...}인 것은 이 저장소의 규칙이다(오답노트·복습과 같은 자리).
 * 덕분에 <b>권한 검사가 여기에 한 줄도 없다</b> — SecurityConfig의
 * {@code /api/me/** authenticated} 하나가 통째로 맡는다. 제보자 id도 경로나 본문이 아니라
 * 토큰에서 꺼내므로("누구의"를 클라이언트가 정하지 못한다) 남의 이름으로 제보할 길이 없다.
 *
 * <p><b>목록 조회가 없다.</b> "내가 낸 제보"를 보여 주는 화면을 만들지 않았기 때문이다 —
 * 1인 서비스에서 그 목록이 답하는 질문("내 제보 어떻게 됐지")에는 알림 통로가 있어야 의미가
 * 있는데 그것부터 없다. 화면이 생기는 날 여기에 GET을 더한다(YAGNI).
 */
@RestController
@RequestMapping("/api/me/problem-reports")
@RequiredArgsConstructor
public class ProblemReportController {

    private final ProblemReportService problemReportService;

    /**
     * 제보 접수. 예: {@code {"problemId": 12, "reason": "WRONG_ANSWER", "detail": "3번도 맞는 것 같습니다"}}
     *
     * <p>201인 이유: 초안 승인(200)과 달리 여기서는 요청한 사람이 가리키는 <b>새 자원 하나</b>가
     * 실제로 만들어진다. 응답으로 만들어진 제보를 돌려주는 것은 화면이 "접수됨"을 그 자리에서
     * 확정적으로 그릴 수 있게 하려는 것이다(다시 조회하지 않아도 된다).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProblemReportItem> report(@AuthenticationPrincipal Long userId,
                                                 @Valid @RequestBody ProblemReportRequest request) {
        return ApiResponse.ok(problemReportService.report(userId, request));
    }
}
