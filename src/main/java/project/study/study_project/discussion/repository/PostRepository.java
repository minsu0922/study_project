package project.study.study_project.discussion.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import project.study.study_project.discussion.domain.CommentStatus;
import project.study.study_project.discussion.domain.Post;
import project.study.study_project.global.common.DomainCode;

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
     * <p>조건은 비어 있으면({@code null}) 걸지 않는다. 검색어는 부르는 쪽이 앞뒤에 %를 붙이고
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
            """;

    /** 새 글부터(검색어·분야가 없으면 V24 인덱스를 탄다). */
    @Query(RECENT_FROM_WHERE + "order by p.createdAt desc, p.id desc")
    Slice<RecentPostRow> findRecent(@Param("visible") CommentStatus visible,
                                    @Param("q") String q,
                                    @Param("domain") DomainCode domain,
                                    Pageable pageable);

    /** 보이는 댓글이 많은 글부터. 수가 같으면 새 글이 먼저다 — 순서가 요청마다 흔들리지 않게 한다. */
    @Query(RECENT_FROM_WHERE + """
            order by (select count(c) from Comment c where c.postId = p.id and c.status = :visible) desc,
                     p.createdAt desc, p.id desc
            """)
    Slice<RecentPostRow> findMostCommented(@Param("visible") CommentStatus visible,
                                           @Param("q") String q,
                                           @Param("domain") DomainCode domain,
                                           Pageable pageable);

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
