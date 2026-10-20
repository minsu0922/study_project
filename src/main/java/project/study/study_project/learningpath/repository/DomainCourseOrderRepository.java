package project.study.study_project.learningpath.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import project.study.study_project.global.common.DomainCode;
import project.study.study_project.learningpath.domain.DomainCourseOrder;

public interface DomainCourseOrderRepository extends JpaRepository<DomainCourseOrder, Long> {

    /** 한 분야의 순번을 통째로 지운다 — 저장은 "지우고 다시 넣기"라 순번이 겹칠 틈이 없다 */
    @Modifying
    @Query("delete from DomainCourseOrder o where o.documentId in "
            + "(select d.id from Document d where d.domain = :domain)")
    void deleteByDomain(@Param("domain") DomainCode domain);
}
