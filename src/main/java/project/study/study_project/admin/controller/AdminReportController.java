package project.study.study_project.admin.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.global.response.ApiResponse;
import project.study.study_project.global.response.PageResponse;
import project.study.study_project.report.domain.ReportStatus;
import project.study.study_project.report.dto.ProblemReportItem;
import project.study.study_project.report.service.ProblemReportService;

import java.util.Map;

/**
 * 제보함 API — {@code /api/admin/**}이라 SecurityConfig의 {@code hasRole(ADMIN)}이 일괄 적용된다
 * (다른 admin 컨트롤러와 같은 원칙: 컨트롤러에 권한 코드를 두지 않는다).
 *
 * <p>인정·기각이 PUT이 아니라 <b>POST + 동사 경로</b>인 것은 초안 승인·거절과 같은 판단이다 —
 * 리소스 수정이 아니라 사람이 내리는 판정이라는 사건이고, 사건 이름을 경로에 드러낸다.
 */
@RestController
@RequestMapping("/api/admin/reports")
@RequiredArgsConstructor
public class AdminReportController {

    private final ProblemReportService problemReportService;

    /**
     * 제보 목록 — 상태를 안 주면 전체. 예: {@code GET /api/admin/reports?status=PENDING}
     *
     * <p>정렬 파라미터가 없는 것은 의도다 — 상태가 정렬을 함께 정한다(서비스 주석).
     */
    @GetMapping
    public ApiResponse<PageResponse<ProblemReportItem>> list(
            @RequestParam(required = false) ReportStatus status,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ApiResponse.ok(problemReportService.getReports(status, pageable));
    }

    /** 대기 건수 — 관리 콘솔 메뉴의 배지. 검수함의 {@code /pending-count}와 같은 모양으로 맞췄다. */
    @GetMapping("/pending-count")
    public ApiResponse<Map<String, Long>> pendingCount() {
        return ApiResponse.ok(Map.of("count", problemReportService.pendingCount()));
    }

    /** 인정 — 이 건의 사유가 다음 생성 프롬프트로 되먹여진다. 메모는 선택: {@code {"note": "..."}} */
    @PostMapping("/{id}/accept")
    public ApiResponse<ProblemReportItem> accept(@PathVariable Long id,
                                                 @RequestBody(required = false) Map<String, String> body) {
        return ApiResponse.ok(problemReportService.accept(id, body != null ? body.get("note") : null));
    }

    /** 기각 — 되먹임에 쓰이지 않는다. 메모는 선택이지만, "왜 아니라고 봤나"는 적어 둘 값어치가 있다. */
    @PostMapping("/{id}/dismiss")
    public ApiResponse<ProblemReportItem> dismiss(@PathVariable Long id,
                                                  @RequestBody(required = false) Map<String, String> body) {
        return ApiResponse.ok(problemReportService.dismiss(id, body != null ? body.get("note") : null));
    }
}
