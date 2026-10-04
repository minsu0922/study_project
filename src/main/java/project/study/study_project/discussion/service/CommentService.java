package project.study.study_project.discussion.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.discussion.domain.Comment;
import project.study.study_project.discussion.domain.CommentStatus;
import project.study.study_project.discussion.dto.CommentItem;
import project.study.study_project.discussion.dto.CommentListResponse;
import project.study.study_project.discussion.dto.CommentWriteRequest;
import project.study.study_project.discussion.repository.CommentRepository;
import project.study.study_project.discussion.repository.DiscussionRepository;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.quiz.repository.SubmissionRepository;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 문제별 토론 — 읽기, 쓰기, 수정, 삭제.
 *
 * <p>읽기는 누구에게나 열려 있고 쓰기는 그 문제를 푼 사람만 한다. 그 판정을 화면이 아니라 여기서 한다 —
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
    private final ProblemRepository problemRepository;
    private final SubmissionRepository submissionRepository;
    private final UserRepository userRepository;

    /**
     * @param viewerId 보는 사람. 비로그인이면 {@code null}
     */
    @Transactional(readOnly = true)
    public CommentListResponse list(Long problemId, Long viewerId, int page) {
        requireProblem(problemId);
        User viewer = viewerId == null ? null : userRepository.findById(viewerId).orElse(null);
        boolean solved = viewer != null && submissionRepository.existsByUserIdAndProblem_Id(viewerId, problemId);
        boolean canWrite = solved || (viewer != null && viewer.getRole() == Role.ADMIN);

        Optional<Long> discussionId = discussionRepository.findIdByProblemId(problemId);
        if (discussionId.isEmpty()) {
            return new CommentListResponse(solved, canWrite, 0, false, List.of());
        }

        Page<Comment> threads = commentRepository.findThreads(
                discussionId.get(), CommentStatus.DELETED, PageRequest.of(Math.max(page, 0), PAGE_SIZE));
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

        long total = commentRepository.countByDiscussionIdAndStatus(discussionId.get(), CommentStatus.VISIBLE);
        return new CommentListResponse(solved, canWrite, total, threads.hasNext(), items);
    }

    @Transactional
    public CommentItem write(Long userId, CommentWriteRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_003));
        Long problemId = request.problemId();
        requireProblem(problemId);
        if (user.getRole() != Role.ADMIN
                && !submissionRepository.existsByUserIdAndProblem_Id(userId, problemId)) {
            throw new BusinessException(ErrorCode.DISCUSSION_002);
        }
        if (user.getNickname() == null) {
            throw new BusinessException(ErrorCode.DISCUSSION_003);
        }

        // 방은 첫 댓글 때 만든다. 두 문장의 이유는 DiscussionRepository 주석에 있다.
        discussionRepository.insertIfAbsent(problemId);
        Long discussionId = discussionRepository.findIdByProblemIdForShare(problemId)
                .orElseThrow(() -> new BusinessException(ErrorCode.QUIZ_001));

        Long rootId = null;
        if (request.parentId() != null) {
            // 다른 방의 댓글은 "없는 댓글"로 답한다 — 받아 주면 답글이 다른 문제의 원글에 매달린다.
            Comment parent = commentRepository.findById(request.parentId())
                    .filter(p -> p.getDiscussionId().equals(discussionId))
                    .orElseThrow(() -> new BusinessException(ErrorCode.DISCUSSION_001));
            if (!parent.isVisible()) {
                throw new BusinessException(ErrorCode.DISCUSSION_006);
            }
            rootId = parent.getParentId() != null ? parent.getParentId() : parent.getId();
        }

        Comment saved = commentRepository.save(Comment.of(discussionId, userId, rootId, request.body().trim()));
        log.info("댓글 작성: problemId={} commentId={} reply={}", problemId, saved.getId(), rootId != null);
        return CommentItem.of(saved, user.getNickname(), userId, List.of());
    }

    @Transactional
    public CommentItem edit(Long userId, Long commentId, String body) {
        Comment comment = requireOwn(userId, commentId);
        if (!comment.isVisible()) {
            throw new BusinessException(ErrorCode.DISCUSSION_006);
        }
        comment.edit(body.trim());
        String nickname = userRepository.findById(userId).map(User::getNickname).orElse(null);
        return CommentItem.of(comment, nickname, userId, List.of());
    }

    /** 이미 지운 글을 또 지워도 오류가 아니다 — 두 번 눌린 삭제 버튼에 실패를 보여 줄 이유가 없다. */
    @Transactional
    public void delete(Long userId, Long commentId) {
        requireOwn(userId, commentId).delete();
    }

    /** 문제별 보이는 댓글 수. 글이 없는 문제는 맵에 없다. */
    @Transactional(readOnly = true)
    public Map<Long, Long> counts(Collection<Long> problemIds) {
        Map<Long, Long> counts = new HashMap<>();
        if (problemIds == null || problemIds.isEmpty()) {
            return counts;
        }
        commentRepository.countByProblemIds(problemIds, CommentStatus.VISIBLE)
                .forEach(row -> counts.put(row.getProblemId(), row.getCnt()));
        return counts;
    }

    private void requireProblem(Long problemId) {
        if (!problemRepository.existsById(problemId)) {
            throw new BusinessException(ErrorCode.QUIZ_001);
        }
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
