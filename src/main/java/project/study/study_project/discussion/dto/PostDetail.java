package project.study.study_project.discussion.dto;

import project.study.study_project.discussion.domain.CommentStatus;
import project.study.study_project.discussion.domain.Post;
import project.study.study_project.discussion.domain.PostCategory;

import java.time.LocalDateTime;

/**
 * 글 한 건. 가린 글이면 제목·본문·닉네임이 {@code null}이다(이유는 {@link PostSummary}와 같다).
 *
 * @param problemId 이 글이 속한 토론방의 문제. 화면이 문제로 돌아가는 링크를 만든다
 * @param mine      보는 사람이 글쓴이인지. 가린 글에서는 늘 false — 고치거나 지울 수 없다
 */
public record PostDetail(
        Long id,
        Long problemId,
        PostCategory category,
        String categoryLabel,
        String title,
        String body,
        String nickname,
        CommentStatus status,
        boolean mine,
        boolean edited,
        LocalDateTime createdAt
) {

    public static PostDetail of(Post post, Long problemId, String nickname, Long viewerId) {
        boolean visible = post.isVisible();
        return new PostDetail(
                post.getId(),
                problemId,
                post.getCategory(),
                post.getCategory().getLabel(),
                visible ? post.getTitle() : null,
                visible ? post.getBody() : null,
                visible ? nickname : null,
                post.getStatus(),
                visible && viewerId != null && viewerId.equals(post.getUserId()),
                post.getEditedAt() != null,
                post.getCreatedAt());
    }
}
