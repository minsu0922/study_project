package project.study.study_project.discussion.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.discussion.domain.Post;
import project.study.study_project.discussion.dto.PostLikeState;
import project.study.study_project.discussion.repository.PostLikeRepository;
import project.study.study_project.discussion.repository.PostRepository;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;
import project.study.study_project.user.support.SuspensionGuard;

import java.time.LocalDateTime;

/**
 * 글 추천(V34). 누르기와 거두기는 몇 번을 불러도 결과가 같다.
 *
 * <p>글쓰기와 달리 그 문제를 풀었는지는 보지 않는다. 읽기가 누구에게나 열려 있고,
 * 추천은 읽은 사람이 하는 일이다.
 */
@Service
@RequiredArgsConstructor
public class PostLikeService {

    private final PostLikeRepository postLikeRepository;
    private final PostRepository postRepository;
    private final UserRepository userRepository;

    /**
     * @throws BusinessException 없는 글 DISCUSSION_011, 가려진 글 DISCUSSION_006,
     *                           내 글 DISCUSSION_014, 정지 중 DISCUSSION_013
     */
    @Transactional
    public PostLikeState like(Long userId, Long postId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_003));
        // 추천도 쓰기다. 정지 중에 추천으로 글 순서를 움직일 수 있으면 정지가 뜻이 없다.
        SuspensionGuard.requireNotSuspended(user);
        Post post = requireVisible(postId);
        if (userId.equals(post.getUserId())) {
            throw new BusinessException(ErrorCode.DISCUSSION_014);
        }
        postLikeRepository.add(postId, userId, LocalDateTime.now());
        return new PostLikeState(postLikeRepository.countByPostId(postId), true);
    }

    /** 누르지 않은 글을 거둬도 오류가 아니다. 정지 중에도 거둘 수는 있다 — 줄이는 쪽은 막을 이유가 없다. */
    @Transactional
    public PostLikeState unlike(Long userId, Long postId) {
        requireVisible(postId);
        postLikeRepository.remove(postId, userId);
        return new PostLikeState(postLikeRepository.countByPostId(postId), false);
    }

    private Post requireVisible(Long postId) {
        Post post = postRepository.findById(postId)
                .filter(p -> !p.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.DISCUSSION_011));
        if (!post.isVisible()) {
            throw new BusinessException(ErrorCode.DISCUSSION_006);
        }
        return post;
    }
}
