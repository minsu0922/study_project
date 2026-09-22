package project.study.study_project.llm.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import project.study.study_project.TestDomains;
import project.study.study_project.llm.domain.DomainSetting;
import project.study.study_project.llm.repository.DomainSettingRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code syncWithDefaults}의 "없는 행을 만들 때 초기값을 어떻게 정하는가" 규칙만 —
 * 공유 DB(domain_setting·problem 등)에 기대지 않는 단위 테스트.
 *
 * <p><b>왜 따로 뒀나.</b> {@link DomainSettingServiceTest}(통합 테스트)의
 * {@code createsMissingRows}는 실제 DB 행을 지웠다 되살리는 방식이다. "폴백 목록에 든 분야가
 * 켠 채로 태어난다"는 경우까지 그 방식으로 재현하려면 NETWORK처럼 폴백 목록에 든 분야의 행을
 * 지워야 하는데, NETWORK의 문제는 submission·review_item·choice 등 여러 표가 참조하고 있어
 * 지우려면 스키마 절반을 함께 비워야 한다(리뷰 라운드 1 — task-5-brief 후속). 그 값을 얻으려고
 * 통합 테스트를 무겁게 만들 이유가 없다 — 이 규칙이 실제로 보는 것은 "빈 표에 무엇을 저장하는가"
 * 뿐이고, 그건 저장소를 가짜로 두면 DB 없이도 그대로 잰다. 그래서 여기서는 DB 상태가 어떻든
 * 절대 깨지지 않는 방식으로, {@link DomainSettingRepository}를 목(mock)으로 두고
 * {@code findAllDomainNamesNative}가 빈 목록을 돌려주게 해 "행이 하나도 없는 첫 기동"을
 * 그대로 흉내 낸다.
 */
@ExtendWith(MockitoExtension.class)
class DomainSettingServiceSyncTest {

    @Mock
    private DomainSettingRepository repository;

    @Test
    @DisplayName("빈 표에서 동기화하면 폴백 목록에 든 분야는 켠 채로, 밖은 꺼진 채로 만든다")
    void createsRowsWithFallbackDrivenEnabledFlag() {
        // "행이 하나도 없는 표"를 흉내 낸다 — 네이티브 조회가 비어 있으면 syncWithDefaults는
        // DefaultDomains.codes() 전부를 "없는 행"으로 보고 하나씩 저장을 시도한다.
        Mockito.when(repository.findAllDomainNamesNative()).thenReturn(List.of());

        // fallbackBatchDomains는 원래 @Value로 주입되는 생성자 인자다 — 여기서는 그 자리에
        // NETWORK 하나만 든 목록을 직접 넣어 "폴백에 있다/없다"를 뜻대로 가른다.
        // cycleAnchor는 이 테스트가 보는 규칙과 무관해 빈 문자열(기본값과 같은 모양)로 둔다.
        DomainSettingService service = new DomainSettingService(
                repository, event -> { }, List.of(TestDomains.NETWORK), "");

        service.syncWithDefaults();

        // save가 부른 인자를 전부 붙잡는다 — 11개 기본 분야 각각 한 번씩 호출된다.
        ArgumentCaptor<DomainSetting> saved = ArgumentCaptor.forClass(DomainSetting.class);
        Mockito.verify(repository, Mockito.times(11)).save(saved.capture());

        // 폴백 목록에 든 분야(NETWORK)는 켠 채로 태어난다 — 리뷰 라운드 1이 지적한, 통합
        // 테스트에서 빠진 바로 그 경우다.
        assertThat(saved.getAllValues())
                .filteredOn(s -> s.getDomain().equals(TestDomains.NETWORK))
                .singleElement()
                .extracting(DomainSetting::isEnabled).isEqualTo(true);

        // 폴백 목록 밖 분야(INTEGRATED)는 꺼진 채로 태어난다 — createsMissingRows가 통합
        // 테스트에서 보는 것과 같은 규칙을 저장소 없이도 확인한다.
        assertThat(saved.getAllValues())
                .filteredOn(s -> s.getDomain().equals(TestDomains.INTEGRATED))
                .singleElement()
                .extracting(DomainSetting::isEnabled).isEqualTo(false);
    }
}
