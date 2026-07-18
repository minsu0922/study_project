package project.study.study_project.admin.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.admin.dto.AdminProblemDetail;
import project.study.study_project.global.response.ApiResponse;
import project.study.study_project.global.response.PageResponse;
import project.study.study_project.llm.domain.DraftStatus;
import project.study.study_project.llm.dto.LlmDraftResponse;
import project.study.study_project.llm.dto.LlmGenerateRequest;
import project.study.study_project.llm.service.LlmProblemService;

import java.util.List;
import java.util.Map;

/**
 * LLM 문제 생성·검수 API — /api/admin/** 이라 SecurityConfig의 hasRole(ADMIN)이 일괄 적용된다
 * (AdminProblemController와 같은 원칙: 컨트롤러에 권한 코드를 두지 않는다).
 *
 * <p>승인/거절이 PUT/DELETE가 아니라 <b>POST + 동사 경로</b>인 이유: 승인은 "초안 리소스 수정"이
 * 아니라 "정식 문제 생성 + 상태 전이"라는 사건(행위)이다. 리소스 CRUD로 어색하게 욱여넣는 것보다
 * 행위를 경로에 드러내는 쪽이 명확하다(오늘의 퀴즈 submit과 같은 판단).
 */
@RestController
@RequestMapping("/api/admin/llm-problems")
@RequiredArgsConstructor
public class AdminLlmProblemController {

    private final LlmProblemService llmProblemService;

    /**
     * 생성 트리거 — Claude 호출은 수십 초 걸릴 수 있어 프론트가 로딩 표시를 담당한다.
     * 201이 아닌 200: 만들어진 것은 "정식 리소스"가 아니라 검수 대기 초안이라 의미가 다르다.
     */
    @PostMapping("/generate")
    public ApiResponse<List<LlmDraftResponse>> generate(@Valid @RequestBody LlmGenerateRequest request) {
        return ApiResponse.ok(llmProblemService.generate(request));
    }

    /** 초안 목록 — 기본 PENDING, 오래된 순. 예: {@code GET /api/admin/llm-problems?status=REJECTED} */
    @GetMapping
    public ApiResponse<PageResponse<LlmDraftResponse>> list(
            @RequestParam(required = false) DraftStatus status,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ApiResponse.ok(llmProblemService.getDrafts(status, pageable));
    }

    /** 검수 대기 건수 — 관리자 화면 배지용. */
    @GetMapping("/pending-count")
    public ApiResponse<Map<String, Long>> pendingCount() {
        return ApiResponse.ok(Map.of("count", llmProblemService.pendingCount()));
    }

    /** 승인 — 정식 문제로 등록하고 생성된 문제 상세를 돌려준다(관리 화면에서 바로 확인·수정 가능). */
    @PostMapping("/{id}/approve")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AdminProblemDetail> approve(@PathVariable Long id) {
        return ApiResponse.ok(llmProblemService.approve(id));
    }

    /** 거절 — 사유(선택)를 바디로 받는다: {@code {"reason": "..."} } (없으면 빈 바디 허용). */
    @PostMapping("/{id}/reject")
    public ApiResponse<Void> reject(@PathVariable Long id,
                                    @RequestBody(required = false) Map<String, String> body) {
        llmProblemService.reject(id, body != null ? body.get("reason") : null);
        return ApiResponse.ok();
    }
}
