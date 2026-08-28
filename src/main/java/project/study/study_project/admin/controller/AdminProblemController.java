package project.study.study_project.admin.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.admin.dto.AdminProblemDetail;
import project.study.study_project.admin.dto.AdminProblemRequest;
import project.study.study_project.admin.service.AdminProblemService;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.global.response.ApiResponse;
import project.study.study_project.global.response.PageResponse;
import project.study.study_project.llm.client.ClaudeRationaleGenerator;
import project.study.study_project.llm.dto.RationaleBackfillResponse;
import project.study.study_project.llm.dto.TitleBackfillResponse;
import project.study.study_project.llm.service.ChoiceRationaleBackfillService;
import project.study.study_project.llm.service.ProblemTitleBackfillService;

/**
 * 관리자 문제 관리 API — /api/admin/** 전체가 SecurityConfig에서 hasRole(ADMIN)로 잠긴다.
 * 컨트롤러에는 권한 코드가 한 줄도 없다는 점이 포인트: 경로 규칙 한 곳(SecurityConfig)에서
 * 일괄 통제하므로, 여기에 API를 추가해도 권한을 빼먹을 수 없다.
 */
@RestController
@RequestMapping("/api/admin/problems")
@RequiredArgsConstructor
public class AdminProblemController {

    private final AdminProblemService adminProblemService;
    /**
     * 제목 백필은 Claude를 부르므로 {@code llm} 패키지에 산다. 문제 관리 화면에서 누르는
     * 버튼이라 입구만 여기에 둔다 — 관리자에게는 "문제를 손보는 일" 중 하나다.
     */
    private final ProblemTitleBackfillService titleBackfillService;
    /** 오답 설명 채우기도 같은 이유로 여기에 입구를 둔다 — 관리자에게는 "문제를 손보는 일"이다. */
    private final ChoiceRationaleBackfillService rationaleBackfillService;

    /** 목록(관리 화면용, 정답 포함). 예: {@code GET /api/admin/problems?domain=NETWORK&page=0} */
    @GetMapping
    public ApiResponse<PageResponse<AdminProblemDetail>> list(
            @RequestParam(required = false) Domain domain,
            @RequestParam(required = false) ProblemType type,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ApiResponse.ok(adminProblemService.getProblems(domain, type, pageable));
    }

    /** 단건(수정 폼 채우기용). */
    @GetMapping("/{id}")
    public ApiResponse<AdminProblemDetail> get(@PathVariable Long id) {
        return ApiResponse.ok(adminProblemService.getProblem(id));
    }

    /** 등록. 성공 시 201 + 생성된 문제 상세(id 포함). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AdminProblemDetail> create(@Valid @RequestBody AdminProblemRequest request) {
        return ApiResponse.ok(adminProblemService.create(request));
    }

    /** 수정(전체 교체 방식 — 서비스 주석 참고). */
    @PutMapping("/{id}")
    public ApiResponse<AdminProblemDetail> update(@PathVariable Long id,
                                                  @Valid @RequestBody AdminProblemRequest request) {
        return ApiResponse.ok(adminProblemService.update(id, request));
    }

    /** 삭제. 제출 이력이 있으면 409(QUIZ_003). */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        adminProblemService.delete(id);
        return ApiResponse.ok();
    }

    /** 제목 없는 문제 수 — 화면이 백필 버튼을 보여 줄지 정하는 데 쓴다. */
    @GetMapping("/untitled-count")
    public ApiResponse<Long> untitledCount() {
        return ApiResponse.ok(titleBackfillService.untitledCount());
    }

    /**
     * 제목 백필 — 제목이 없는 문제에 Claude가 지은 목록 제목을 채운다(V13).
     *
     * <p><b>{@code GET}이 아니라 {@code POST}인 이유</b>는 당연해 보이지만 적어 둘 값이 있다:
     * 이 요청은 <b>돈이 들고 DB를 바꾼다</b>. 브라우저가 미리 가져오거나 새로고침으로 다시
     * 보내도 되는 종류가 아니다.
     *
     * <p>한 번에 상한(40건)까지만 처리하고 남은 수를 응답에 담는다 — 서버가 알아서 반복하지
     * 않는 이유는 {@link ProblemTitleBackfillService#backfill()} 주석 참고.
     */
    @PostMapping("/backfill-titles")
    public ApiResponse<TitleBackfillResponse> backfillTitles() {
        return ApiResponse.ok(titleBackfillService.backfill());
    }

    /** 오답 설명이 빠진 문제 수 — 화면이 채우기 카드를 보여 줄지 정하는 데 쓴다. */
    @GetMapping("/missing-rationale-count")
    public ApiResponse<Long> missingRationaleCount() {
        return ApiResponse.ok(rationaleBackfillService.missingRationaleCount());
    }

    /**
     * 오답 설명 채우기 — 설명이 비어 있는 오답 보기에 Claude가 쓴 "왜 틀렸는지"를 채운다(V15).
     *
     * <p>제목 채우기와 같은 이유로 {@code POST}다 — <b>돈이 들고 DB를 바꾼다</b>.
     * 브라우저가 미리 가져오거나 새로고침으로 다시 보내도 되는 종류가 아니다.
     *
     * <p>한 번에 상한(10문제)까지만 처리하고 남은 수를 응답에 담는다. 상한이 제목 쪽(40건)보다
     * 훨씬 작은 이유는 {@link ClaudeRationaleGenerator#BATCH_SIZE} 주석 참고.
     *
     * <p><b>해설은 바뀌지 않는다.</b> 응답의 {@code explanationsToCheck}는 사람이 다듬을 문제
     * 목록일 뿐, 서버가 손댄 것이 아니다({@link ChoiceRationaleBackfillService} 주석).
     */
    @PostMapping("/backfill-rationales")
    public ApiResponse<RationaleBackfillResponse> backfillRationales() {
        return ApiResponse.ok(rationaleBackfillService.backfill());
    }
}
