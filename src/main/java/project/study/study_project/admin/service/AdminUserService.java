package project.study.study_project.admin.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.admin.dto.AdminSuspendRequest;
import project.study.study_project.admin.dto.AdminUserActivity;
import project.study.study_project.admin.dto.AdminUserItem;
import project.study.study_project.discussion.domain.Comment;
import project.study.study_project.discussion.domain.CommentReport;
import project.study.study_project.discussion.domain.CommentStatus;
import project.study.study_project.discussion.domain.Post;
import project.study.study_project.discussion.repository.CommentReportRepository;
import project.study.study_project.discussion.repository.CommentRepository;
import project.study.study_project.discussion.repository.PostRepository;
import project.study.study_project.global.common.SearchKeyword;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.global.response.PageResponse;
import project.study.study_project.quiz.repository.SubmissionRepository;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 사용자 찾기, 활동 내역, 정지·해제(V26).
 *
 * <p>정지는 쓰기만 막는다. 무엇이 막히는지는 {@code SuspensionGuard}를 부르는 곳이 정한다 —
 * 여기는 "누구를 언제까지"만 적는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserService {

    /** 고를 수 있는 정지 일수. 화면의 선택지와 같다 — 아무 숫자나 받으면 기준이 관리자마다 달라진다. */
    private static final Set<Integer> ALLOWED_DAYS = Set.of(1, 7, 30);

    /** 활동 내역에 싣는 최근 신고 건수. 최근 글도 같은 수다(저장소 메서드 이름의 Top5). */
    private static final int RECENT = 5;
    private static final int EXCERPT_LENGTH = 80;

    private final UserRepository userRepository;
    private final PostRepository postRepository;
    private final CommentRepository commentRepository;
    private final CommentReportRepository reportRepository;
    private final SubmissionRepository submissionRepository;

    /**
     * @param q             아이디나 닉네임의 일부. 비우면 전체
     * @param suspendedOnly 지금 정지 중인 사람만
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminUserItem> search(String q, boolean suspendedOnly, Pageable pageable) {
        LocalDateTime now = LocalDateTime.now();
        String keyword = SearchKeyword.likePattern(q);
        return PageResponse.from(userRepository.search(keyword, suspendedOnly, now, pageable)
                .map(user -> AdminUserItem.of(user, now)));
    }

    @Transactional(readOnly = true)
    public AdminUserItem detail(Long userId) {
        return AdminUserItem.of(requireUser(userId), LocalDateTime.now());
    }

    /**
     * 정지할지 판단하는 근거. 수는 전부 세고, 목록은 최근 {@value #RECENT}건만 싣는다 —
     * 더 보려면 커뮤니티와 신고함에서 본다.
     */
    @Transactional(readOnly = true)
    public AdminUserActivity activity(Long userId) {
        requireUser(userId);

        List<AdminUserActivity.PostLine> recentPosts = postRepository
                .findTop5ByUserIdAndStatusNotOrderByCreatedAtDescIdDesc(userId, CommentStatus.DELETED).stream()
                .map(p -> new AdminUserActivity.PostLine(p.getId(), p.getCategory().getLabel(), p.getTitle(),
                        p.getStatus(), p.getCreatedAt()))
                .toList();

        return new AdminUserActivity(
                postRepository.countByUserIdAndStatusNot(userId, CommentStatus.DELETED),
                commentRepository.countByUserIdAndStatusNot(userId, CommentStatus.DELETED),
                postRepository.countByUserIdAndStatus(userId, CommentStatus.HIDDEN)
                        + commentRepository.countByUserIdAndStatus(userId, CommentStatus.HIDDEN),
                reportRepository.countReceivedBy(userId),
                submissionRepository.countAttemptedProblems(userId),
                submissionRepository.countSolvedProblems(userId),
                recentPosts,
                recentReportsOf(userId));
    }

    private List<AdminUserActivity.ReportLine> recentReportsOf(Long userId) {
        List<CommentReport> reports = reportRepository.findReceivedBy(userId, PageRequest.of(0, RECENT));
        // 댓글 신고는 그 댓글이 달린 글과 지금 본문을 알아야 한 줄을 만든다. 한 번에 읽는다.
        Map<Long, Comment> comments = new HashMap<>();
        commentRepository.findAllById(reports.stream().map(CommentReport::getCommentId)
                .filter(Objects::nonNull).toList()).forEach(c -> comments.put(c.getId(), c));
        Map<Long, Post> posts = new HashMap<>();
        postRepository.findAllById(reports.stream().map(CommentReport::getPostId)
                .filter(Objects::nonNull).toList()).forEach(p -> posts.put(p.getId(), p));

        return reports.stream().map(r -> {
            Comment comment = r.targetsPost() ? null : comments.get(r.getCommentId());
            Post post = r.targetsPost() ? posts.get(r.getPostId()) : null;
            // 스냅샷은 V27부터 남는다. 그 전의 신고는 지금 본문으로 대신한다.
            String body = r.getSnapshotBody() != null ? r.getSnapshotBody()
                    : comment != null ? comment.getBody() : post != null ? post.getBody() : "";
            return new AdminUserActivity.ReportLine(r.getId(), r.targetsPost() ? "POST" : "COMMENT",
                    r.targetsPost() ? r.getPostId() : comment != null ? comment.getPostId() : null,
                    r.getReason().getLabel(), r.getStatus(), excerpt(body), r.getCreatedAt());
        }).toList();
    }

    private String excerpt(String body) {
        String oneLine = body.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= EXCERPT_LENGTH ? oneLine : oneLine.substring(0, EXCERPT_LENGTH) + "…";
    }

    /** 이미 정지 중이면 새 기간과 사유로 덮어쓴다 — 7일을 30일로 늘릴 때 풀었다가 다시 걸게 하지 않는다. */
    @Transactional
    public AdminUserItem suspend(Long userId, AdminSuspendRequest request) {
        if (request.days() != null && !ALLOWED_DAYS.contains(request.days())) {
            throw new BusinessException(ErrorCode.COMMON_001, "정지 기간은 1일·7일·30일·무기한 가운데 하나입니다.");
        }
        User user = requireUser(userId);
        if (user.getRole() == Role.ADMIN) {
            throw new BusinessException(ErrorCode.USER_002);
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime until = request.days() == null ? User.INDEFINITE : now.plusDays(request.days());
        user.suspend(until, request.reason().trim());
        log.info("사용자 정지: userId={} days={}", userId, request.days());
        return AdminUserItem.of(user, now);
    }

    /** 정지가 아닌 사람을 풀어도 오류가 아니다 — 두 번 눌린 해제 버튼에 실패를 보여 줄 이유가 없다. */
    @Transactional
    public AdminUserItem unsuspend(Long userId) {
        User user = requireUser(userId);
        user.unsuspend();
        log.info("사용자 정지 해제: userId={}", userId);
        return AdminUserItem.of(user, LocalDateTime.now());
    }

    private User requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_001));
    }
}
