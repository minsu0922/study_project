package project.study.study_project.llm.dto;

import jakarta.validation.constraints.Size;
import project.study.study_project.llm.client.ProblemReview;

/**
 * 제안 없는 AI 지적을 검수자가 처리할 때 보내는 바디.
 *
 * @param target      고칠 칸. {@code null}이면 "문제 영향 없음"으로 처리만 한다
 * @param choiceIndex 보기·오답 이유를 고칠 때 그 보기 위치(0부터)
 * @param text        고친 글 전체
 */
public record AiFindingResolveRequest(ProblemReview.FixTarget target, Integer choiceIndex,
                                      @Size(max = 5000) String text) {
}
