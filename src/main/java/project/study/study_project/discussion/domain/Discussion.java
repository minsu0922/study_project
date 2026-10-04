package project.study.study_project.discussion.domain;

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
 * 토론방 — DB의 {@code discussion} 테이블(V21). 문제 하나에 방 하나다.
 *
 * <p>생성자를 열지 않는다. 방은 {@code DiscussionRepository.insertIfAbsent}로만 만든다 —
 * 첫 댓글 둘이 동시에 올 때 유일 제약 위반 없이 하나만 생기게 하려면 SQL 한 문장이어야 한다.
 */
@Entity
@Table(name = "discussion")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Discussion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "problem_id")
    private Long problemId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
