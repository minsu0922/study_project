package project.study.study_project.llm.support;

import project.study.study_project.global.common.DomainCode;

/**
 * 분야 한 칸의 전체 모습 — 코드·켜짐 여부·정렬 순서·이름·힌트를 한 번에 묶는다.
 *
 * <p>화면(관리자 "분야 설정" 목록)과 배치(날짜 순환)가 똑같이 이 다섯 가지를 필요로 한다.
 * 둘이 각자 {@link DomainCatalog}를 조합해 만들면 "화면엔 있는데 배치엔 빠진 필드"가
 * 생기기 쉽다 — 한 레코드로 묶어 두면 그럴 일이 없다.
 *
 * @param code        분야 코드 (DB 기본키·API 표기)
 * @param enabled     꺼진 분야는 배치·문제 생성 후보에서 빠진다(마지막 하나는 끌 수 없음 — DomainSettingService)
 * @param sortOrder   화면 목록·날짜 순환에서 쓰는 순서
 * @param displayName 화면에 보이는 한글 이름
 * @param hint        분야 경계 설명(프롬프트에 이어 붙일 원문, 없으면 빈 문자열)
 */
public record DomainEntry(DomainCode code, boolean enabled, int sortOrder, String displayName, String hint) {
}
