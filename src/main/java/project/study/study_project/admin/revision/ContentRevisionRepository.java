package project.study.study_project.admin.revision;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ContentRevisionRepository extends JpaRepository<ContentRevision, Long> {

    List<ContentRevision> findByTargetTypeAndTargetIdOrderByIdDesc(RevisionTarget targetType, Long targetId,
                                                                   Pageable pageable);

    /** 종류와 대상을 함께 건다 — id만으로 찾으면 다른 문제의 옛 모습을 이 문제에 덮어쓸 수 있다. */
    Optional<ContentRevision> findByIdAndTargetTypeAndTargetId(Long id, RevisionTarget targetType, Long targetId);

    @Modifying
    @Query("delete from ContentRevision r where r.targetType = :targetType and r.targetId = :targetId")
    void deleteAllOf(@Param("targetType") RevisionTarget targetType, @Param("targetId") Long targetId);
}
