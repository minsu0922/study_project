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
import project.study.study_project.discussion.dto.CommentReportItem;
import project.study.study_project.discussion.dto.CommentReportRequest;
import project.study.study_project.discussion.repository.CommentReportRepository;
import project.study.study_project.discussion.repository.CommentRepository;
import project.study.study_project.discussion.repository.DiscussionRepository;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.global.response.PageResponse;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.report.domain.ReportStatus;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;

import java.time.LocalDateTime;

/**
 * 댓글 신고 — 접수(학습자)와 판정(관리자).
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
    private final DiscussionRepository discussionRepository;
    private final ProblemRepository problemRepository;
    private final UserRepository userRepository;

    /* ── 학습자 ───────────────────────────────────────────── */

    /** 중복은 두 겹으로 막는다 — 미리 세어 안내하고, 끼어든 요청은 유일 제약이 막는다(문제 제보와 같은 방식). */
    @Transactional
    public CommentReportItem report(Long userId, CommentReportRequest request) {
        Comment comment = requireComment(request.commentId());
        if (!comment.isVisible()) {
            throw new BusinessException(ErrorCode.DISCUSSION_006);
        }
        if (reportRepository.existsByCommentIdAndUserId(comment.getId(), userId)) {
            throw new BusinessException(ErrorCode.DISCUSSION_007);
        }
        String detail = (request.detail() == null || request.detail().isBlank()) ? null : request.detail().trim();
        try {
            CommentReport saved = reportRepository.saveAndFlush(
                    CommentReport.of(comment.getId(), userId, request.reason(), detail));
            log.info("댓글 신고 접수: commentId={} reason={}", comment.getId(), request.reason());
            return toItem(saved, comment);
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
        return PageResponse.from(page.map(r -> toItem(r, requireComment(r.getCommentId()))));
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

    /** 존재를 먼저 보고 상태를 본다 — 뒤집으면 없는 id에 "이미 처리됨"이 나간다. */
    @Transactional
    public CommentReportItem dismiss(Long reportId, String adminNote) {
        CommentReport report = reportRepository.findById(reportId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DISCUSSION_008));
        if (!report.isPending()) {
            throw new BusinessException(ErrorCode.DISCUSSION_009);
        }
        report.dismiss((adminNote == null || adminNote.isBlank()) ? null : adminNote.trim());
        log.info("댓글 신고 기각: reportId={}", reportId);
        return toItem(report, requireComment(report.getCommentId()));
    }

    /* ── 내부 ─────────────────────────────────────────────── */

    private Comment requireComment(Long commentId) {
        return commentRepository.findById(commentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DISCUSSION_001));
    }

    /** 신고 한 줄에 글쓴이 닉네임과 문제 제목을 붙인다. 한 쪽이 20건이라 건마다 읽어도 부담이 없다. */
    private CommentReportItem toItem(CommentReport report, Comment comment) {
        String nickname = comment.getUserId() == null ? null
                : userRepository.findById(comment.getUserId()).map(User::getNickname).orElse(null);
        Long problemId = discussionRepository.findById(comment.getDiscussionId())
                .map(Discussion::getProblemId).orElse(null);
        String problemTitle = problemId == null ? null
                : problemRepository.findById(problemId).map(Problem::getTitle).orElse(null);
        return CommentReportItem.of(report, comment, nickname, problemId, problemTitle);
    }
}
