package project.study.study_project.discussion.dto;

import project.study.study_project.discussion.domain.Post;

import java.time.LocalDateTime;

/**
 * 관리 콘솔에 보이는 "오류 지적" 글 한 건. 본문을 전문으로 싣는다 — 지적이 맞는지를 그 자리에서 판단한다.
 *
 * @param nickname     글쓴이 표시 이름. 탈퇴했으면 {@code null}
 * @param staffReplied 운영진이 댓글을 달았는가. 처리했다는 표시로 쓴다
 */
public record ErrataPostItem(
        Long id,
        String title,
        String body,
        String nickname,
        long commentCount,
        boolean staffReplied,
        LocalDateTime createdAt,
        Long problemId,
        String problemTitle,
        String domain
) {

    public static ErrataPostItem of(Post post, String nickname, long commentCount, boolean staffReplied,
                                    Long problemId, String problemTitle, String domain) {
        return new ErrataPostItem(post.getId(), post.getTitle(), post.getBody(), nickname, commentCount,
                staffReplied, post.getCreatedAt(), problemId, problemTitle, domain);
    }
}
