package project.study.study_project.admin.controller;

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
import project.study.study_project.document.dto.DocumentDetailResponse;
import project.study.study_project.global.response.ApiResponse;
import project.study.study_project.global.response.PageResponse;
import project.study.study_project.llm.domain.DraftStatus;
import project.study.study_project.llm.dto.LlmDocumentDraftResponse;
import project.study.study_project.llm.service.LlmDocumentService;

import java.util.Map;

/**
 * 개념 문서 초안 검수 API — docs/15.
 *
 * <p>{@code /api/admin/**} 아래라 SecurityConfig의 {@code hasRole(ADMIN)}이 일괄 적용된다
 * (컨트롤러에 권한 코드를 두지 않는 것이 이 프로젝트의 규칙).
 *
 * <p><b>생성 트리거({@code POST /generate})가 없는 것이 문제 API와의 차이다.</b> 문서 생성은
 * GitHub Actions에서만 돌린다 — 한 편에 1~2분, $0.2가 드는 작업이라 관리자 화면에 버튼을 두면
 * 실수로 누르기 쉽고, 화면은 그동안 응답을 기다리며 멈춰 있어야 한다. 필요하면 워크플로를
 * 수동 실행하면 된다(그쪽은 주제·날짜까지 지정할 수 있어 오히려 더 낫다).
 */
@RestController
@RequestMapping("/api/admin/llm-documents")
@RequiredArgsConstructor
public class AdminLlmDocumentController {

    private final LlmDocumentService llmDocumentService;

    /** 초안 목록 — 기본 PENDING, 오래된 순. 각 건에 자동 검증 결과가 실려 온다. */
    @GetMapping
    public ApiResponse<PageResponse<LlmDocumentDraftResponse>> list(
            @RequestParam(required = false) DraftStatus status,
            // 문제 목록(20)보다 작게 잡은 이유: 응답에 3,000자짜리 본문이 통째로 들어간다.
            // 20건이면 한 번에 60KB를 넘기는데, 검수는 어차피 한 편씩 읽는 작업이라 낭비다.
            @PageableDefault(size = 5) Pageable pageable
    ) {
        return ApiResponse.ok(llmDocumentService.getDrafts(status, pageable));
    }

    /** 검수 대기 건수 — 관리자 화면 배지용. */
    @GetMapping("/pending-count")
    public ApiResponse<Map<String, Long>> pendingCount() {
        return ApiResponse.ok(Map.of("count", llmDocumentService.pendingCount()));
    }

    /**
     * 승인 — 정식 문서로 등록하고 상세를 돌려준다(관리 화면에서 바로 확인·수정 가능).
     *
     * <p>자동 검증의 차단 항목이 남아 있으면 409(LLM_005), slug가 이미 쓰이고 있으면
     * 409(DOC_002)로 막힌다. 둘 다 "요청은 멀쩡한데 지금은 안 된다"라 상태 코드가 같다.
     */
    @PostMapping("/{id}/approve")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DocumentDetailResponse> approve(@PathVariable Long id) {
        return ApiResponse.ok(llmDocumentService.approve(id));
    }

    /** 거절 — 사유(선택)를 바디로 받는다: {@code {"reason": "..."} } (없으면 빈 바디 허용). */
    @PostMapping("/{id}/reject")
    public ApiResponse<Void> reject(@PathVariable Long id,
                                    @RequestBody(required = false) Map<String, String> body) {
        llmDocumentService.reject(id, body != null ? body.get("reason") : null);
        return ApiResponse.ok();
    }

    /**
     * 복구 — 거절한 초안을 검수 대기로 되돌린다.
     *
     * <p>문서 쪽에는 부수 효과가 하나 있다: 배치의 "이 문서로는 문제를 만들지 마라" 목록에서도
     * 빠지므로 <b>다시 근거 문서로 쓰일 수 있게 된다</b>(docs/15).
     */
    @PostMapping("/{id}/restore")
    public ApiResponse<Void> restore(@PathVariable Long id) {
        llmDocumentService.restore(id);
        return ApiResponse.ok();
    }
}
