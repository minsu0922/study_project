package project.study.study_project.learningpath.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import project.study.study_project.document.domain.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 관리자가 고른 학습 코스(V43). 분야별·자바 코드 코스는 저장하지 않고 규칙으로 만든다(docs/26).
 */
@Entity
@Table(name = "learning_path")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LearningPath {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean published;

    /** 단계는 코스와 생사를 같이한다. 순서 바꾸기는 {@link #replaceSteps}로 통째로 다시 쓴다 */
    @OneToMany(mappedBy = "path", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("seq asc")
    private List<LearningPathStep> steps = new ArrayList<>();

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    private LearningPath(String title, String description, int sortOrder) {
        this.title = title;
        this.description = description;
        this.sortOrder = sortOrder;
    }

    /** 새 코스는 비공개로 시작한다. 단계를 다 넣은 뒤 공개한다 */
    public static LearningPath create(String title, String description, int sortOrder) {
        return new LearningPath(title, description, sortOrder);
    }

    public void update(String title, String description, int sortOrder) {
        this.title = title;
        this.description = description;
        this.sortOrder = sortOrder;
    }

    public void publish() {
        this.published = true;
    }

    public void unpublish() {
        this.published = false;
    }

    /**
     * 단계를 주어진 문서 순서로 다시 쓴다. seq는 1부터 다시 매긴다.
     *
     * <p>한 줄씩 seq를 옮기지 않는 이유: 두 단계를 맞바꾸는 동안 잠깐 같은 seq가 둘이 되고,
     * 중간에 실패하면 순서가 반쯤 바뀐 코스가 남는다. 단계는 많아야 수십 개라 통째로 써도 싸다.
     * 비운 뒤 flush 없이 다시 넣으면 (path_id, document_id) UNIQUE에 걸리므로 부르는 쪽이
     * 비우기와 채우기 사이에 flush한다(LearningPathAdminService).
     */
    public void clearSteps() {
        steps.clear();
    }

    public void addSteps(List<Document> documents) {
        int seq = steps.size();
        for (Document d : documents) {
            steps.add(new LearningPathStep(this, ++seq, d));
        }
    }
}
