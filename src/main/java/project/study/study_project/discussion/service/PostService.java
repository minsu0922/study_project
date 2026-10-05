package project.study.study_project.discussion.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.discussion.domain.CommentStatus;
import project.study.study_project.discussion.domain.Discussion;
import project.study.study_project.discussion.domain.Post;
import project.study.study_project.discussion.dto.PostDetail;
import project.study.study_project.discussion.dto.PostEditRequest;
import project.study.study_project.discussion.dto.PostListResponse;
import project.study.study_project.discussion.dto.PostSort;
import project.study.study_project.discussion.dto.PostSummary;
import project.study.study_project.discussion.dto.PostWriteRequest;
import project.study.study_project.discussion.dto.RecentPostItem;
import project.study.study_project.discussion.dto.RecentPostResponse;
import project.study.study_project.discussion.repository.CommentRepository;
import project.study.study_project.discussion.repository.DiscussionRepository;
import project.study.study_project.discussion.repository.PostRepository;
import project.study.study_project.global.common.DomainCode;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.quiz.repository.SubmissionRepository;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;
import project.study.study_project.user.support.SuspensionGuard;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 토론방 게시글 — 목록, 상세, 쓰기, 수정, 삭제.
 *
 * <p>읽기는 누구에게나 열려 있고 쓰기는 그 문제를 푼 사람만 한다({@link CommentService}와 같은 규칙).
 * 토론방은 미리 만들어 두지 않는다 — 첫 글이 쓰일 때 생긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostService {

    private static final int PAGE_SIZE = 20;
    private static final int MAX_QUERY_LENGTH = 50;

    private final PostRepository postRepository;
    private final CommentRepository commentRepository;
    private final DiscussionRepository discussionRepository;
    private final ProblemRepository problemRepository;
    private final SubmissionRepository submissionRepository;
    private final UserRepository userRepository;

    /**
     * @param viewerId 보는 사람. 비로그인이면 {@code null}
     */
    @Transactional(readOnly = true)
    public PostListResponse list(Long problemId, Long viewerId, int page) {
        requireProblem(problemId);
        User viewer = viewerId == null ? null : userRepository.findById(viewerId).orElse(null);
        boolean solved = viewer != null && submissionRepository.existsByUserIdAndProblem_Id(viewerId, problemId);
        // 정지 중이면 쓸 수 없다고 미리 알린다 — 화면이 글쓰기 버튼을 내지 않는다.
        boolean canWrite = (solved || (viewer != null && viewer.getRole() == Role.ADMIN))
                && !viewer.isSuspended(LocalDateTime.now());

        Optional<Long> discussionId = discussionRepository.findIdByProblemId(problemId);
        if (discussionId.isEmpty()) {
            return new PostListResponse(solved, canWrite, 0, false, SuspensionGuard.noticeFor(viewer), List.of());
        }

        Slice<Post> posts = postRepository.findByDiscussionIdAndStatusNotOrderByCreatedAtDescIdDesc(
                discussionId.get(), CommentStatus.DELETED, PageRequest.of(Math.max(page, 0), PAGE_SIZE));
        Map<Long, String> nicknames = nicknamesOf(posts.getContent());
        Map<Long, Long> commentCounts = commentCountsOf(posts.getContent());
        List<PostSummary> items = posts.getContent().stream()
                .map(p -> PostSummary.of(p, nicknames.get(p.getUserId()),
                        commentCounts.getOrDefault(p.getId(), 0L)))
                .toList();

        long total = postRepository.countByDiscussionIdAndStatus(discussionId.get(), CommentStatus.VISIBLE);
        return new PostListResponse(solved, canWrite, total, posts.hasNext(), SuspensionGuard.noticeFor(viewer), items);
    }

    /**
     * 모든 토론방의 글 — 커뮤니티 화면이 읽는다. 지우거나 가린 글은 넣지 않는다.
     *
     * @param q      제목·본문에서 찾을 말. 비어 있으면 안 거른다
     * @param domain 그 글이 속한 문제의 분야. {@code null}이면 전체
     */
    @Transactional(readOnly = true)
    public RecentPostResponse recent(String q, PostSort sort, DomainCode domain, int page) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), PAGE_SIZE);
        String pattern = likePattern(q);
        Slice<PostRepository.RecentPostRow> rows = sort == PostSort.COMMENTS
                ? postRepository.findMostCommented(CommentStatus.VISIBLE, pattern, domain, pageable)
                : postRepository.findRecent(CommentStatus.VISIBLE, pattern, domain, pageable);
        List<Post> posts = rows.getContent().stream().map(PostRepository.RecentPostRow::getPost).toList();
        Map<Long, String> nicknames = nicknamesOf(posts);
        Map<Long, Long> commentCounts = commentCountsOf(posts);
        List<RecentPostItem> items = rows.getContent().stream()
                .map(row -> RecentPostItem.of(row.getPost(),
                        nicknames.get(row.getPost().getUserId()),
                        commentCounts.getOrDefault(row.getPost().getId(), 0L),
                        row.getProblemId(), row.getProblemTitle(), row.getDomain().value()))
                .toList();
        return new RecentPostResponse(rows.hasNext(), items);
    }

    /**
     * 검색어를 LIKE 패턴으로 바꾼다. 비어 있으면 {@code null}(조건을 걸지 않는다).
     *
     * <p>사용자가 친 %와 _는 글자 그대로 찾는다. 그대로 넘기면 "%" 한 글자로 모든 글이 나오고
     * "_"는 아무 글자 하나에 맞는다. 이스케이프 글자는 '!'다(PostRepository의 escape와 짝).
     * 길이는 50자에서 자른다 — 그보다 긴 검색어는 찾으려는 말이 아니라 붙여 넣은 글이다.
     */
    private String likePattern(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        String word = q.trim();
        if (word.length() > MAX_QUERY_LENGTH) {
            word = word.substring(0, MAX_QUERY_LENGTH);
        }
        return "%" + word.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    /** 지운 글은 없는 글로 답한다. 가린 글은 자리만 돌려준다 — 링크로 들어온 사람에게 이유를 보여 준다. */
    @Transactional(readOnly = true)
    public PostDetail detail(Long postId, Long viewerId) {
        Post post = requireAlive(postId);
        String nickname = post.getUserId() == null
                ? null
                : userRepository.findById(post.getUserId()).map(User::getNickname).orElse(null);
        return PostDetail.of(post, problemIdOf(post), nickname, viewerId);
    }

    @Transactional
    public PostDetail write(Long userId, PostWriteRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_003));
        SuspensionGuard.requireNotSuspended(user);
        Long problemId = request.problemId();
        requireProblem(problemId);
        if (user.getRole() != Role.ADMIN
                && !submissionRepository.existsByUserIdAndProblem_Id(userId, problemId)) {
            throw new BusinessException(ErrorCode.DISCUSSION_002);
        }
        if (user.getNickname() == null) {
            throw new BusinessException(ErrorCode.DISCUSSION_003);
        }

        // 방은 첫 글 때 만든다. 두 문장으로 나눈 이유는 DiscussionRepository 주석에 있다.
        discussionRepository.insertIfAbsent(problemId);
        Long discussionId = discussionRepository.findIdByProblemIdForShare(problemId)
                .orElseThrow(() -> new BusinessException(ErrorCode.QUIZ_001));

        Post saved = postRepository.save(
                Post.of(discussionId, userId, request.title().trim(), request.body().trim()));
        log.info("글 작성: problemId={} postId={}", problemId, saved.getId());
        return PostDetail.of(saved, problemId, user.getNickname(), userId);
    }

    @Transactional
    public PostDetail edit(Long userId, Long postId, PostEditRequest request) {
        Post post = requireOwn(userId, requireAlive(postId));
        if (!post.isVisible()) {
            throw new BusinessException(ErrorCode.DISCUSSION_006);
        }
        // 수정도 막는다. 정지 중에 이미 올린 글의 내용을 바꿔 치울 수 있으면 정지가 뜻이 없다.
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_003));
        SuspensionGuard.requireNotSuspended(user);
        post.edit(request.title().trim(), request.body().trim());
        return PostDetail.of(post, problemIdOf(post), user.getNickname(), userId);
    }

    /** 이미 지운 글을 또 지워도 오류가 아니다 — 두 번 눌린 삭제 버튼에 실패를 보여 줄 이유가 없다. */
    @Transactional
    public void delete(Long userId, Long postId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DISCUSSION_011));
        requireOwn(userId, post).delete();
    }

    /** 문제별 보이는 글 수. 글이 없는 문제는 맵에 없다. */
    @Transactional(readOnly = true)
    public Map<Long, Long> counts(Collection<Long> problemIds) {
        Map<Long, Long> counts = new HashMap<>();
        if (problemIds == null || problemIds.isEmpty()) {
            return counts;
        }
        postRepository.countByProblemIds(problemIds, CommentStatus.VISIBLE)
                .forEach(row -> counts.put(row.getProblemId(), row.getCnt()));
        return counts;
    }

    /** 글 id → 보이는 댓글 수. 한 쪽의 글을 한 번에 센다. */
    private Map<Long, Long> commentCountsOf(List<Post> posts) {
        Map<Long, Long> counts = new HashMap<>();
        if (posts.isEmpty()) {
            return counts;
        }
        commentRepository.countByPostIds(posts.stream().map(Post::getId).toList(), CommentStatus.VISIBLE)
                .forEach(row -> counts.put(row.getPostId(), row.getCnt()));
        return counts;
    }

    private void requireProblem(Long problemId) {
        if (!problemRepository.existsById(problemId)) {
            throw new BusinessException(ErrorCode.QUIZ_001);
        }
    }

    private Post requireAlive(Long postId) {
        return postRepository.findById(postId)
                .filter(p -> !p.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.DISCUSSION_011));
    }

    /** 존재를 먼저 보고 주인을 본다 — 뒤집으면 없는 id에 "권한 없음"이 나간다. */
    private Post requireOwn(Long userId, Post post) {
        if (!userId.equals(post.getUserId())) {
            throw new BusinessException(ErrorCode.DISCUSSION_005);
        }
        return post;
    }

    private Long problemIdOf(Post post) {
        return discussionRepository.findById(post.getDiscussionId())
                .map(Discussion::getProblemId)
                .orElse(null);
    }

    /** 글쓴이 id → 닉네임. 탈퇴한 사용자는 id가 null이라 맵에 없다. */
    private Map<Long, String> nicknamesOf(List<Post> posts) {
        List<Long> userIds = posts.stream().map(Post::getUserId).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> nicknames = new HashMap<>();
        userRepository.findAllById(userIds).forEach(u -> nicknames.put(u.getId(), u.getNickname()));
        return nicknames;
    }
}
