package project.study.study_project.llm.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import project.study.study_project.llm.domain.ImportedDraftFile;

import java.util.List;

/**
 * 흡수 완료 파일 이력 저장소 — "이 파일 이미 가져왔나?"를 묻는 것이 전부라 메서드가 없다.
 * {@code existsById(filename)}으로 충분하다(PK가 파일명이므로, V7 주석 참고).
 */
public interface ImportedDraftFileRepository extends JpaRepository<ImportedDraftFile, String> {

    /**
     * 최근 들여온 파일 열다섯 건 — 배치 현황 화면이 쓴다(2026-09-01).
     *
     * <p>메서드가 하나 생겼으니 위 주석의 "메서드가 없다"는 이제 사실이 아니다. 다만 그 판단
     * 자체는 유효하다 — <b>배치가</b> 쓰는 것은 여전히 {@code existsById} 하나뿐이고, 이 메서드는
     * 사람이 보는 화면 전용이다. 그래서 정렬 기준도 파일명이 아니라 <b>들어온 시각</b>이다.
     * 파일명 순으로 정렬하면 손으로 채운 접미사 파일이 날짜 사이에 끼어 "최근"이 흐려진다.
     */
    List<ImportedDraftFile> findTop15ByOrderByImportedAtDesc();
}
