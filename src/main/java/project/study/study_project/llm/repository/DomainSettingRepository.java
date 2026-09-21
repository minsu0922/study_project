package project.study.study_project.llm.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * enum 변환을 거치지 않고 {@code domain} 컬럼 문자열을 그대로 읽는다(4번 작업의 동기화 전용).
     *
     * <p>{@link #findAll()}·{@link #findAllByOrderBySortOrderAsc()}는 행마다 {@link Domain}으로
     * 변환하며 읽는다({@code @Enumerated(EnumType.STRING)}이 내부적으로 {@code Enum.valueOf}를
     * 쓴다). 그런데 동기화가 지워야 할 대상이 바로 "enum에 없는 이름을 가진 행"이다 —
     * 그 행 하나 때문에 변환이 {@code IllegalArgumentException}을 던지면, 고아 행을
     * <b>찾으려는 조회 자체가</b> 먼저 죽는다. 그래서 이 조회만은 변환을 타지 않는 네이티브
     * SQL로 우회한다.
     */
    @Query(value = "SELECT domain FROM domain_setting", nativeQuery = true)
    List<String> findAllDomainNamesNative();

    /**
     * 위와 같은 이유로 지우기도 네이티브로 한다. enum으로 바꿀 수 없는 값이라
     * {@code deleteByDomain(Domain)} 같은 파생 쿼리를 쓸 수 없고, 파라미터도 문자열로 그대로
     * 바인딩한다.
     */
    @Modifying
    @Query(value = "DELETE FROM domain_setting WHERE domain = :domainName", nativeQuery = true)
    void deleteByDomainNameNative(@Param("domainName") String domainName);
}
