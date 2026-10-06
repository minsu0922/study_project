package project.study.study_project.discussion.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import project.study.study_project.discussion.domain.CommentStatus;
import project.study.study_project.discussion.domain.Post;
import project.study.study_project.discussion.domain.PostCategory;
import project.study.study_project.global.common.DomainCode;
import project.study.study_project.user.domain.Role;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface PostRepository extends JpaRepository<Post, Long> {

    /**
     * 한 방의 글을 새 글부터. 지운 글은 빼고 가린 글은 넣는다 — 가린 글은 자리를 남겨 보여 준다.
     *
     * <p>{@code Slice}인 이유: 전체 건수를 세는 쿼리가 따로 나가지 않는다. 화면에 쓰는 건수는
     * 보이는 글만 세므로({@link #countByDiscussionIdAndStatus}) 어차피 다른 쿼리다.
     */
    Slice<Post> findByDiscussionIdAndStatusNotOrderByCreatedAtDescIdDesc(
            Long discussionId, CommentStatus status, Pageable pageable);

    long countByDiscussionIdAndStatus(Long discussionId, CommentStatus status);

    /**
     * 커뮤니티 목록의 공통 부분 — 방을 가리지 않고 보이는 글을 고른다. 문제 id·제목·분야를 같이
     * 읽는다. 글마다 따로 읽으면 한 쪽에 조회가 60번 나간다.
     *
     * <p>조건은 비어 있으면({@code null}) 걸지 않는다. 탭마다 쿼리를 따로 두지
     * 않고 조건을 얹는 이유: "내 활동"도 고르는 대상과 한 줄의 모양이 커뮤니티 목록과 같다.
     * 따로 두면 "보이는 글"의 뜻을 고칠 때 여러 군데를 고쳐야 한다.
     *
     * <p>검색어는 부르는 쪽이 앞뒤에 %를 붙이고
     * 와일드카드를 '!'로 이스케이프해서 넘긴다(PostService.likePattern) — 여기서 붙이면 사용자가
     * 친 %와 우리가 붙인 %를 가를 수 없다.
     *
     * <p>본문 LIKE는 인덱스를 타지 않는다. 글이 수천 건일 때까지는 그대로 두고, 느려지면
     * 전문 검색 인덱스로 바꾼다.
     */
    String RECENT_FROM_WHERE = """
            select p as post, d.problemId as problemId, pr.title as problemTitle, pr.domain as domain
            from Post p
              join Discussion d on d.id = p.discussionId
              join Problem pr on pr.id = d.problemId
            where p.status = :visible
              and (:q is null or p.title like :q escape '!' or p.body like :q escape '!')
              and (:domain is null or pr.domain = :domain)
              and (:category is null or p.category = :category)
              and (:authorId is null or p.userId = :authorId)
              and (:commenterId is null
                   or exists (select 1 from Comment c
                              where c.postId = p.id and c.userId = :commenterId and c.status = :visible))
            """;

    /** 새 글부터(검색어·분야가 없으면 V24 인덱스를 탄다). */
    @Query(RECENT_FROM_WHERE + "order by p.createdAt desc, p.id desc")
    Slice<RecentPostRow> findRecent(@Param("visible") CommentStatus visible,
                                    @Param("q") String q,
                                    @Param("domain") DomainCode domain,
                                    @Param("category") PostCategory category,
                                    @Param("authorId") Long authorId,
                                    @Param("commenterId") Long commenterId,
                                    Pageable pageable);

    /** 보이는 댓글이 많은 글부터. 수가 같으면 새 글이 먼저다 — 순서가 요청마다 흔들리지 않게 한다. */
    @Query(RECENT_FROM_WHERE + """
            order by (select count(c) from Comment c where c.postId = p.id and c.status = :visible) desc,
                     p.createdAt desc, p.id desc
            """)
    Slice<RecentPostRow> findMostCommented(@Param("visible") CommentStatus visible,
                                           @Param("q") String q,
                                           @Param("domain") DomainCode domain,
                                           @Param("category") PostCategory category,
                                           @Param("authorId") Long authorId,
                                           @Param("commenterId") Long commenterId,
                                           Pageable pageable);

    /**
     * 토론방 목록 — 보이는 글이 하나라도 있는 문제를, 최근 글이 달린 방부터.
     *
     * <p>방(discussion) 표가 아니라 글에서 출발한다. 글을 다 지운 방은 표에는 남아 있지만
     * 들어가도 볼 것이 없다 — 그런 방을 목록에서 빼려면 "보이는 글이 있는가"로 세어야 한다.
     */
    @Query("""
            select d.problemId as problemId, pr.title as problemTitle, pr.domain as domain,
                   count(p) as postCount, max(p.createdAt) as lastPostAt
            from Post p
              join Discussion d on d.id = p.discussionId
              join Problem pr on pr.id = d.problemId
            where p.status = :visible
              and (:domain is null or pr.domain = :domain)
            group by d.problemId, pr.title, pr.domain
            order by max(p.createdAt) desc, d.problemId desc
            """)
    Slice<RoomRow> findRooms(@Param("visible") CommentStatus visible,
                             @Param("domain") DomainCode domain,
                             Pageable pageable);

    /**
     * "오류 지적" 글 — 관리 콘솔의 제보 화면이 읽는다. 문제가 틀렸다는 지적이라 관리자가 봐야 한다.
     *
     * <p>처리했는지를 따로 적어 두지 않고 운영진의 댓글이 있는지로 가른다. 운영진의 답은 글쓴이도
     * 봐야 하는 것이라 댓글이 곧 처리 기록이다. {@code unansweredOnly}가 참이면 그 댓글이 없는 글만 고른다.
     */
    @Query(value = """
            select p as post, d.problemId as problemId, pr.title as problemTitle, pr.domain as domain,
                   (select count(c) from Comment c, User u
                     where c.postId = p.id and u.id = c.userId
                       and u.role = :adminRole and c.status = :visible) as staffReplyCount
            from Post p
              join Discussion d on d.id = p.discussionId
              join Problem pr on pr.id = d.problemId
            where p.status = :visible and p.category = :errata
              and (:unansweredOnly = false
                   or not exists (select 1 from Comment c2, User u2
                                   where c2.postId = p.id and u2.id = c2.userId
                                     and u2.role = :adminRole and c2.status = :visible))
            order by p.createdAt desc, p.id desc
            """,
            countQuery = """
            select count(p) from Post p
            where p.status = :visible and p.category = :errata
              and (:unansweredOnly = false
                   or not exists (select 1 from Comment c2, User u2
                                   where c2.postId = p.id and u2.id = c2.userId
                                     and u2.role = :adminRole and c2.status = :visible))
            """)
    Page<ErrataRow> findErrata(@Param("visible") CommentStatus visible,
                               @Param("errata") PostCategory errata,
                               @Param("adminRole") Role adminRole,
                               @Param("unansweredOnly") boolean unansweredOnly,
                               Pageable pageable);

    interface ErrataRow {
        Post getPost();

        Long getProblemId();

        String getProblemTitle();

        DomainCode getDomain();

        long getStaffReplyCount();
    }

    interface RoomRow {
        Long getProblemId();

        String getProblemTitle();

        DomainCode getDomain();

        long getPostCount();

        LocalDateTime getLastPostAt();
    }

    interface RecentPostRow {
        Post getPost();

        Long getProblemId();

        String getProblemTitle();

        DomainCode getDomain();
    }

    /** 문제별 보이는 글 수 — 문제 목록이 한 쪽(20건)의 수를 한 번에 묻는다. 글이 없는 문제는 결과에 없다. */
    @Query("""
            select d.problemId as problemId, count(p) as cnt
            from Post p join Discussion d on d.id = p.discussionId
            where d.problemId in :problemIds and p.status = :visible
            group by d.problemId
            """)
    List<ProblemPostCount> countByProblemIds(@Param("problemIds") Collection<Long> problemIds,
                                             @Param("visible") CommentStatus visible);

    interface ProblemPostCount {
        Long getProblemId();

        long getCnt();
    }
}
