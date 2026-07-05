package project.study.study_project.admin.dto;

import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.quiz.domain.Choice;
import project.study.study_project.quiz.domain.Problem;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 관리자용 문제 상세 — 풀이용(QuizProblemItem)과 달리 <b>정답·해설·보기 정답 여부를 전부 포함</b>한다.
 * 관리자는 채점 규칙을 관리하는 사람이라 감출 이유가 없고, 수정 폼을 채우려면 필요하다.
 * (이 DTO가 실리는 /api/admin/** 경로 전체가 SecurityConfig에서 ADMIN 전용으로 잠겨 있다)
 */
public record AdminProblemDetail(
        Long id,
        Domain domain,
        Difficulty difficulty,
        ProblemType type,
        String question,
        String answer,
        String explanation,
        LocalDateTime createdAt,
        List<ChoiceDetail> choices
) {
    public record ChoiceDetail(Long id, int seq, String text, boolean correct) {
        static ChoiceDetail from(Choice c) {
            return new ChoiceDetail(c.getId(), c.getSeq(), c.getText(), c.isCorrect());
        }
    }

    public static AdminProblemDetail from(Problem p) {
        return new AdminProblemDetail(
                p.getId(), p.getDomain(), p.getDifficulty(), p.getType(),
                p.getQuestion(), p.getAnswer(), p.getExplanation(), p.getCreatedAt(),
                p.getChoices().stream().map(ChoiceDetail::from).toList()
        );
    }
}
