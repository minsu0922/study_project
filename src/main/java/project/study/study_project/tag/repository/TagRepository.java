package project.study.study_project.tag.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import project.study.study_project.tag.domain.Tag;

import java.util.Optional;

/**
 * Tag 저장소 — 관리자 콘텐츠 등록의 find-or-create(있으면 재사용, 없으면 생성)에 쓰인다.
 */
public interface TagRepository extends JpaRepository<Tag, Long> {

    /** 정규화된 이름(소문자)으로 조회. name에는 UNIQUE 인덱스가 있어 빠르다(V1). */
    Optional<Tag> findByName(String name);
}
