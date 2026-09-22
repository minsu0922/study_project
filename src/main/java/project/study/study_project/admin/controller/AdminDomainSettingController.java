package project.study.study_project.admin.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.admin.dto.AdminDomainSettingMoveRequest;
import project.study.study_project.admin.dto.AdminDomainSettingRequest;
import project.study.study_project.admin.dto.AdminDomainSettingResponse;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.global.response.ApiResponse;
import project.study.study_project.llm.service.DomainSettingService;
import project.study.study_project.llm.support.DomainHints;

import java.util.List;

/**
 * 분야 설정 관리 API — Task 9.
 *
 * <p>{@code /api/admin/**} 아래라 SecurityConfig의 {@code hasRole(ADMIN)}이 일괄 적용된다
 * ({@code AdminTopicQueueController}와 같은 규칙, 컨트롤러에 권한 코드를 두지 않는다).
 *
 * <p>행 자체는 {@link project.study.study_project.global.common.Domain} enum 상수 수만큼
 * 고정이다(기동 시 동기화가 맞춰 둔다). 그래서 이 API에는 "추가"·"삭제"가 없다 — 있는 행을
 * 고치고({@link #edit}) 순서를 옮기는({@link #move}) 것, 그리고 그 결과를 저장 전에
 * 미리 보는 것({@link #preview})뿐이다.
 */
@RestController
@RequestMapping("/api/admin/domain-settings")
@RequiredArgsConstructor
public class AdminDomainSettingController {

    private final DomainSettingService domainSettingService;

    /** 미리보기 기본 일수 — 저장 버튼 옆에 "다음 7일"을 그리는 화면(Task 10)에 맞춘다. */
    private static final int DEFAULT_PREVIEW_DAYS = 7;

    /**
     * 미리보기 상한 — 60일이면 4일 주기가 열다섯 번 돈다. 분야 열한 개가 한 바퀴 이상 도는 것을
     * 보기에 넉넉하고, 그 이상은 화면이 쓰지 않는다(화면은 7일만 부른다).
     */
    private static final int MAX_PREVIEW_DAYS = 60;

    /**
     * 목록 — {@code sortOrder} 순 전체(꺼진 분야도 포함, 화면에서 다시 켤 수 있어야 하므로).
     *
     * <p>{@code promptPreview}는 {@link DomainHints#hintFor}가 만드는 <b>프롬프트에 실릴 꼴
     * 그대로</b>다 — 앞 공백과 괄호까지 포함한다. 원문({@code hint})과 따로 내려주는 이유는
     * 화면이 "무엇을 저장할지"(원문)와 "실제로 모델에게 나갈 것"(프리뷰)을 둘 다 보여 줘야
     * 하기 때문이다.
     */
    @GetMapping
    public ApiResponse<List<AdminDomainSettingResponse>> list() {
        DomainHints hints = domainSettingService.hints();
        List<AdminDomainSettingResponse> responses = domainSettingService.findAll().stream()
                .map(setting -> AdminDomainSettingResponse.from(setting, hints))
                .toList();
        return ApiResponse.ok(responses);
    }

    /**
     * 분야 하나의 켜짐 여부·이름·힌트를 고친다. 순서는 {@link #move}의 몫이라 여기서는
     * 건드리지 않는다.
     *
     * <p>힌트가 500자를 넘으면 400 — 유료 LLM 프롬프트에 그대로 실리는 값이라, 문서를 통째로
     * 붙여 넣는 실수가 매 배치 요금으로 돌아오는 것을 막는다({@code AdminDomainSettingRequest}
     * Javadoc). 없는 분야면 404(DOMAIN_001) — enum 동기화가 기동마다 전체 분야에 행을 맞춰
     * 두므로 정상 경로에서는 나지 않는다.
     */
    @PutMapping("/{domain}")
    public ApiResponse<Void> edit(@PathVariable Domain domain,
                                  @Valid @RequestBody AdminDomainSettingRequest request) {
        domainSettingService.edit(domain, request);
        return ApiResponse.ok();
    }

    /**
     * 순서 이동 — 본문 {@code {"direction":"UP"|"DOWN"}}으로 이웃과 자리를 맞바꾼다.
     *
     * <p>PATCH가 아니라 POST인 이유는 {@code AdminTopicQueueController.move}와 같다 — "필드
     * 하나를 이 값으로 고쳐라"가 아니라 <b>두 행의 자리를 맞바꿔라</b>는 동작이라, 어떤 값이
     * 될지는 서버가 정한다.
     *
     * <p>맨 위에서 더 올리거나 맨 아래에서 더 내려도 200이다 — 오류로 만들면 버튼을 눌러
     * 보기가 무서워진다({@code DomainSettingService.move} Javadoc).
     */
    @PostMapping("/{domain}/move")
    public ApiResponse<Void> move(@PathVariable Domain domain,
                                  @Valid @RequestBody AdminDomainSettingMoveRequest request) {
        domainSettingService.move(domain, request.direction());
        return ApiResponse.ok();
    }

    /**
     * 앞으로 {@code days}일의 생성 계획 미리보기 — <b>저장하지 않는다.</b>
     *
     * <p>{@code domains}는 화면이 <b>지금 들고 있는(아직 저장 안 한) 순서</b>를 그대로 싣는다
     * — 체크를 끄거나 ▲▼로 순서를 바꾼 직후, 저장 버튼을 누르기 전에 그 결과를 눈으로
     * 확인시키기 위한 값이다({@code DomainSettingService.preview} Javadoc, task-9-brief 룰링 3).
     * 생략하면(화면을 처음 열었을 때 등) 지금 저장된 순서({@code batchDomains()})로 대신한다.
     *
     * <p>예: {@code GET /api/admin/domain-settings/preview?days=7&domains=NETWORK,OS,DATABASE}
     *
     * <p><b>{@code days}는 1~{@value #MAX_PREVIEW_DAYS}일, 벗어나면 400</b>(최종 리뷰 Minor 4).
     * 범위가 없으면 {@code days=-1}은 {@code ArrayList(-1)}에서 500이 나고, 아주 큰 값은
     * 칸을 그 수만큼 만들어 메모리를 다 쓴다.
     *
     * <p>{@code @Min}·{@code @Max}를 달지 않고 손으로 검사하는 이유: 요청 파라미터의 제약 위반은
     * 스프링 6.1+에서 {@code HandlerMethodValidationException}으로 나오는데,
     * {@code GlobalExceptionHandler}가 그 예외를 따로 받지 않아 {@code Exception} 처리기로 떨어져
     * <b>500</b>이 된다. 전역 처리기를 이 한 곳 때문에 넓히기보다, 다른 400과 같은 모양
     * ({@code VALIDATION_ERROR})을 여기서 직접 만든다.
     */
    @GetMapping("/preview")
    public ApiResponse<List<DomainSettingService.PreviewCell>> preview(
            @RequestParam(required = false) List<Domain> domains,
            @RequestParam(defaultValue = "" + DEFAULT_PREVIEW_DAYS) int days) {
        if (days < 1 || days > MAX_PREVIEW_DAYS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "days는 1~" + MAX_PREVIEW_DAYS + " 사이여야 합니다: " + days);
        }
        List<Domain> target = (domains == null || domains.isEmpty())
                ? domainSettingService.batchDomains() : domains;
        return ApiResponse.ok(domainSettingService.preview(target, days));
    }
}
