package project.study.study_project.document.dto;

import project.study.study_project.global.common.DomainCount;

import java.util.List;

/**
 * 문서 목록 옆 기둥에 적을 수 — 공개 문서만 센다.
 *
 * @param domains 분야별 문서 수. 문서가 없는 분야는 빠진다
 * @param tags    태그별 문서 수. 많은 것부터, 같으면 이름순
 */
public record DocumentFacets(List<DomainCount> domains, List<TagCount> tags) {

    public record TagCount(String name, long count) {
    }
}
