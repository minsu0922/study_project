package project.study.study_project.learningpath.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import project.study.study_project.document.domain.Document;

/** 코스의 한 단계 = 개념 문서 한 편(그 문서로 만든 문제가 이 단계의 문제다). */
@Entity
@Table(name = "learning_path_step")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LearningPathStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "path_id")
    private LearningPath path;

    @Column(nullable = false)
    private int seq;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id")
    private Document document;

    LearningPathStep(LearningPath path, int seq, Document document) {
        this.path = path;
        this.seq = seq;
        this.document = document;
    }
}
