package project.study.study_project.discussion.dto;

import java.time.LocalDateTime;

/**
 * 내가 쓴 댓글 한 줄 — 어느 글에 무엇을 썼는지.
 *
 * @param hidden 관리자가 가린 댓글인가. 가려진 것도 본인에게는 보여 준다 — 왜 안 보이는지 알아야 한다
 */
public record MyCommentItem(
        Long id,
        Long postId,
        String postTitle,
        String body,
        boolean hidden,
        LocalDateTime createdAt
) {
}
