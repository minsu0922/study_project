package project.study.study_project.bookmark.domain;

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
import project.study.study_project.quiz.domain.Problem;

import java.time.LocalDateTime;

/**
 * 북마크 한 건(V33) — 문제 또는 문서 가운데 하나를 가리킨다.
 *
 * <p>읽기 전용 매핑이다. 담기는 {@code INSERT IGNORE}로 하므로(BookmarkRepository) 생성자가 없다.
 */
@Entity
@Table(name = "bookmark")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Bookmark {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "problem_id")
    private Problem problem;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id")
    private Document document;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
