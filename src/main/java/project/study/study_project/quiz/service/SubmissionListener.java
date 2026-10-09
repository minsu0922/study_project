package project.study.study_project.quiz.service;

import project.study.study_project.quiz.domain.Submission;

/**
 * 채점된 제출을 받아 자기 상태를 고치는 쪽(복습 사다리, 오늘의 퀴즈)이 구현한다.
 *
 * <p>{@link QuizService#submit}이 제출을 저장한 직후 <b>같은 트랜잭션 안에서</b> 차례로 부른다.
 * 이벤트로 바꾸지 않은 이유가 그것이다 — "이력은 남았는데 복습 상태만 안 바뀐" 상태가 없어야 한다.
 * 구현은 {@code Propagation.MANDATORY}로 둔다.
 *
 * <p>인터페이스가 여기 있는 이유: 채점이 복습·데일리를 직접 부르면 그쪽도 문제(Problem)를 쓰므로
 * 두 패키지가 서로 기댄다. 듣는 쪽이 이 패키지에 기대는 한 방향만 남긴다.
 */
public interface SubmissionListener {

    void onSubmission(Long userId, Submission submission);
}
