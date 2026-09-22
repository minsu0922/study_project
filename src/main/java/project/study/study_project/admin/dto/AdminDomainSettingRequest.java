package project.study.study_project.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 분야 설정 한 줄을 고치는 요청 — {@code PUT /api/admin/domain-settings/{domain}}.
 *
 * <p>{@code sortOrder}가 없는 것은 의도다. 순서는 이웃과 맞바꾸는 별개의 연산
 * ({@code DomainSettingService.move})이라, 이 요청에 순서값을 실으면 "이름만 고치려던" 호출도
 * 실수로 순서를 흔들 수 있다({@code DomainSetting.edit} Javadoc과 같은 이유).
 *
 * @param enabled     배치 자동 선택 후보로 켤지
 * @param displayName 화면에 뜨는 이름. 비우면 400 — {@code DomainSetting.edit}도 같은 규칙을
 *                     한 번 더 막지만, 여기서 먼저 걸러야 서비스까지 안 가고 검증 메시지로
 *                     곧장 이유가 보인다. 40자 상한은 컬럼 길이({@code display_name VARCHAR(40)})와
 *                     같다 — 이게 없으면 41자는 400이 아니라 DB 오류(500)로 떨어진다(최종 리뷰 Minor 3)
 * @param hint        모델에게 주는 경계 설명(선택). 500자 상한 — 이 값은 그대로 유료 LLM
 *                     프롬프트에 붙는다. 상한이 없으면 문서를 통째로 붙여 넣는 실수 하나가
 *                     매 배치마다 요금으로 돌아온다
 */
public record AdminDomainSettingRequest(

        boolean enabled,

        @NotBlank(message = "화면 이름은 비울 수 없습니다.")
        @Size(max = 40, message = "화면 이름은 40자를 넘을 수 없습니다.")
        String displayName,

        @Size(max = 500, message = "힌트는 500자를 넘을 수 없습니다.")
        String hint
) {

    /**
     * 화면 이름의 앞뒤 공백을 자른다 — 힌트가 {@code DomainHints.of}에서 잘리는 것과 같은 취급.
     *
     * <p><b>왜 여기(요청 레코드)서 자르나.</b> 화면도 {@code trim()}해서 보내지만 API를 직접 부르는
     * 쪽까지 믿을 수는 없다. 그리고 레코드 생성자에서 자르면 검증({@code @NotBlank}·{@code @Size})이
     * <b>잘린 값</b>을 보게 된다 — 앞뒤 공백 때문에 41자가 된 이름이 400으로 튕기지 않고, 저장될
     * 값과 검사한 값이 같아진다. 서비스나 엔티티에서 자르면 검사는 자르기 전 값으로 끝난 뒤다.
     */
    public AdminDomainSettingRequest {
        displayName = displayName == null ? null : displayName.trim();
    }
}
