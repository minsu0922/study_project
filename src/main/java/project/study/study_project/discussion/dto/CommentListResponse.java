package project.study.study_project.discussion.dto;

import java.util.List;

/**
 * 한 글의 댓글 한 쪽. 가린 글이면 댓글도 내보내지 않는다.
 *
 * @param solved   보는 사람이 이 글이 속한 문제를 풀었는지
 * @param canWrite 쓸 수 있는지 — 풀었거나 관리자이고, 글이 가려지지 않았다
 * @param total    보이는 댓글·답글 수. 가려지거나 삭제된 글은 세지 않는다
 * @param hasNext  다음 쪽이 있는지
 */
public record CommentListResponse(
        boolean solved,
        boolean canWrite,
        long total,
        boolean hasNext,
        List<CommentItem> comments
) {
}
