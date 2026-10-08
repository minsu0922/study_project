package project.study.study_project.discussion.dto;

import project.study.study_project.discussion.domain.Post;
import project.study.study_project.discussion.domain.PostCategory;

import java.time.LocalDateTime;

/**
 * 커뮤니티 최근 글의 한 줄. 여러 토론방의 글이 섞여 나오므로 어느 문제의 글인지를 함께 싣는다.
 *
 * @param nickname     글쓴이 표시 이름. 탈퇴했으면 {@code null}
 * @param problemTitle 문제 제목. 문제 목록에 이미 보이는 값이라 정답을 흘리지 않는다
 * @param domain       그 문제의 분야 코드. 화면이 분야 이름으로 바꿔 보여 주고 걸러 보는 데 쓴다
 */
public record RecentPostItem(
        Long id,
        PostCategory category,
        String categoryLabel,
        String title,
        String nickname,
        long commentCount,
        long likeCount,
        LocalDateTime createdAt,
        Long problemId,
        String problemTitle,
        String domain
) {

    public static RecentPostItem of(Post post, String nickname, long commentCount, long likeCount,
                                    Long problemId, String problemTitle, String domain) {
        return new RecentPostItem(post.getId(), post.getCategory(), post.getCategory().getLabel(),
                post.getTitle(), nickname, commentCount, likeCount,
                post.getCreatedAt(), problemId, problemTitle, domain);
    }
}
