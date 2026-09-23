package project.study.study_project.llm.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import project.study.study_project.TestDomains;
import project.study.study_project.document.repository.DocumentRepository;
import project.study.study_project.llm.domain.DomainSetting;
import project.study.study_project.llm.repository.DomainSettingRepository;
import project.study.study_project.llm.repository.GeneratedDocumentDraftRepository;
import project.study.study_project.llm.repository.GeneratedProblemDraftRepository;
import project.study.study_project.llm.repository.TopicQueueItemRepository;
import project.study.study_project.quiz.repository.ProblemRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code seedIfEmpty}의 "빈 표에 무엇을 저장하는가" 규칙만 — 공유 DB(domain_setting·problem 등)에
 * 기대지 않는 단위 테스트.
 *
 * <p><b>왜 따로 뒀나.</b> {@link DomainSettingServiceTest}(통합 테스트)의
 * {@code fillsOnlyWhenTableIsCompletelyEmpty}도 같은 규칙을 DB로 확인하지만, "폴백 목록에 든
 * 분야가 켠 채로 태어난다"는 경우까지 실제 다섯 내용 표를 모두 비워 가며 재현하면 무거워진다
 * (리뷰 라운드 1 — task-5-brief 후속, 그리고 6번 작업에서도 같은 판단이 이어진다). 이 규칙이
 * 실제로 보는 것은 "빈 표에 무엇을 저장하는가"뿐이고, 그건 저장소를 가짜로 두면 DB 없이도
 * 그대로 잰다.
 *
 * <p><b>6번 작업에서 바뀐 것.</b> 예전에는 {@code findAllDomainNamesNative()}가 빈 목록을
 * 돌려주게 해 "행이 하나도 없는 첫 기동"을 흉내 냈다. 그 네이티브 조회 자체가 고아 행 정리용
 * 이었는데(외래키가 생기며 고아 행이 원천적으로 불가능해졌다) 이제 지워졌으므로, 대신
 * {@code repository.count()}가 0을 돌려주게 해 같은 "빈 표"를 흉내 낸다 —
 * {@code seedIfEmpty}가 실제로 보는 신호가 그것이기 때문이다.
 */
@ExtendWith(MockitoExtension.class)
class DomainSettingServiceSyncTest {

    @Mock
    private DomainSettingRepository repository;

    // seedIfEmpty()는 아래 다섯 저장소를 전혀 건드리지 않는다(사용량 확인은 delete()의 몫) —
    // 그래도 생성자 인자를 채워야 하므로 목으로만 채워 넣는다. 스텁을 하나도 걸지 않으므로
    // Mockito의 "불필요한 스텁" 엄격 검사에도 걸리지 않는다.
    @Mock
    private ProblemRepository problemRepository;
    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private GeneratedProblemDraftRepository problemDraftRepository;
    @Mock
    private GeneratedDocumentDraftRepository documentDraftRepository;
    @Mock
    private TopicQueueItemRepository topicQueueItemRepository;

    @Test
    @DisplayName("빈 표를 시드하면 폴백 목록에 든 분야는 켠 채로, 밖은 꺼진 채로 만든다")
    void createsRowsWithFallbackDrivenEnabledFlag() {
        // "행이 하나도 없는 표"를 흉내 낸다 — count()가 0이면 seedIfEmpty는
        // DefaultDomains.codes() 전부를 "없는 행"으로 보고 하나씩 저장을 시도한다.
        Mockito.when(repository.count()).thenReturn(0L);

        // fallbackBatchDomains는 원래 @Value로 주입되는 생성자 인자다 — 여기서는 그 자리에
        // NETWORK 하나만 든 목록을 직접 넣어 "폴백에 있다/없다"를 뜻대로 가른다.
        // cycleAnchor는 이 테스트가 보는 규칙과 무관해 빈 문자열(기본값과 같은 모양)로 둔다.
        DomainSettingService service = new DomainSettingService(
                repository, problemRepository, documentRepository, problemDraftRepository,
                documentDraftRepository, topicQueueItemRepository, event -> { },
                List.of(TestDomains.NETWORK), "");

        service.seedIfEmpty();

        // save가 부른 인자를 전부 붙잡는다 — 11개 기본 분야 각각 한 번씩 호출된다.
        ArgumentCaptor<DomainSetting> saved = ArgumentCaptor.forClass(DomainSetting.class);
        Mockito.verify(repository, Mockito.times(11)).save(saved.capture());

        // 폴백 목록에 든 분야(NETWORK)는 켠 채로 태어난다 — 리뷰 라운드 1이 지적한, 통합
        // 테스트에서 빠진 바로 그 경우다.
        assertThat(saved.getAllValues())
                .filteredOn(s -> s.getDomain().equals(TestDomains.NETWORK))
                .singleElement()
                .extracting(DomainSetting::isEnabled).isEqualTo(true);

        // 폴백 목록 밖 분야(INTEGRATED)는 꺼진 채로 태어난다 — 통합 테스트가 보는 것과 같은
        // 규칙을 저장소 없이도 확인한다.
        assertThat(saved.getAllValues())
                .filteredOn(s -> s.getDomain().equals(TestDomains.INTEGRATED))
                .singleElement()
                .extracting(DomainSetting::isEnabled).isEqualTo(false);
    }

    @Test
    @DisplayName("행이 하나라도 있으면 시드는 아무 것도 저장하지 않는다")
    void doesNothingWhenNotEmpty() {
        // count()가 1 이상이면 "이미 채워진 표"다 — seedIfEmpty는 이 신호 하나만 보고 즉시
        // 돌아온다. 관리자가 추가한 행을 "기본 목록에 없다"는 이유로 지우던 옛 syncWithDefaults의
        // 동작이 이 분기에서 사라졌다는 것을 저장소 없이도 확인한다.
        Mockito.when(repository.count()).thenReturn(1L);

        DomainSettingService service = new DomainSettingService(
                repository, problemRepository, documentRepository, problemDraftRepository,
                documentDraftRepository, topicQueueItemRepository, event -> { },
                List.of(TestDomains.NETWORK), "");

        service.seedIfEmpty();

        Mockito.verify(repository, Mockito.never()).save(Mockito.any());
    }
}
