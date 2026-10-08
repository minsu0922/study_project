package project.study.study_project.bookmark.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import project.study.study_project.bookmark.domain.Bookmark;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface BookmarkRepository extends JpaRepository<Bookmark, Long> {

    /**
     * 담기. 이미 담겨 있으면 아무 일도 하지 않는다.
     *
     * <p>"있나 보고 없으면 넣기"로 하지 않는 이유: 버튼을 연달아 두 번 누르면 둘 다 "없다"를 보고
     * 넣다가 한쪽이 UNIQUE 위반으로 500이 된다. 판정을 DB에 맡기면 그 틈이 없다.
     */
    @Modifying
    @Query(value = "INSERT IGNORE INTO bookmark (user_id, problem_id, created_at) "
            + "VALUES (:userId, :problemId, :now)", nativeQuery = true)
    void addProblem(@Param("userId") Long userId, @Param("problemId") Long problemId,
                    @Param("now") LocalDateTime now);

    @Modifying
    @Query(value = "INSERT IGNORE INTO bookmark (user_id, document_id, created_at) "
            + "VALUES (:userId, :documentId, :now)", nativeQuery = true)
    void addDocument(@Param("userId") Long userId, @Param("documentId") Long documentId,
                     @Param("now") LocalDateTime now);

    @Modifying
    @Query("delete from Bookmark b where b.userId = :userId and b.problem.id = :problemId")
    void removeProblem(@Param("userId") Long userId, @Param("problemId") Long problemId);

    @Modifying
    @Query("delete from Bookmark b where b.userId = :userId and b.document.id = :documentId")
    void removeDocument(@Param("userId") Long userId, @Param("documentId") Long documentId);

    /** 내려 둔 문제(V30)는 목록에서 뺀다 — 눌러도 풀 수 없다. 다시 올라오면 북마크도 돌아온다. */
    @Query(value = """
            select b from Bookmark b
            join fetch b.problem p
            where b.userId = :userId and p.hidden = false
            order by b.id desc
            """,
            countQuery = """
            select count(b) from Bookmark b
            where b.userId = :userId and b.problem is not null and b.problem.hidden = false
            """)
    Page<Bookmark> findProblems(@Param("userId") Long userId, Pageable pageable);

    @Query(value = """
            select b from Bookmark b
            join fetch b.document d
            where b.userId = :userId and d.hidden = false
            order by b.id desc
            """,
            countQuery = """
            select count(b) from Bookmark b
            where b.userId = :userId and b.document is not null and b.document.hidden = false
            """)
    Page<Bookmark> findDocuments(@Param("userId") Long userId, Pageable pageable);

    /** 넘긴 문제 가운데 내가 담아 둔 것 — 화면이 버튼을 채워 그릴지 정하는 데 쓴다. */
    @Query("select b.problem.id from Bookmark b where b.userId = :userId and b.problem.id in :ids")
    List<Long> findBookmarkedProblemIds(@Param("userId") Long userId, @Param("ids") Collection<Long> ids);

    @Query("select b.document.id from Bookmark b where b.userId = :userId and b.document.id in :ids")
    List<Long> findBookmarkedDocumentIds(@Param("userId") Long userId, @Param("ids") Collection<Long> ids);
}
