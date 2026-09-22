package project.study.study_project.llm.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import project.study.study_project.global.common.DomainCode;
import project.study.study_project.llm.domain.DomainSetting;

import java.util.List;
import java.util.Optional;

/**
 * 분야 설정 조회 — {@code domain_setting}(V19).
 *
 * <p>PK가 {@link DomainCode}라 {@code JpaRepository<DomainSetting, DomainCode>}로 바로
 * {@code findById(DomainCode)}가 된다. 그런데도 {@link #findByDomain}을 따로 두는 이유는
 * 부르는 쪽 코드에서 "분야로 설정을 찾는다"는 뜻이 {@code findById}보다 분명히 읽혀서다 —
 * PK가 숫자가 아니라는 사실이 잘 드러나지 않는 이름이면, 나중에 이 테이블만 보고도
 * "id 컬럼이 따로 있겠지"라고 오해하기 쉽다.
 */
public interface DomainSettingRepository extends JpaRepository<DomainSetting, DomainCode> {

    /** 관리 화면·동기화가 보는 순서 — 날짜 순환이 이 순서를 그대로 따른다. */
    List<DomainSetting> findAllByOrderBySortOrderAsc();

    Optional<DomainSetting> findByDomain(DomainCode domain);

    /**
     * enum 변환을 거치지 않고 {@code domain} 컬럼 문자열을 그대로 읽는다(4번 작업의 동기화 전용).
     *
     * <p>{@link #findAll()}·{@link #findAllByOrderBySortOrderAsc()}는 행마다 {@link DomainCode}로
     * 변환하며 읽는다({@code DomainCodeAttributeConverter}가 {@code DomainCode.of}로 형식을 본다).
     * enum 시절에는 그 변환이 {@code Enum.valueOf}여서, 동기화가 지워야 할 "enum에 없는 이름을
     * 가진 행" 하나 때문에 {@code IllegalArgumentException}이 나고 고아 행을 <b>찾으려는 조회
     * 자체가</b> 먼저 죽었다. 지금 변환기는 형식만 보므로 그 이유는 약해졌지만, 형식이 깨진 옛
     * 값이 있으면 같은 일이 난다 — 외래키가 들어오기 전까지는 변환을 타지 않는 네이티브 SQL로
     * 우회해 둔다.
     */
    @Query(value = "SELECT domain FROM domain_setting", nativeQuery = true)
    List<String> findAllDomainNamesNative();

    /**
     * 위와 같은 이유로 지우기도 네이티브로 한다. enum으로 바꿀 수 없는 값이라
     * {@code deleteByDomain(DomainCode)} 같은 파생 쿼리를 쓸 수 없고, 파라미터도 문자열로 그대로
     * 바인딩한다.
     */
    @Modifying
    @Query(value = "DELETE FROM domain_setting WHERE domain = :domainName", nativeQuery = true)
    void deleteByDomainNameNative(@Param("domainName") String domainName);
}
