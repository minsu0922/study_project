package project.study.study_project.discussion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.TestFixtures;
import project.study.study_project.discussion.domain.Post;
import project.study.study_project.discussion.domain.PostCategory;
import project.study.study_project.discussion.dto.PostDetail;
import project.study.study_project.discussion.dto.PostFilter;
import project.study.study_project.discussion.dto.PostLikeState;
import project.study.study_project.discussion.dto.PostSort;
import project.study.study_project.discussion.dto.RecentPostItem;
import project.study.study_project.discussion.service.PostLikeService;
import project.study.study_project.discussion.service.PostService;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.user.domain.User;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 글 추천(V34) — 한 사람이 한 번만 세어지고, 추천순 정렬이 그 수를 따르는지. */
@SpringBootTest
@Transactional
class PostLikeIntegrationTest {

    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private PostLikeService postLikeService;
    @Autowired
    private PostService postService;

    @Test
    @DisplayName("추천은 두 번 눌러도 한 번이고, 거두면 0으로 돌아온다")
    void likeIsIdempotent() {
        Problem problem = fixtures.problem();
        User author = fixtures.solver(problem);
        User reader = fixtures.solver(problem);
        Post post = fixtures.post(problem, author.getId());

        postLikeService.like(reader.getId(), post.getId());
        PostLikeState again = postLikeService.like(reader.getId(), post.getId());

        assertThat(again.likeCount()).isEqualTo(1);
        assertThat(again.liked()).isTrue();

        PostLikeState after = postLikeService.unlike(reader.getId(), post.getId());
        assertThat(after.likeCount()).isZero();
        assertThat(postLikeService.unlike(reader.getId(), post.getId()).likeCount()).isZero();
    }

    @Test
    @DisplayName("글 상세가 추천 수와 내가 눌렀는지를 준다")
    void detailCarriesLikeState() {
        Problem problem = fixtures.problem();
        User author = fixtures.solver(problem);
        User reader = fixtures.solver(problem);
        Post post = fixtures.post(problem, author.getId());
        postLikeService.like(reader.getId(), post.getId());

        PostDetail forReader = postService.detail(post.getId(), reader.getId());
        PostDetail forAuthor = postService.detail(post.getId(), author.getId());
        PostDetail forAnonymous = postService.detail(post.getId(), null);

        assertThat(forReader.likeCount()).isEqualTo(1);
        assertThat(forReader.liked()).isTrue();
        assertThat(forAuthor.liked()).isFalse();
        assertThat(forAnonymous.likeCount()).isEqualTo(1);
        assertThat(forAnonymous.liked()).isFalse();
    }

    @Test
    @DisplayName("내 글은 추천할 수 없다")
    void ownPostCannotBeLiked() {
        Problem problem = fixtures.problem();
        User author = fixtures.solver(problem);
        Post post = fixtures.post(problem, author.getId());

        assertThatThrownBy(() -> postLikeService.like(author.getId(), post.getId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DISCUSSION_014));
    }

    @Test
    @DisplayName("추천 많은 순은 추천 수가 큰 글을 앞에 둔다")
    void sortByLikes() {
        Problem problem = fixtures.problem();
        User author = fixtures.solver(problem);
        User a = fixtures.solver(problem);
        User b = fixtures.solver(problem);
        String key = UUID.randomUUID().toString().substring(0, 8);
        Post plain = fixtures.post(problem, author.getId(), PostCategory.QUESTION, "추천없음 " + key, "본문");
        Post one = fixtures.post(problem, author.getId(), PostCategory.QUESTION, "추천하나 " + key, "본문");
        Post two = fixtures.post(problem, author.getId(), PostCategory.QUESTION, "추천둘 " + key, "본문");
        postLikeService.like(a.getId(), one.getId());
        postLikeService.like(a.getId(), two.getId());
        postLikeService.like(b.getId(), two.getId());

        // 다른 테스트의 글과 섞이지 않게 이 테스트만의 낱말로 좁힌다
        List<RecentPostItem> items = postService.recent(
                PostFilter.community(key, PostSort.LIKES, null, null), 0).posts();

        assertThat(items).extracting(RecentPostItem::id)
                .containsExactly(two.getId(), one.getId(), plain.getId());
        assertThat(items).extracting(RecentPostItem::likeCount).containsExactly(2L, 1L, 0L);
    }
}
