package project.study.study_project.learningpath.dto;

import java.time.LocalDateTime;

/**
 * 로드맵 목록의 한 줄, 홈의 "이어서 하기" 카드.
 *
 * @param key           코스 키(domain:NETWORK 등). 상세 주소에 그대로 쓴다
 * @param kind          CUSTOM(추천 코스) / DOMAIN(분야별) / CODE(코드 문제)
 * @param solved        맞힌 적 있는 문제 수. 진도 막대는 solved / total
 * @param nextSeq       다음 추천 단계. 다 풀었으면 null
 * @param nextStepTitle 다음 추천 단계의 이름. 홈 카드가 "다음: ○○"로 쓴다
 * @param lastStudiedAt 시작한 코스만 차 있다
 */
public record LearningPathSummary(
        String key,
        String kind,
        String title,
        String description,
        int stepCount,
        long total,
        long attempted,
        long solved,
        Integer nextSeq,
        String nextStepTitle,
        boolean started,
        LocalDateTime lastStudiedAt
) {
}
