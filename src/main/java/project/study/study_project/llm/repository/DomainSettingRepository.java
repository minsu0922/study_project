package project.study.study_project.llm.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import project.study.study_project.global.common.DomainCode;
import project.study.study_project.llm.domain.DomainSetting;

import java.util.List;
import java.util.Optional;

/**
 * 분야 설정 조회 — {@code domain_setting}(V19), 외래키(V20)로 다섯 내용 표가 이 표를 가리킨다.
 *
 * <p>PK가 {@link DomainCode}라 {@code JpaRepository<DomainSetting, DomainCode>}로 바로
 * {@code findById(DomainCode)}가 된다. 그런데도 {@link #findByDomain}을 따로 두는 이유는
 * 부르는 쪽 코드에서 "분야로 설정을 찾는다"는 뜻이 {@code findById}보다 분명히 읽혀서다 —
 * PK가 숫자가 아니라는 사실이 잘 드러나지 않는 이름이면, 나중에 이 테이블만 보고도
 * "id 컬럼이 따로 있겠지"라고 오해하기 쉽다.
 *
 * <p><b>네이티브 고아 정리 질의 둘을 6번 작업에서 지웠다</b>({@code findAllDomainNamesNative}·
 * {@code deleteByDomainNameNative}). 둘 다 {@code syncWithDefaults}가 "기본 분야 목록에서
 * 빠진 행"을 지우려고 enum 변환을 피해 쓰던 것이었다. 외래키가 생긴 지금은 고아 행 자체가
 * 생길 수 없고(다섯 표 중 하나라도 참조하면 삭제가 막힌다), 등록부가 사람이 손으로 관리하는
 * 것으로 바뀌면서 "기본 목록에 없다고 지운다"는 규칙 자체가 사라졌다 — 관리자가 추가한 분야는
 * 기본 목록에 없는 것이 <b>정상</b>이다. 그 규칙대로 도는 시드 러너가 남아 있으면 관리자가
 * 추가한 행을 기동마다 지워 버린다({@link DomainSetting} 클래스 Javadoc, {@code DomainSettingService}
 * Javadoc 참고).
 */
public interface DomainSettingRepository extends JpaRepository<DomainSetting, DomainCode> {

    /** 관리 화면·시드가 보는 순서 — 날짜 순환이 이 순서를 그대로 따른다. */
    List<DomainSetting> findAllByOrderBySortOrderAsc();

    Optional<DomainSetting> findByDomain(DomainCode domain);

    /**
     * 코드 중복 검사(6번 작업, {@code DomainSettingService#create})용. PK가 {@link DomainCode}라
     * {@code existsById}와 결과가 같지만, 부르는 쪽에서 "이 코드로 이미 등록된 분야가 있나"라는
     * 뜻이 곧바로 읽히도록 이름을 따로 둔다({@link #findByDomain}과 같은 이유).
     */
    boolean existsByDomain(DomainCode domain);

    /**
     * 지금까지 쓴 가장 큰 {@code sortOrder} — 새로 추가하는 분야는 이 뒤에 붙는다
     * ({@code TopicQueueItemRepository#findMaxSortOrder}와 같은 이유: {@code count()}로
     * 대신하면 중간 삭제 뒤에는 이미 쓰이는 값이 나올 수 있다). 표가 비어 있으면 0을 주므로
     * {@code create()}가 여기에 1을 더해 1부터 매긴다.
     */
    @Query("select coalesce(max(s.sortOrder), 0) from DomainSetting s")
    int findMaxSortOrder();
}
