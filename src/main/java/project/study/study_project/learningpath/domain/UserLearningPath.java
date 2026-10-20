package project.study.study_project.learningpath.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 사용자가 시작한 코스 한 건(V43). 진도는 저장하지 않는다 — 제출 기록에서 계산한다.
 * 쓰기는 {@code INSERT ... ON DUPLICATE KEY UPDATE} 한 문장이라(UserLearningPathRepository) 생성자가 없다.
 *
 * @see PathKey 코스 키 형식
 */
@Entity
@Table(name = "user_learning_path")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserLearningPath {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "path_key", nullable = false, length = 40)
    private String pathKey;

    @Column(name = "started_at", nullable = false, updatable = false)
    private LocalDateTime startedAt;

    @Column(name = "last_studied_at", nullable = false)
    private LocalDateTime lastStudiedAt;
}
