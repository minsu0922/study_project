package project.study.study_project.learningpath.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 분야별 코스에서 문서 한 편의 자리(V44). 순번이 없는 문서는 기본 규칙으로 뒤에 붙는다. */
@Entity
@Table(name = "domain_course_order")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DomainCourseOrder {

    @Id
    @Column(name = "document_id")
    private Long documentId;

    @Column(nullable = false)
    private int seq;

    public DomainCourseOrder(Long documentId, int seq) {
        this.documentId = documentId;
        this.seq = seq;
    }
}
