package project.study.study_project.quiz.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import project.study.study_project.quiz.domain.Submission;

/**
 * Submission 저장소. MVP 채점 단계에서는 저장만 한다.
 * 오답노트(GET /api/me/wrong-answers)용 조회 메서드는 다음 단계에서 추가한다.
 */
public interface SubmissionRepository extends JpaRepository<Submission, Long> {
}
