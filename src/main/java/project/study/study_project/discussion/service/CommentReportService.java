package project.study.study_project.discussion.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.discussion.domain.Comment;
import project.study.study_project.discussion.domain.CommentReport;
import project.study.study_project.discussion.domain.CommentStatus;
import project.study.study_project.discussion.domain.Discussion;
import project.study.study_project.discussion.domain.Post;
import project.study.study_project.discussion.dto.CommentReportItem;
import project.study.study_project.discussion.dto.CommentReportRequest;
import project.study.study_project.discussion.dto.PostReportRequest;
import project.study.study_project.discussion.repository.CommentReportRepository;
import project.study.study_project.discussion.repository.CommentRepository;
import project.study.study_project.discussion.repository.DiscussionRepository;
import project.study.study_project.discussion.repository.PostRepository;
import project.study.study_project.global.common.Texts;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.global.response.PageResponse;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.report.domain.ReportStatus;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;
import project.study.study_project.user.support.SuspensionGuard;

import java.time.LocalDateTime;

/**
 * 글·댓글 신고 — 접수(학습자)와 판정(관리자).
 *
 * <p>가림은 관리자만 한다(2026-10-03 사용자 결정). 신고가 쌓여도 글은 저절로 가려지지 않는다 —
 * 여럿이 짜고 멀쩡한 글을 내리는 길을 열지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentReportService {

    private final CommentReportRepository reportRepository;
    private final CommentRepository commentRepository;
    private final PostRepository postRepository;
    private final DiscussionRepository discussionRepository;
    private final ProblemRepository problemRepository;
    private final UserRepository userRepository;

    /* ── 학습자 ───────────────────────────────────────────── */

    /** 중복은 두 겹으로 막는다 — 미리 세어 안내하고, 끼어든 요청은 유일 제약이 막는다(문제 제보와 같은 방식). */
    @Transactional
    public CommentReportItem report(Long userId, CommentReportRequest request) {
        requireNotSuspended(userId);
        Comment comment = requireComment(request.commentId());
        if (!comment.isVisible()) {
            throw new BusinessException(ErrorCode.DISCUSSION_006);
        }
        rejectOwn(userId, comment.getUserId());
        if (reportRepository.existsByCommentIdAndUserId(comment.getId(), userId)) {
            throw new BusinessException(ErrorCode.DISCUSSION_007);
        }
        try {
            CommentReport saved = reportRepository.saveAndFlush(
                    CommentReport.of(comment, userId, request.reason(), Texts.trimToNull(request.detail())));
            log.info("댓글 신고 접수: commentId={} reason={}", comment.getId(), request.reason());
            return toItem(saved);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.DISCUSSION_007);
        }
    }

    /** 글 신고. 지운 글은 없는 글로, 이미 가린 글은 처리된 것으로 답한다. */
    @Transactional
    public CommentReportItem reportPost(Long userId, PostReportRequest request) {
        requireNotSuspended(userId);
        Post post = requirePost(request.postId());
        if (post.isDeleted()) {
            throw new BusinessException(ErrorCode.DISCUSSION_011);
        }
        if (!post.isVisible()) {
            throw new BusinessException(ErrorCode.DISCUSSION_006);
        }
        rejectOwn(userId, post.getUserId());
        if (reportRepository.existsByPostIdAndUserId(post.getId(), userId)) {
            throw new BusinessException(ErrorCode.DISCUSSION_007);
        }
        try {
            CommentReport saved = reportRepository.saveAndFlush(
                    CommentReport.ofPost(post, userId, request.reason(), Texts.trimToNull(request.detail())));
            log.info("글 신고 접수: postId={} reason={}", post.getId(), request.reason());
            return toItem(saved);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.DISCUSSION_007);
        }
    }

    /* ── 관리자 ───────────────────────────────────────────── */

    @Transactional(readOnly = true)
    public PageResponse<CommentReportItem> getReports(ReportStatus status, Pageable pageable) {
        Page<CommentReport> page = status == ReportStatus.PENDING
                ? reportRepository.findOldestFirst(status, pageable)
                : reportRepository.findNewestFirst(status, pageable);
        return PageResponse.from(page.map(this::toItem));
    }

    @Transactional(readOnly = true)
    public long pendingCount() {
        return reportRepository.countByStatus(ReportStatus.PENDING);
    }

    /** 가림 — 그 글에 걸린 대기 신고를 모두 인정으로 닫는다. 같은 글의 신고를 하나씩 누르게 하지 않는다. */
    @Transactional
    public CommentStatus hide(Long commentId) {
        Comment comment = requireComment(commentId);
        if (comment.getStatus() == CommentStatus.DELETED) {
            throw new BusinessException(ErrorCode.DISCUSSION_006);
        }
        comment.hide();
        int accepted = reportRepository.acceptPendingOf(
                commentId, ReportStatus.PENDING, ReportStatus.ACCEPTED, LocalDateTime.now());
        log.info("댓글 가림: commentId={} 닫힌 신고={}", commentId, accepted);
        return comment.getStatus();
    }

    @Transactional
    public CommentStatus restore(Long commentId) {
        Comment comment = requireComment(commentId);
        if (comment.getStatus() != CommentStatus.HIDDEN) {
            throw new BusinessException(ErrorCode.DISCUSSION_006);
        }
        comment.restore();
        log.info("댓글 복구: commentId={}", commentId);
        return comment.getStatus();
    }

    /** 글 가림. 글을 가리면 그 아래 댓글도 함께 안 보인다(CommentService.list) — 댓글의 상태는 건드리지 않는다. */
    @Transactional
    public CommentStatus hidePost(Long postId) {
        Post post = requirePost(postId);
        if (post.isDeleted()) {
            throw new BusinessException(ErrorCode.DISCUSSION_006);
        }
        post.hide();
        int accepted = reportRepository.acceptPendingOfPost(
                postId, ReportStatus.PENDING, ReportStatus.ACCEPTED, LocalDateTime.now());
        log.info("글 가림: postId={} 닫힌 신고={}", postId, accepted);
        return post.getStatus();
    }

    @Transactional
    public CommentStatus restorePost(Long postId) {
        Post post = requirePost(postId);
        if (post.getStatus() != CommentStatus.HIDDEN) {
            throw new BusinessException(ErrorCode.DISCUSSION_006);
        }
        post.restore();
        log.info("글 복구: postId={}", postId);
        return post.getStatus();
    }

    /** 존재를 먼저 보고 상태를 본다 — 뒤집으면 없는 id에 "이미 처리됨"이 나간다. */
    @Transactional
    public CommentReportItem dismiss(Long reportId, String adminNote) {
        CommentReport report = reportRepository.findById(reportId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DISCUSSION_008));
        if (!report.isPending()) {
            throw new BusinessException(ErrorCode.DISCUSSION_009);
        }
        report.dismiss(Texts.trimToNull(adminNote));
        log.info("신고 기각: reportId={}", reportId);
        return toItem(report);
    }

    /* ── 내부 ─────────────────────────────────────────────── */

    /** 화면은 자기 글에 신고 버튼을 내지 않지만 주소는 직접 부를 수 있다. 내리고 싶으면 지우면 된다. */
    private void rejectOwn(Long userId, Long authorId) {
        if (userId.equals(authorId)) {
            throw new BusinessException(ErrorCode.DISCUSSION_012);
        }
    }

    /** 신고도 쓰기다. 정지된 사람이 남의 글을 신고로 괴롭히는 길을 남기지 않는다. */
    private void requireNotSuspended(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_003));
        SuspensionGuard.requireNotSuspended(user);
    }

    private Comment requireComment(Long commentId) {
        return commentRepository.findById(commentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DISCUSSION_001));
    }

    /** 지운 글도 돌려준다 — 신고함은 지운 글의 신고도 보여 줘야 한다. 지운 글을 막을지는 부르는 쪽이 정한다. */
    private Post requirePost(Long postId) {
        return postRepository.findById(postId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DISCUSSION_011));
    }

    /** 신고 한 줄에 글쓴이와 글·문제 제목을 붙인다. 한 쪽이 20건이라 건마다 읽어도 부담이 없다. */
    private CommentReportItem toItem(CommentReport report) {
        Comment comment = report.targetsPost() ? null : requireComment(report.getCommentId());
        Post post = requirePost(report.targetsPost() ? report.getPostId() : comment.getPostId());
        Long authorId = comment != null ? comment.getUserId() : post.getUserId();
        User author = authorId == null ? null : userRepository.findById(authorId).orElse(null);
        Long problemId = discussionRepository.findById(post.getDiscussionId())
                .map(Discussion::getProblemId).orElse(null);
        String problemTitle = problemId == null ? null
                : problemRepository.findById(problemId).map(Problem::getTitle).orElse(null);
        return comment != null
                ? CommentReportItem.ofComment(report, comment, post, author, problemId, problemTitle)
                : CommentReportItem.ofPost(report, post, author, problemId, problemTitle);
    }
}
