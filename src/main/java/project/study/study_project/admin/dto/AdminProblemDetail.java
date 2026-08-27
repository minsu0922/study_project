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
        /**
         * 목록에 뜰 한 줄 제목. 비어 있으면 {@code null}을 <b>그대로</b> 내린다 —
         * 여기서 지문 앞부분으로 채워 주면 수정 폼이 그 가짜 값을 진짜 제목으로 저장해 버린다.
         * "제목이 없다"는 사실은 백필 대상을 고르는 근거이기도 하므로 지우면 안 된다.
         */
        String title,
        String question,
        String answer,
        String explanation,
        LocalDateTime createdAt,
        List<ChoiceDetail> choices
) {
    /**
     * @param rationale 이 오답이 왜 틀렸는지 한 줄(V15). 정답 보기와 옛 문제는 {@code null}.
     *                  <b>수정 폼이 이 값을 되채운다</b> — 안 내려주면 문제를 한 번 수정할 때마다
     *                  오답 설명이 통째로 지워진다. 폼은 화면에 있는 것만 보내기 때문이다.
     */
    public record ChoiceDetail(Long id, int seq, String text, boolean correct, String rationale) {
        static ChoiceDetail from(Choice c) {
            return new ChoiceDetail(c.getId(), c.getSeq(), c.getText(), c.isCorrect(), c.getRationale());
        }
    }

    public static AdminProblemDetail from(Problem p) {
        return new AdminProblemDetail(
                p.getId(), p.getDomain(), p.getDifficulty(), p.getType(), p.getTitle(),
                p.getQuestion(), p.getAnswer(), p.getExplanation(), p.getCreatedAt(),
                p.getChoices().stream().map(ChoiceDetail::from).toList()
        );
    }
}
