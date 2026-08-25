package project.study.study_project.admin.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import project.study.study_project.admin.dto.AdminProblemDetail;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.global.response.ApiResponse;
import project.study.study_project.global.response.PageResponse;
import project.study.study_project.llm.client.SourceDocument;
import project.study.study_project.llm.domain.DraftStatus;
import project.study.study_project.llm.dto.LlmBulkApproveRequest;
import project.study.study_project.llm.dto.LlmBulkApproveResponse;
import project.study.study_project.llm.dto.LlmDocumentGenerateRequest;
import project.study.study_project.llm.dto.LlmDraftResponse;
import project.study.study_project.llm.dto.LlmGenerateRequest;
import project.study.study_project.llm.service.LlmDraftBulkApprover;
import project.study.study_project.llm.service.LlmProblemService;
import project.study.study_project.llm.support.UploadedDocumentReader;

import java.io.IOException;
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
    /** 일괄 승인만 따로 맡는 협력자 — 건별 트랜잭션을 얻으려면 서비스 <b>밖</b>에서 불러야 한다. */
    private final LlmDraftBulkApprover llmDraftBulkApprover;

    /**
     * 생성 트리거 — Claude 호출은 수십 초 걸릴 수 있어 프론트가 로딩 표시를 담당한다.
     * 201이 아닌 200: 만들어진 것은 "정식 리소스"가 아니라 검수 대기 초안이라 의미가 다르다.
     */
    @PostMapping("/generate")
    public ApiResponse<List<LlmDraftResponse>> generate(@Valid @RequestBody LlmGenerateRequest request) {
        return ApiResponse.ok(llmProblemService.generate(request));
    }

    /**
     * <b>문서를 근거로</b> 생성 — 2026-08-18 신설. 입구는 셋이다: 파일 업로드, 본문 붙여넣기,
     * <b>이미 등록된 문서 고르기</b>(2026-08-25 추가).
     *
     * <p>{@code multipart/form-data}인 이유: 파일과 폼 필드를 한 요청에 함께 보내야 한다.
     * 나머지 둘도 같은 엔드포인트를 쓰는 것은 <b>뒤가 완전히 같기 때문</b>이다 —
     * 입구만 셋이고 그 뒤로는 같은 {@code SourceDocument}가 흐른다. 경로를 셋으로 가르면
     * 검증과 상한이 세 곳에 생기고, 언젠가 한쪽에만 규칙이 붙는다.
     *
     * <p><b>셋 중 정확히 하나여야 한다.</b> 하나도 없으면 무엇으로 만들지 알 수 없고,
     * 둘 이상이면 어느 쪽을 썼는지 사람이 알 수 없다 — 조용히 하나를 고르면
     * "올린 파일이 무시된 줄 모르는" 사고가 난다. 그래서 골라 주지 않고 400으로 되돌린다.
     * 세는 방식을 boolean 비교({@code hasFile == hasText})에서 <b>개수 세기</b>로 바꾼 이유가
     * 이것이다 — 입구가 둘일 때만 통하던 요령이라 셋째가 붙는 순간 조용히 틀린다.
     *
     * <p><b>셋째 입구가 나머지 둘과 다른 점</b>은 생성된 초안에 근거 문서 slug가 기록된다는 것뿐이다.
     * 등록된 문서라 학습자 화면의 "개념 문서 읽기"가 갈 곳이 있다. 그 판단은 여기가 아니라
     * {@link LlmProblemService#generateFromDocument}가 문서의 {@code kind}를 보고 내린다.
     *
     * <p>파일 크기 상한은 두 겹이다: 톰캣·스프링의 multipart 상한(application.yml)이 먼저 막고,
     * 통과한 뒤 {@code UploadedDocumentReader}가 <b>글자 수</b>로 다시 막는다. 앞은 전송량,
     * 뒤는 요금이 기준이라 재는 대상이 다르다. 등록 문서에는 이 상한을 걸지 않는다 —
     * 우리가 만들어 넣은 문서라 분량을 이미 프롬프트가 통제하고 있다.
     */
    @PostMapping("/generate-from-document")
    public ApiResponse<List<LlmDraftResponse>> generateFromDocument(
            @Valid @ModelAttribute LlmDocumentGenerateRequest request,
            @RequestParam(value = "file", required = false) MultipartFile file
    ) {
        boolean hasFile = file != null && !file.isEmpty();
        boolean hasText = request.text() != null && !request.text().isBlank();
        boolean hasSlug = request.slug() != null && !request.slug().isBlank();

        int given = (hasFile ? 1 : 0) + (hasText ? 1 : 0) + (hasSlug ? 1 : 0);
        if (given != 1) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, given == 0
                    ? "등록된 문서를 고르거나, 문서를 올리거나, 본문을 붙여넣어 주세요."
                    : "등록 문서·파일·붙여넣기 중 하나만 보내 주세요.");
        }

        SourceDocument document;
        if (hasSlug) {
            document = llmProblemService.findRegisteredDocument(request.slug());
        } else if (hasFile) {
            try {
                document = UploadedDocumentReader.fromFile(file.getBytes(), file.getOriginalFilename());
            } catch (IOException e) {
                // 업로드 스트림을 읽다 끊긴 경우 — 사용자가 고칠 수 있는 문제라 400으로 돌려준다.
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "업로드 파일을 읽지 못했습니다. 다시 시도해 주세요.");
            }
        } else {
            document = UploadedDocumentReader.fromText(request.text());
        }
        return ApiResponse.ok(llmProblemService.generateFromDocument(request, document));
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

    /**
     * <b>일괄 승인</b> — 검수함에서 체크한 여러 건을 한 요청으로 승인한다.
     *
     * <p>단건 승인과 달리 <b>201이 아니라 200</b>이다. 201 Created는 "만들어진 것 하나"를
     * 가리키는 상태 코드인데(보통 Location 헤더와 짝을 이룬다) 여기서는 만들어진 것이 여럿이고,
     * 심지어 하나도 없을 수도 있다(전부 실패). 성패가 건마다 다른 응답이라 상태 코드로 성패를
     * 말하지 않고, 무엇이 되고 안 됐는지를 본문에 적는다({@link LlmBulkApproveResponse}).
     *
     * <p>경로를 {@code /approve-batch}로 둔 것은 {@code /{id}/approve}와 충돌을 피하려는 것이
     * 아니라(경로 변수 자리가 달라 충돌하지 않는다) 클래스 첫머리에 적은 원칙 그대로다 —
     * 승인은 리소스 수정이 아니라 사건이고, 사건 이름을 경로에 드러낸다.
     */
    @PostMapping("/approve-batch")
    public ApiResponse<LlmBulkApproveResponse> approveBatch(@Valid @RequestBody LlmBulkApproveRequest request) {
        return ApiResponse.ok(llmDraftBulkApprover.approveAll(request.ids()));
    }

    /** 거절 — 사유(선택)를 바디로 받는다: {@code {"reason": "..."} } (없으면 빈 바디 허용). */
    @PostMapping("/{id}/reject")
    public ApiResponse<Void> reject(@PathVariable Long id,
                                    @RequestBody(required = false) Map<String, String> body) {
        llmProblemService.reject(id, body != null ? body.get("reason") : null);
        return ApiResponse.ok();
    }

    /**
     * 복구 — 거절한 초안을 검수 대기로 되돌린다(실수 거절 취소).
     *
     * <p>승인된 초안에는 쓸 수 없다. 이미 만들어진 정식 문제가 있어 되돌리면 중복 등록으로
     * 이어지기 때문 — 그때는 문제 관리에서 그 문제를 지우는 것이 옳은 경로다(409 LLM_002).
     */
    @PostMapping("/{id}/restore")
    public ApiResponse<Void> restore(@PathVariable Long id) {
        llmProblemService.restore(id);
        return ApiResponse.ok();
    }
}
