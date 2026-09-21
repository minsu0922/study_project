package project.study.study_project.quiz.dto;

import project.study.study_project.llm.domain.DomainSetting;

/**
 * {@code GET /api/domains} 한 줄 — 화면이 필요로 하는 최소한만 담는다.
 *
 * <p>{@code code}는 {@link project.study.study_project.global.common.Domain} 상수명이다
 * (예: {@code NETWORK}). 화면 JS의 {@code DOMAINS} 배열이 {@code [code, displayName]} 쌍의
 * 목록이던 것과 그대로 맞춰, 응답을 받은 쪽이 배열로 변환하는 코드 한 줄만 바꾸면 되게 했다
 * (api.js 주석 참고).
 *
 * <p>{@link DomainSetting}의 {@code enabled}·{@code sortOrder}·{@code hint}는 여기 담지
 * 않는다 — 이 API는 <b>학습자 화면의 필터/이름표</b>가 전부고, 그 값들은 관리자 설정 화면
 * (9번 작업)의 몫이다. 필드를 더 내려 주면 "학습자 화면이 관리 값을 알아야 하나"라는
 * 질문이 생긴다.
 */
public record DomainResponse(String code, String displayName) {

    public static DomainResponse from(DomainSetting setting) {
        return new DomainResponse(setting.getDomain().name(), setting.getDisplayName());
    }
}
