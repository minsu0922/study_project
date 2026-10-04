package project.study.study_project.discussion.dto;

import java.util.List;

/**
 * 한 문제의 토론 한 쪽.
 *
 * @param solved   보는 사람이 이 문제를 풀었는지. 화면이 토론을 접어 둘지 정한다
 * @param canWrite 쓸 수 있는지 — 풀었거나 관리자다
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
