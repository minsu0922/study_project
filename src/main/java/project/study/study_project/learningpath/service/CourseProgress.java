package project.study.study_project.learningpath.service;

import java.util.List;

/**
 * 한 코스의 내 진도. 저장하지 않고 제출 기록에서 센다(LearningPathStatsRepository).
 *
 * @param steps    단계마다 문제 수·제출한 수·맞힌 수
 * @param nextSeq  다음에 추천할 단계 — 아직 안 푼 문제가 남은 첫 단계. 다 풀었으면 {@code null}.
 *                 맞힌 수로 고르지 않는 이유: 어려운 단계를 못 맞히면 추천이 그 자리에 영영 멈춘다.
 *                 단계는 잠그지 않으므로 이 값은 권유일 뿐이다(docs/26)
 */
public record CourseProgress(List<StepProgress> steps, Integer nextSeq) {

    public record StepProgress(int seq, long total, long attempted, long solved) {

        /** 단계의 문제를 전부 맞혔다 */
        public boolean done() {
            return total > 0 && solved >= total;
        }
    }

    public long total() {
        return steps.stream().mapToLong(StepProgress::total).sum();
    }

    public long solved() {
        return steps.stream().mapToLong(StepProgress::solved).sum();
    }

    public long attempted() {
        return steps.stream().mapToLong(StepProgress::attempted).sum();
    }
}
