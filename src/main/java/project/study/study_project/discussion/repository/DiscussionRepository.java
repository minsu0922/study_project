package project.study.study_project.discussion.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import project.study.study_project.discussion.domain.Discussion;

import java.util.Optional;

public interface DiscussionRepository extends JpaRepository<Discussion, Long> {

    /**
     * 그 문제의 방이 없으면 만든다. 있으면 아무 일도 하지 않는다.
     *
     * <p>"조회 → 없으면 저장"으로 쓰면 첫 댓글 둘이 동시에 올 때 한쪽이 유일 제약에 걸려 500이 난다.
     * {@code INSERT IGNORE}는 그 충돌을 DB 안에서 끝낸다.
     */
    @Modifying
    @Query(value = "insert ignore into discussion (problem_id, created_at) values (:problemId, now(6))",
            nativeQuery = true)
    void insertIfAbsent(@Param("problemId") Long problemId);

    /**
     * 방 id를 <b>잠금 읽기</b>로 가져온다 — {@link #insertIfAbsent} 바로 뒤에 쓴다.
     *
     * <p>일반 조회는 트랜잭션이 처음 읽은 시점의 스냅샷을 본다(REPEATABLE READ). 다른 트랜잭션이
     * 방금 커밋한 방이 안 보여 "만들었는데 없다"가 된다. {@code FOR SHARE}는 최신 커밋을 읽는다.
     */
    @Query(value = "select id from discussion where problem_id = :problemId for share", nativeQuery = true)
    Optional<Long> findIdByProblemIdForShare(@Param("problemId") Long problemId);

    @Query("select d.id from Discussion d where d.problemId = :problemId")
    Optional<Long> findIdByProblemId(@Param("problemId") Long problemId);

    long countByProblemId(Long problemId);
}
