package project.study.study_project.quiz.service;

import java.time.LocalDateTime;

/** 지금 복습할 문제 수. 학습 요약 카드가 쓴다. 복습 패키지가 구현한다 — 이유는 {@link SubmissionListener}와 같다. */
public interface DueReviewCounter {

    long countDue(Long userId, LocalDateTime now);
}
