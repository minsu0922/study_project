package project.study.study_project.admin.dto;

import project.study.study_project.llm.domain.DomainSetting;
import project.study.study_project.llm.support.DomainHints;

/**
 * 분야 설정 관리 화면 한 줄.
 *
 * @param code          분야 코드({@link project.study.study_project.global.common.DomainCode#value()}).
 *                       화면은 문자열로 받아 그대로 API 주소({@code /{domain}/move} 등)에 되돌려 쓴다
 * @param enabled       배치 자동 선택 후보인지
 * @param sortOrder     날짜 순환 순서 — 이 값이 곧 미리보기가 계산하는 순서다
 * @param displayName   화면에 뜨는 이름
 * @param hint          입력칸에 그대로 채울 원문(괄호 없음) — {@link DomainSetting#getHint()}
 * @param promptPreview 프롬프트에 그대로 이어 붙는 꼴, 앞 공백과 괄호까지 포함
 *                       ({@link DomainHints#hintFor}). 화면이 "실제로 모델에게 나갈 것"을
 *                       상상이 아니라 눈으로 보여 주려고 원문과 따로 둔다(task-9-brief)
 */
public record AdminDomainSettingResponse(
        String code,
        boolean enabled,
        int sortOrder,
        String displayName,
        String hint,
        String promptPreview
) {

    public static AdminDomainSettingResponse from(DomainSetting setting, DomainHints hints) {
        return new AdminDomainSettingResponse(
                setting.getDomain().value(),
                setting.isEnabled(),
                setting.getSortOrder(),
                setting.getDisplayName(),
                setting.getHint(),
                hints.hintFor(setting.getDomain()));
    }
}
