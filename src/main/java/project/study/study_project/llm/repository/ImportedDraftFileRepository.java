package project.study.study_project.llm.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import project.study.study_project.llm.domain.ImportedDraftFile;

/**
 * 흡수 완료 파일 이력 저장소 — "이 파일 이미 가져왔나?"를 묻는 것이 전부라 메서드가 없다.
 * {@code existsById(filename)}으로 충분하다(PK가 파일명이므로, V7 주석 참고).
 */
public interface ImportedDraftFileRepository extends JpaRepository<ImportedDraftFile, String> {
}
