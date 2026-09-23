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
import project.study.study_project.llm.domain.DomainSetting;
import project.study.study_project.llm.repository.DomainSettingRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code seedIfEmpty}가 "빈 표를 한 번 채우는 시드"로만 동작하는지를 지킨다(6번 작업).
 *
 * <p><b>여기서 가장 중요한 것은 {@link #doesNothingIfAnyRowRemains}다.</b> 옛
 * {@code syncWithDefaults}는 기동마다 기본 분야 11개와 표를 맞췄다 — 없는 행을 만들고, 기본
 * 목록에서 빠진 행은 지웠다. 등록부가 관리자 손으로 늘고 주는 것으로 바뀐 지금 그 규칙을
 * 그대로 두면, 관리자가 화면에서 추가한 분야가 다음 기동에 조용히 사라진다. 그래서
 * {@code seedIfEmpty}는 표에 행이 <b>하나라도</b> 있으면 손을 떼고, 표가 <b>완전히 비어
 * 있을 때만</b> 채운다 — 그 경계를 이 테스트가 지킨다.
 *
 * <p><b>"표가 완전히 빈 상태"는 여기서 재현하지 않는다.</b> 그러려면 문제·문서·생성 문제
 * 초안·생성 문서 초안·주제 대기열 다섯 표를 <b>전부</b> 비워야 하는데, {@code problem} 행은
 * {@code submission}·{@code daily_quiz_item}이 {@code ON DELETE RESTRICT}로 물고 있어(V1·V5)
 * 문제를 지우려면 그 표들까지 함께 비워야 한다 — 스키마 절반을 비우는 값을 이 통합 테스트
 * 하나가 얻으려 할 이유가 없다({@code DomainSettingServiceSyncTest} Javadoc과 같은 판단, 실제로
 * 그 규칙은 그쪽이 저장소를 가짜로 두고 DB 없이 확인한다).
 */
@SpringBootTest
@Transactional
class DomainSettingServiceTest {

    @Autowired DomainSettingService service;
    @Autowired DomainSettingRepository repository;
    @PersistenceContext EntityManager em;

    /**
     * <b>6번 작업에서 가장 중요한 회귀 방지다.</b> 옛 {@code syncWithDefaults}였다면 이 테스트가
     * 지운 INTEGRATED 행이 다음 호출에서 되살아났다 — "기본 분야에는 있는데 행이 없다"는
     * 이유였다. 이제 표에는 나머지 10개 행이 여전히 남아 있어 "완전히 비지 않았고", 그래서
     * {@code seedIfEmpty}는 INTEGRATED를 되살리지 않고 그냥 돌아온다. 이 동작이 없으면 관리자가
     * 추가한 분야도 같은 이유로 재시작마다 지워질 수 있다는 뜻이라, 이 경계가 곧 그 회귀를
     * 막는 방벽이다.
     */
    @Test
    @DisplayName("행이 하나라도 남아 있으면 시드는 지운 행을 되살리지 않는다")
    void doesNothingIfAnyRowRemains() {
        // 기준은 "기본 11개"가 아니라 <지금 등록부에 있는 수>다. 분야는 관리자가 화면에서
        // 늘리고 줄이므로, 기본 목록 크기를 기대값으로 쓰면 누가 분야를 하나 추가한 순간
        // 이 테스트가 무관하게 깨진다(2026-09-23에 실제로 그랬다 — TEST 분야 추가).
        long before = repository.count();

        for (String table : List.of("problem", "document", "generated_problem_draft",
                "generated_document_draft", "topic_queue")) {
            em.createNativeQuery("DELETE FROM " + table + " WHERE domain = :domain")
                    .setParameter("domain", TestDomains.INTEGRATED.value())
                    .executeUpdate();
        }
        em.flush();
        em.clear();
        repository.deleteById(TestDomains.INTEGRATED);

        service.seedIfEmpty();

        assertThat(repository.findByDomain(TestDomains.INTEGRATED)).isEmpty();
        assertThat(repository.count()).isEqualTo(before - 1);
    }

    @Test
    @DisplayName("이미 있는 행은 건드리지 않는다 — 기동할 때마다 화면 설정이 초기화되면 못 쓴다")
    void keepsExistingRows() {
        service.seedIfEmpty();
        repository.findByDomain(TestDomains.NETWORK).orElseThrow().edit(false, "네트워크", "내가 쓴 힌트");
        repository.flush();

        service.seedIfEmpty();

        assertThat(repository.findByDomain(TestDomains.NETWORK)).get()
                .extracting(DomainSetting::getHint).isEqualTo("내가 쓴 힌트");
    }

    @Test
    @DisplayName("배치 후보는 켜진 것만, 순서대로 준다")
    void batchDomainsAreEnabledOnesInOrder() {
        service.seedIfEmpty();

        List<DomainCode> domains = service.batchDomains();

        assertThat(domains).doesNotContain(TestDomains.CLOUD_INFRA, TestDomains.INTEGRATED);
        assertThat(domains).startsWith(TestDomains.NETWORK, TestDomains.OS, TestDomains.DATABASE);
    }

    @Test
    @DisplayName("힌트는 내장값을 초기값으로 받는다")
    void hintsStartFromBuiltIn() {
        service.seedIfEmpty();

        assertThat(service.hints().rawHintFor(TestDomains.BACKEND_FRAMEWORK)).contains("Spring DI/IoC");
    }
}
