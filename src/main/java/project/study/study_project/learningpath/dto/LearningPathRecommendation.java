package project.study.study_project.learningpath.dto;

/**
 * 홈의 추천 코스 한 장. 화면은 reason으로 "왜 이 코스인지" 한 줄을 고른다.
 *
 * @param path       목록과 같은 요약(진도·다음 단계 포함)
 * @param reason     추천 이유. 선언 순서가 곧 우선순위다
 * @param afterTitle NEXT_COURSE일 때 끝낸 앞 코스의 이름. 그 밖에는 null
 */
public record LearningPathRecommendation(LearningPathSummary path, RecommendReason reason, String afterTitle) {

    public enum RecommendReason {
        /** 끝낸 추천 코스의 바로 다음 추천 코스(관리자가 정한 순서), 아직 시작 전 */
        NEXT_COURSE,
        /** 시작했지만 이어서 하기 카드에 밀린 코스 */
        STARTED,
        /** 시작한 적은 없지만 이 코스의 문제를 이미 맞힌 적이 있다(분야 퀴즈로 푼 경우 등) */
        HALF_DONE,
        /** 관리자가 짠 추천 코스, 아직 손대지 않음 */
        CURATED,
        /** 아직 손대지 않은 분야·코드 코스 */
        NEW
    }
}
