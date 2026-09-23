package project.study.study_project.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import project.study.study_project.global.common.DomainCode;

/**
 * 새 분야를 등록하는 요청 — {@code POST /api/admin/domain-settings}(6번 작업).
 *
 * <p><b>코드 형식은 여기서 따로 검사하지 않는다.</b> {@code code}의 타입이 이미
 * {@link DomainCode}라, JSON 문자열을 이 타입으로 바꾸는 순간(Jackson {@code @JsonCreator})
 * {@link DomainCode}의 정규식 검사({@code ^[A-Z][A-Z0-9_]{1,29}$})를 통과 못 하면
 * {@code IllegalArgumentException}이 나고 {@code GlobalExceptionHandler}가 그것을
 * {@code HttpMessageNotReadableException}으로 받아 400(COMMON_001)으로 바꾼다 — 몸통 파싱
 * 단계에서 이미 걸러지므로 {@code @Pattern}을 여기 또 달 필요가 없다.
 *
 * <p><b>코드 중복은 여기서 못 본다.</b> "이미 등록된 코드인가"는 DB의 사실이라 요청 검증
 * 단계(자바 객체만 보는 Bean Validation)가 알 수 없다 — {@code DomainSettingService#create}가
 * 저장 직전에 {@code DomainSettingRepository#existsByDomain}으로 확인해 400(DOMAIN_004)을 낸다.
 *
 * <p>{@code displayName}·{@code hint} 규칙은 {@link AdminDomainSettingRequest}와 같다(40자·500자
 * 상한, 이유도 그 클래스 Javadoc과 동일) — 수정과 추가가 같은 제약을 어긋나게 두면 "수정으로는
 * 되는데 추가로는 안 되는" 혼란이 생긴다.
 */
public record AdminDomainCreateRequest(

        @NotNull(message = "분야 코드는 필수입니다.")
        DomainCode code,

        @NotBlank(message = "화면 이름은 비울 수 없습니다.")
        @Size(max = 40, message = "화면 이름은 40자를 넘을 수 없습니다.")
        String displayName,

        @Size(max = 500, message = "힌트는 500자를 넘을 수 없습니다.")
        String hint
) {

    /**
     * 화면 이름의 앞뒤 공백을 자른다 — {@link AdminDomainSettingRequest}와 같은 이유(검증이
     * 잘린 값을 보게 해, 저장될 값과 검사한 값을 같게 만든다).
     */
    public AdminDomainCreateRequest {
        displayName = displayName == null ? null : displayName.trim();
    }
}
