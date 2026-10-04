package project.study.study_project.discussion.dto;

import project.study.study_project.discussion.domain.CommentStatus;
import project.study.study_project.discussion.domain.Post;

import java.time.LocalDateTime;

/**
 * 글 목록의 한 줄. 본문은 싣지 않는다 — 목록에서 쓰지 않고, 20건이면 최대 10만 자가 된다.
 *
 * @param title    가린 글이면 {@code null} — 화면에서만 가리면 응답을 열어 읽을 수 있다
 * @param nickname 글쓴이 표시 이름. 탈퇴했거나 가린 글이면 {@code null}
 */
public record PostSummary(
        Long id,
        String title,
        String nickname,
        CommentStatus status,
        LocalDateTime createdAt
) {

    public static PostSummary of(Post post, String nickname) {
        boolean visible = post.isVisible();
        return new PostSummary(
                post.getId(),
                visible ? post.getTitle() : null,
                visible ? nickname : null,
                post.getStatus(),
                post.getCreatedAt());
    }
}
