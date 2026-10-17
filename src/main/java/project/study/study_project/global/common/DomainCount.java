package project.study.study_project.global.common;

/**
 * 분야 하나에 몇 건이 있는지 — 문서 목록·커뮤니티의 옆 기둥이 분야 옆에 적는 수.
 *
 * <p>표기 이름은 싣지 않는다. 화면이 이미 분야 목록(api.js DOMAINS)을 들고 있어 코드만으로 이름을 단다.
 *
 * @param domain 분야 코드 값(예: {@code NETWORK})
 */
public record DomainCount(String domain, long count) {
}
