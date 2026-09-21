package project.study.study_project.llm.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import project.study.study_project.global.common.Domain;
import project.study.study_project.llm.domain.DomainSetting;

import java.util.List;
import java.util.Optional;

/**
 * 분야 설정 조회 — {@code domain_setting}(V19).
 *
 * <p>PK가 {@link Domain}이라 {@code JpaRepository<DomainSetting, Domain>}으로 바로
 * {@code findById(Domain)}이 된다. 그런데도 {@link #findByDomain}을 따로 두는 이유는
 * 부르는 쪽 코드에서 "분야로 설정을 찾는다"는 뜻이 {@code findById}보다 분명히 읽혀서다 —
 * PK가 숫자가 아니라는 사실이 잘 드러나지 않는 이름이면, 나중에 이 테이블만 보고도
 * "id 컬럼이 따로 있겠지"라고 오해하기 쉽다.
 */
public interface DomainSettingRepository extends JpaRepository<DomainSetting, Domain> {

    /** 관리 화면·동기화가 보는 순서 — 날짜 순환이 이 순서를 그대로 따른다. */
    List<DomainSetting> findAllByOrderBySortOrderAsc();

    Optional<DomainSetting> findByDomain(Domain domain);
}
