package project.study.study_project.review.dto;

import project.study.study_project.global.common.DomainCount;

import java.util.List;

/**
 * 복습 현황 옆 기둥에 적을 수.
 *
 * @param domains   분야별 항목 수(복습 중 + 졸업). 항목이 없는 분야는 빠진다
 * @param learning  복습 중인 항목 수
 * @param graduated 졸업한 항목 수
 */
public record ReviewCounts(List<DomainCount> domains, long learning, long graduated) {
}
