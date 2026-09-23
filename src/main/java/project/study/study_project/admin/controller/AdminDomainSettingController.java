package project.study.study_project.admin.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
import project.study.study_project.admin.dto.AdminDomainCreateRequest;
import project.study.study_project.admin.dto.AdminDomainSettingMoveRequest;
import project.study.study_project.admin.dto.AdminDomainSettingRequest;
import project.study.study_project.admin.dto.AdminDomainSettingResponse;
import project.study.study_project.global.common.DomainCode;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.global.response.ApiResponse;
import project.study.study_project.llm.domain.DomainSetting;
import project.study.study_project.llm.service.DomainSettingService;
import project.study.study_project.llm.support.DomainHints;

import java.util.List;

/**
 * 분야 설정 관리 API — Task 9(수정·순서 이동·미리보기) + Task 6(등록부 추가·삭제).
 *
 * <p>{@code /api/admin/**} 아래라 SecurityConfig의 {@code hasRole(ADMIN)}이 일괄 적용된다
 * ({@code AdminTopicQueueController}와 같은 규칙, 컨트롤러에 권한 코드를 두지 않는다).
 *
 * <p><b>행 수가 기본 분야 수로 고정이던 시절(Task 9)에는 이 API에 "추가"·"삭제"가 없었다.</b>
 * 6번 작업에서 외래키(V20)가 등록부를 실제 등록부로 만들면서 그 제약이 풀렸다 —
 * {@link #create}·{@link #delete}가 새로 생긴 이유다. 있는 행을 고치고({@link #edit}) 순서를
 * 옮기는({@link #move}) 것, 저장 전 미리 보는 것({@link #preview})은 그대로다.
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
     * 새 분야 등록 — 항상 <b>꺼진 채로</b>, 맨 끝 순서로 생긴다({@code DomainSettingService#create}
     * Javadoc). 코드 형식이 틀리면 400(COMMON_001, 몸통 역직렬화 단계에서 걸린다), 이미 쓰는
     * 코드면 400(DOMAIN_004), 화면 이름이 비었거나 40자를 넘으면 400, 힌트가 500자를 넘어도 400.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AdminDomainSettingResponse> create(@Valid @RequestBody AdminDomainCreateRequest request) {
        DomainSetting created = domainSettingService.create(request);
        return ApiResponse.ok(AdminDomainSettingResponse.from(created, domainSettingService.hints()));
    }

    /**
     * 분야 삭제 — 문제·문서·생성 문제 초안·생성 문서 초안(거절 포함)·주제 대기열 중 하나라도
     * 이 분야를 쓰면 400(DOMAIN_005, 메시지에 어느 표에 몇 건인지 실린다), 마지막으로 켜진
     * 분야면 400(DOMAIN_002), 없는 분야면 404(DOMAIN_001). 기본 11개도 특별 취급하지 않는다
     * ({@code DomainSettingService#delete} Javadoc).
     */
    @DeleteMapping("/{domain}")
    public ApiResponse<Void> delete(@PathVariable DomainCode domain) {
        domainSettingService.delete(domain);
        return ApiResponse.ok();
    }

    /**
     * 분야 하나의 켜짐 여부·이름·힌트를 고친다. 순서는 {@link #move}의 몫이라 여기서는
     * 건드리지 않는다.
     *
     * <p>힌트가 500자를 넘으면 400 — 유료 LLM 프롬프트에 그대로 실리는 값이라, 문서를 통째로
     * 붙여 넣는 실수가 매 배치 요금으로 돌아오는 것을 막는다({@code AdminDomainSettingRequest}
     * Javadoc). 없는 분야면 404(DOMAIN_001) — 목록이 늘 실제 있는 행만 보여 주므로 정상
     * 경로에서는 나지 않는다(다른 창에서 그 사이 {@link #delete}로 지워졌을 때만 난다).
     */
    @PutMapping("/{domain}")
    public ApiResponse<Void> edit(@PathVariable DomainCode domain,
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
    public ApiResponse<Void> move(@PathVariable DomainCode domain,
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
     * {@code ?domains=NETWORK,,OS}처럼 빈 조각이 섞이면 그 조각만 빼고 나머지로 계산한다
     * (최종 리뷰 Minor 4 — 전에는 그 자리가 {@code null}로 들어와 500이 났다).
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
            @RequestParam(required = false) List<DomainCode> domains,
            @RequestParam(defaultValue = "" + DEFAULT_PREVIEW_DAYS) int days) {
        if (days < 1 || days > MAX_PREVIEW_DAYS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "days는 1~" + MAX_PREVIEW_DAYS + " 사이여야 합니다: " + days);
        }
        // 빈 원소를 먼저 걷어낸다(최종 리뷰 Minor 4). ?domains=NETWORK,,OS처럼 쉼표가 겹치면
        // 스프링 변환기가 그 빈 조각을 null 원소로 넣어 주는데, 그대로 두면 preview가
        // plan.domain().value()에서 NPE를 내고 500이 된다. 관리 화면은 이런 주소를 만들지
        // 않지만(주소를 손으로 고쳐야 나온다), 500은 "서버가 고장났다"는 뜻이라 원인을 찾는 데
        // 시간을 쓰게 만든다 — 빈 조각은 "아무 분야도 아님"이므로 조용히 빼는 것이 사실에 맞다.
        // 400으로 되돌리는 안은 버렸다: 나머지 원소가 멀쩡한데 미리보기 전체를 막을 이유가 없다.
        List<DomainCode> target = (domains == null) ? List.of()
                : domains.stream().filter(java.util.Objects::nonNull).toList();
        if (target.isEmpty()) {
            // 전부 걸러졌거나 애초에 안 넘어왔으면 지금 저장된 순서로 보여 준다(위 Javadoc).
            target = domainSettingService.batchDomains();
        }
        return ApiResponse.ok(domainSettingService.preview(target, days));
    }
}
