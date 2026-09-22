package project.study.study_project.llm.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.TestDomains;
import project.study.study_project.global.common.DomainCode;
import project.study.study_project.llm.support.DefaultDomains;
import project.study.study_project.llm.domain.DomainSetting;
import project.study.study_project.llm.repository.DomainSettingRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설정 행이 enum을 따라오는지를 지킨다.
 *
 * <p>여기서 틀리면 조용히 틀린다. 새 분야에 행이 없으면 배치 후보에서 빠지는데, 화면에는
 * 그 분야가 멀쩡히 보이므로 "왜 저기만 문제가 안 늘지"로만 드러난다.
 */
@SpringBootTest
@Transactional
class DomainSettingServiceTest {

    @Autowired DomainSettingService service;
    @Autowired DomainSettingRepository repository;
    @PersistenceContext EntityManager em;

    /**
     * <b>5번 작업(외래키)으로 {@code repository.deleteAll()}을 버렸다.</b> 표 전체를 비우면
     * 로컬 DB의 문제 147건이 domain_setting을 외래키로 가리키고 있어(V20) 삭제 자체가
     * DataIntegrityViolationException으로 거부된다.
     *
     * <p>대신 기본 분야 중 <b>내용 표 다섯 곳 어디에도 행이 없는 하나</b>만 지워 "행이 없다"를
     * 재현한다. {@code INTEGRATED}("통합시나리오")가 그 자리다 — 2026-09-22 로컬 DB 실측으로
     * problem·document·generated_problem_draft·generated_document_draft·topic_queue 전부
     * 0건이라 안전하게 지울 수 있고, 폴백 배치 목록(NETWORK~BACKEND_FRAMEWORK 8개)에도 없어
     * "폴백 밖 분야는 꺼진 채로 태어난다"는 원래 검증(옛 CLOUD_INFRA 자리)도 그대로 잇는다.
     *
     * <p>만약 이 분야에 나중에 실제 콘텐츠가 생기면 이 테스트는 <b>FK 위반으로 실패</b>한다 —
     * 조용히 깨지는 대신 원인이 분명한 실패로 알려 주므로, 그때는 지금도 비어 있는 다른
     * 기본 분야로 바꾸면 된다.
     */
    @Test
    @DisplayName("기본 분야에 있는데 행이 없으면 만든다 — 폴백 목록 밖 분야는 꺼진 채로")
    void createsMissingRows() {
        repository.deleteById(TestDomains.INTEGRATED);

        service.syncWithDefaults();

        assertThat(repository.findByDomain(TestDomains.INTEGRATED)).get()
                .extracting(DomainSetting::isEnabled).isEqualTo(false);
        // 건드리지 않은 행도 여전히 11개 그대로다 — 지운 것 하나만 되살아났을 뿐 나머지는
        // syncWithDefaults의 "있는 행은 손대지 않는다" 규칙대로 무사하다.
        assertThat(repository.count()).isEqualTo(DefaultDomains.codes().size());
    }

    @Test
    @DisplayName("행이 있는데 enum에 없으면 지운다 — 지운 분야가 화면에 남지 않는다")
    void deletesOrphanRows() {
        service.syncWithDefaults();
        // enum에 없는 값은 JPA로 저장할 수 없으므로 네이티브로 심는다. 2026-09-21에 실제로
        // FRONTEND_CS를 지웠고, 앞으로도 enum에서 빠지는 이름이 나온다 — 그때 이 행이
        // 남아 있으면 화면 목록에 뜻 없는 줄이 하나 붙는다.
        em.createNativeQuery("""
                INSERT INTO domain_setting
                    (domain, enabled, sort_order, display_name, hint, created_at, updated_at)
                VALUES ('FRONTEND_CS', true, 99, '프론트엔드CS', NULL, NOW(6), NOW(6))
                """).executeUpdate();
        em.flush();
        em.clear();

        service.syncWithDefaults();

        Long left = (Long) em.createNativeQuery(
                        "SELECT COUNT(*) FROM domain_setting WHERE domain = 'FRONTEND_CS'")
                .getSingleResult();
        assertThat(left).isZero();
    }

    @Test
    @DisplayName("이미 있는 행은 건드리지 않는다 — 기동할 때마다 화면 설정이 초기화되면 못 쓴다")
    void keepsExistingRows() {
        service.syncWithDefaults();
        repository.findByDomain(TestDomains.NETWORK).orElseThrow().edit(false, "네트워크", "내가 쓴 힌트");
        repository.flush();

        service.syncWithDefaults();

        assertThat(repository.findByDomain(TestDomains.NETWORK)).get()
                .extracting(DomainSetting::getHint).isEqualTo("내가 쓴 힌트");
    }

    @Test
    @DisplayName("배치 후보는 켜진 것만, 순서대로 준다")
    void batchDomainsAreEnabledOnesInOrder() {
        service.syncWithDefaults();

        List<DomainCode> domains = service.batchDomains();

        assertThat(domains).doesNotContain(TestDomains.CLOUD_INFRA, TestDomains.INTEGRATED);
        assertThat(domains).startsWith(TestDomains.NETWORK, TestDomains.OS, TestDomains.DATABASE);
    }

    @Test
    @DisplayName("힌트는 내장값을 초기값으로 받는다")
    void hintsStartFromBuiltIn() {
        service.syncWithDefaults();

        assertThat(service.hints().rawHintFor(TestDomains.BACKEND_FRAMEWORK)).contains("Spring DI/IoC");
    }
}
