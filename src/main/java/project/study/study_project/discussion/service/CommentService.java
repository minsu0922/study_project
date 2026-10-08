package project.study.study_project.discussion.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.discussion.domain.Comment;
import project.study.study_project.discussion.domain.CommentStatus;
import project.study.study_project.discussion.domain.Discussion;
import project.study.study_project.discussion.domain.Post;
import project.study.study_project.discussion.repository.PostRepository;
import project.study.study_project.discussion.dto.CommentItem;
import project.study.study_project.discussion.dto.CommentListResponse;
import project.study.study_project.discussion.dto.CommentWriteRequest;
import project.study.study_project.discussion.repository.CommentRepository;
import project.study.study_project.discussion.repository.DiscussionRepository;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.notification.domain.NotificationType;
import project.study.study_project.notification.service.NotificationService;
import project.study.study_project.quiz.repository.SubmissionRepository;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;
import project.study.study_project.user.support.SuspensionGuard;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 글에 달리는 댓글 — 읽기, 쓰기, 수정, 삭제.
 *
 * <p>읽기는 누구에게나 열려 있고 쓰기는 그 글이 속한 문제를 푼 사람만 한다. 그 판정을 화면이 아니라 여기서 한다 —
 * 화면에서만 막으면 주소를 직접 불러 쓸 수 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentService {

    /** 한 쪽의 댓글 수. 답글은 댓글에 딸려 전부 함께 간다. */
    private static final int PAGE_SIZE = 20;

    private final CommentRepository commentRepository;
    private final DiscussionRepository discussionRepository;
    private final PostRepository postRepository;
    private final SubmissionRepository submissionRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    /**
     * @param viewerId 보는 사람. 비로그인이면 {@code null}
     */
    @Transactional(readOnly = true)
    public CommentListResponse list(Long postId, Long viewerId, int page) {
        Post post = requirePost(postId);
        Long problemId = problemIdOf(post);
        User viewer = viewerId == null ? null : userRepository.findById(viewerId).orElse(null);
        boolean solved = viewer != null && submissionRepository.existsByUserIdAndProblem_Id(viewerId, problemId);
        // 가린 글 아래의 댓글이 그대로 보이면 댓글만 읽어도 가린 내용을 짐작할 수 있다.
        if (!post.isVisible()) {
            return new CommentListResponse(solved, false, 0, false, null, List.of());
        }
        boolean canWrite = (solved || (viewer != null && viewer.getRole() == Role.ADMIN))
                && !viewer.isSuspended(LocalDateTime.now());

        Page<Comment> threads = commentRepository.findThreads(
                postId, CommentStatus.DELETED, PageRequest.of(Math.max(page, 0), PAGE_SIZE));
        List<Long> threadIds = threads.getContent().stream().map(Comment::getId).toList();
        List<Comment> replies = threadIds.isEmpty()
                ? List.of()
                : commentRepository.findReplies(threadIds, CommentStatus.DELETED);

        Map<Long, String> nicknames = nicknamesOf(threads.getContent(), replies);
        Map<Long, List<CommentItem>> repliesByParent = new LinkedHashMap<>();
        for (Comment reply : replies) {
            repliesByParent.computeIfAbsent(reply.getParentId(), id -> new ArrayList<>())
                    .add(CommentItem.of(reply, nicknames.get(reply.getUserId()), viewerId, List.of()));
        }
        List<CommentItem> items = threads.getContent().stream()
                .map(c -> CommentItem.of(c, nicknames.get(c.getUserId()), viewerId,
                        repliesByParent.getOrDefault(c.getId(), List.of())))
                .toList();

        long total = commentRepository.countByPostIdAndStatus(postId, CommentStatus.VISIBLE);
        return new CommentListResponse(solved, canWrite, total, threads.hasNext(),
                SuspensionGuard.noticeFor(viewer), items);
    }

    @Transactional
    public CommentItem write(Long userId, CommentWriteRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_003));
        SuspensionGuard.requireNotSuspended(user);
        Post post = requirePost(request.postId());
        if (!post.isVisible()) {
            throw new BusinessException(ErrorCode.DISCUSSION_006);
        }
        Long postId = post.getId();
        Long problemId = problemIdOf(post);
        if (user.getRole() != Role.ADMIN
                && !submissionRepository.existsByUserIdAndProblem_Id(userId, problemId)) {
            throw new BusinessException(ErrorCode.DISCUSSION_002);
        }
        if (user.getNickname() == null) {
            throw new BusinessException(ErrorCode.DISCUSSION_003);
        }

        Long rootId = null;
        Long parentAuthorId = null;
        if (request.parentId() != null) {
            // 다른 글의 댓글은 "없는 댓글"로 답한다 — 받아 주면 답글이 다른 글의 댓글에 매달린다.
            Comment parent = commentRepository.findById(request.parentId())
                    .filter(p -> p.getPostId().equals(postId))
                    .orElseThrow(() -> new BusinessException(ErrorCode.DISCUSSION_001));
            if (!parent.isVisible()) {
                throw new BusinessException(ErrorCode.DISCUSSION_006);
            }
            rootId = parent.getParentId() != null ? parent.getParentId() : parent.getId();
            parentAuthorId = parent.getUserId();
        }

        Comment saved = commentRepository.save(Comment.of(postId, userId, rootId, request.body().trim()));
        log.info("댓글 작성: postId={} commentId={} reply={}", postId, saved.getId(), rootId != null);
        notifyWritten(post, parentAuthorId, user);
        return CommentItem.of(saved, user.getNickname(), userId, List.of());
    }

    @Transactional
    public CommentItem edit(Long userId, Long commentId, String body) {
        Comment comment = requireOwn(userId, commentId);
        if (!comment.isVisible()) {
            throw new BusinessException(ErrorCode.DISCUSSION_006);
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_003));
        SuspensionGuard.requireNotSuspended(user);
        comment.edit(body.trim());
        return CommentItem.of(comment, user.getNickname(), userId, List.of());
    }

    /** 이미 지운 글을 또 지워도 오류가 아니다 — 두 번 눌린 삭제 버튼에 실패를 보여 줄 이유가 없다. */
    @Transactional
    public void delete(Long userId, Long commentId) {
        requireOwn(userId, commentId).delete();
    }

    /**
     * 글쓴이와 답글 대상에게 알린다. 둘이 같은 사람이면 답글 알림 하나만 보낸다 —
     * 같은 댓글로 알림이 두 줄 오면 하나는 소음이다.
     */
    private void notifyWritten(Post post, Long parentAuthorId, User writer) {
        String link = "/post.html?id=" + post.getId();
        if (parentAuthorId != null) {
            notificationService.notify(parentAuthorId, writer.getId(), NotificationType.COMMENT_REPLY,
                    writer.getNickname() + "님이 내 댓글에 답글을 달았습니다: " + post.getTitle(), link);
        }
        if (post.getUserId() != null && !post.getUserId().equals(parentAuthorId)) {
            notificationService.notify(post.getUserId(), writer.getId(), NotificationType.POST_COMMENT,
                    writer.getNickname() + "님이 내 글에 댓글을 달았습니다: " + post.getTitle(), link);
        }
    }

    /** 지운 글은 없는 글로 답한다({@link PostService}와 같은 판단). */
    private Post requirePost(Long postId) {
        return postRepository.findById(postId)
                .filter(p -> !p.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.DISCUSSION_011));
    }

    private Long problemIdOf(Post post) {
        return discussionRepository.findById(post.getDiscussionId())
                .map(Discussion::getProblemId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DISCUSSION_011));
    }

    /** 존재를 먼저 보고 주인을 본다 — 뒤집으면 없는 id에 "권한 없음"이 나간다. */
    private Comment requireOwn(Long userId, Long commentId) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DISCUSSION_001));
        if (!userId.equals(comment.getUserId())) {
            throw new BusinessException(ErrorCode.DISCUSSION_005);
        }
        return comment;
    }

    /** 글쓴이 id → 닉네임. 탈퇴한 사용자는 id가 null이라 맵에 없다. */
    private Map<Long, String> nicknamesOf(List<Comment> threads, List<Comment> replies) {
        List<Long> userIds = java.util.stream.Stream.concat(threads.stream(), replies.stream())
                .map(Comment::getUserId).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> nicknames = new HashMap<>();
        userRepository.findAllById(userIds).forEach(u -> nicknames.put(u.getId(), u.getNickname()));
        return nicknames;
    }
}
