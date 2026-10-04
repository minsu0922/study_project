package project.study.study_project.discussion.dto;

import project.study.study_project.discussion.domain.Comment;
import project.study.study_project.discussion.domain.CommentStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 댓글 한 건의 화면용 표현.
 *
 * @param nickname 글쓴이 표시 이름. 탈퇴했거나 보이지 않는 글이면 {@code null}
 * @param body     보이지 않는 글(가림·삭제)이면 {@code null} — 화면에서만 가리면 응답을 열어 읽을 수 있다
 * @param replies  답글. 답글 자신에게는 늘 빈 목록이다
 */
public record CommentItem(
        Long id,
        String nickname,
        String body,
        CommentStatus status,
        boolean mine,
        boolean edited,
        LocalDateTime createdAt,
        List<CommentItem> replies
) {

    public static CommentItem of(Comment comment, String nickname, Long viewerId, List<CommentItem> replies) {
        boolean visible = comment.isVisible();
        return new CommentItem(
                comment.getId(),
                visible ? nickname : null,
                visible ? comment.getBody() : null,
                comment.getStatus(),
                visible && viewerId != null && viewerId.equals(comment.getUserId()),
                comment.getEditedAt() != null,
                comment.getCreatedAt(),
                replies);
    }
}
