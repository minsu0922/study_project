package project.study.study_project.quiz.dto;

import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.DomainCode;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.domain.Submission;

import java.time.LocalDateTime;

/**
 * 풀이 이력 한 줄. 답과 해설은 싣지 않는다 — 다시 풀러 갈 목록이라 정답이 보이면 안 된다.
 *
 * @param title 문제 제목. 없으면 지문
 */
public record SubmissionHistoryItem(
        Long submissionId,
        Long problemId,
        String title,
        DomainCode domain,
        Difficulty difficulty,
        boolean correct,
        LocalDateTime submittedAt
) {
    public static SubmissionHistoryItem from(Submission s) {
        Problem p = s.getProblem();
        return new SubmissionHistoryItem(s.getId(), p.getId(),
                p.getTitle() != null ? p.getTitle() : p.getQuestion(),
                p.getDomain(), p.getDifficulty(), s.isCorrect(), s.getSubmittedAt());
    }
}
