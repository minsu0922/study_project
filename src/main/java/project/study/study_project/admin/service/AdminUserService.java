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
import java.util.concurrent.ThreadLocalRandom;

/**
 * 사용자 찾기, 활동 내역, 정지·해제(V26), 닉네임 초기화.
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

    private static final String PLACEHOLDER_PREFIX = "사용자";
    private static final long PLACEHOLDER_MODULUS = 1_000_000_000L;
    private static final int PLACEHOLDER_TRIES = 5;

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

    /**
     * 부적절한 닉네임을 지운다. 비우지 않고 "사용자" + 번호로 바꾼다 — 닉네임이 비면 그 사람이 쓴
     * 글과 댓글이 모두 "탈퇴한 사용자"로 보인다. 본인은 마이페이지에서 새 닉네임을 정할 수 있다.
     *
     * <p>닉네임이 없는 계정(닉네임이 필수가 되기 전에 가입)은 지울 것이 없어 그대로 둔다.
     */
    @Transactional
    public AdminUserItem resetNickname(Long userId) {
        User user = requireUser(userId);
        if (user.getNickname() != null) {
            user.changeNickname(placeholderNickname(user));
            log.info("닉네임 초기화: userId={}", userId);
        }
        return AdminUserItem.of(user, LocalDateTime.now());
    }

    /** 번호는 사용자 id다. 누가 그 이름을 먼저 골라 썼으면 임의의 수로 바꿔 몇 번 더 찾는다. */
    private String placeholderNickname(User user) {
        // 닉네임은 12자까지다. "사용자"(3자) 뒤에 9자리까지 붙는다.
        String candidate = PLACEHOLDER_PREFIX + (user.getId() % PLACEHOLDER_MODULUS);
        for (int i = 0; i < PLACEHOLDER_TRIES; i++) {
            if (!userRepository.existsByNicknameAndIdNot(candidate, user.getId())) {
                return candidate;
            }
            candidate = PLACEHOLDER_PREFIX + ThreadLocalRandom.current().nextLong(PLACEHOLDER_MODULUS);
        }
        throw new BusinessException(ErrorCode.COMMON_001, "바꿔 줄 닉네임을 정하지 못했습니다. 다시 눌러 주세요.");
    }

    private User requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_001));
    }
}
