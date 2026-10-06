package project.study.study_project.discussion.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import project.study.study_project.discussion.domain.CommentReport;
import project.study.study_project.report.domain.ReportStatus;

import java.time.LocalDateTime;
import java.util.List;

public interface CommentReportRepository extends JpaRepository<CommentReport, Long> {

    boolean existsByCommentIdAndUserId(Long commentId, Long userId);

    boolean existsByPostIdAndUserId(Long postId, Long userId);

    long countByStatus(ReportStatus status);

    /**
     * 한 사람의 글과 댓글이 받은 신고. 신고에는 글쓴이가 적혀 있지 않아 대상을 거쳐 찾는다 —
     * 글쓴이를 신고에 따로 적어 두면 대상이 지워지거나 탈퇴로 주인이 비었을 때 두 값이 어긋난다.
     */
    String RECEIVED_BY = """
            from CommentReport r
            where r.postId in (select p.id from Post p where p.userId = :userId)
               or r.commentId in (select c.id from Comment c where c.userId = :userId)
            """;

    @Query("select count(r) " + RECEIVED_BY)
    long countReceivedBy(@Param("userId") Long userId);

    @Query("select r " + RECEIVED_BY + " order by r.createdAt desc, r.id desc")
    List<CommentReport> findReceivedBy(@Param("userId") Long userId, Pageable pageable);

    /** 대기 목록 — 오래 기다린 것부터. 방치된 신고가 맨 위에 온다(문제 제보함과 같은 규칙). */
    @Query("select r from CommentReport r where (:status is null or r.status = :status) order by r.createdAt asc")
    Page<CommentReport> findOldestFirst(@Param("status") ReportStatus status, Pageable pageable);

    /** 처리된 목록 — 최근 것부터. */
    @Query("select r from CommentReport r where (:status is null or r.status = :status) order by r.createdAt desc")
    Page<CommentReport> findNewestFirst(@Param("status") ReportStatus status, Pageable pageable);

    /**
     * 글을 가릴 때 그 글의 대기 신고를 한 번에 인정으로 바꾼다.
     *
     * <p>벌크 연산은 영속성 컨텍스트를 건너뛴다. 같은 트랜잭션에서 이 신고들을 이미 읽어 둔 코드가
     * 있으면 옛 상태가 보이므로, 부르는 쪽은 이 뒤에 신고를 다시 읽지 않는다.
     */
    @Modifying
    @Query("""
            update CommentReport r set r.status = :accepted, r.resolvedAt = :now
            where r.commentId = :commentId and r.status = :pending
            """)
    int acceptPendingOf(@Param("commentId") Long commentId,
                        @Param("pending") ReportStatus pending,
                        @Param("accepted") ReportStatus accepted,
                        @Param("now") LocalDateTime now);

    /** 글 신고용. 뜻과 주의할 점은 {@link #acceptPendingOf}와 같다. */
    @Modifying
    @Query("""
            update CommentReport r set r.status = :accepted, r.resolvedAt = :now
            where r.postId = :postId and r.status = :pending
            """)
    int acceptPendingOfPost(@Param("postId") Long postId,
                            @Param("pending") ReportStatus pending,
                            @Param("accepted") ReportStatus accepted,
                            @Param("now") LocalDateTime now);
}
