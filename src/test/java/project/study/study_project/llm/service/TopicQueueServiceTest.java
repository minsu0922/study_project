package project.study.study_project.llm.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import project.study.study_project.admin.dto.AdminTopicQueueRequest;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.llm.domain.TopicQueueItem;
import project.study.study_project.llm.dto.TopicQueueFile;
import project.study.study_project.llm.dto.TopicQueueItemResponse;
import project.study.study_project.llm.repository.TopicQueueItemRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 주제 범위 서비스 테스트 — 관리자 입력, 다음 차례 판정, <b>파일에서 돌아오는 사용 기록</b>.
 *
 * <p>가장 값어치 있는 검증은 {@link TopicQueueService#syncFrom} 쪽이다. 이 경로는 배치가
 * 클라우드에서 남긴 흔적을 읽는 유일한 길인데, 틀려도 화면과 배치 둘 다 정상으로 보인다 —
 * 증상은 <b>한 범위만 계속 걸리거나</b> <b>편수가 켤 때마다 불어나는</b> 것이라 원인을 짚기 어렵다.
 */
@ExtendWith(MockitoExtension.class)
class TopicQueueServiceTest {

    @Mock
    private TopicQueueItemRepository repository;
    @Mock
    private ApplicationEventPublisher events;

    private TopicQueueService service;

    @BeforeEach
    void setUp() {
        service = new TopicQueueService(repository, events);
    }

    /* ── 추가 ─────────────────────────────────────────────────── */

    @Test
    @DisplayName("새 범위는 맨 뒤에 붙는다 — 순서는 사람이 정한 대로 유지된다")
    void addsToTheEnd() {
        when(repository.existsByDomainAndTopic(any(), any())).thenReturn(false);
        when(repository.findMaxSortOrder()).thenReturn(7);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.add(new AdminTopicQueueRequest(Domain.BACKEND_FRAMEWORK, "  Spring 트랜잭션  ", null));

        TopicQueueItem saved = captureSaved();
        assertThat(saved.getSortOrder()).isEqualTo(8);
        assertThat(saved.getTopic()).as("앞뒤 공백은 다듬는다").isEqualTo("Spring 트랜잭션");
        assertThat(saved.isNeverUsed()).isTrue();
        assertThat(saved.getUsedCount()).isZero();
        verify(events).publishEvent(any(TopicQueueChanged.class)); // 파일을 다시 내보내야 한다
    }

    /**
     * {@code count()}가 아니라 {@code max(sortOrder)}를 쓰는 이유를 못 박는다. 중간을 삭제하면
     * 개수와 순서값이 어긋나 <b>이미 쓰이는 값</b>이 나오고, 새 범위가 기존 범위와 같은 자리에
     * 끼어들어 순서가 뒤죽박죽이 된다.
     */
    @Test
    @DisplayName("중간을 지운 뒤 추가해도 순서값이 겹치지 않는다")
    void doesNotReuseSortOrderAfterDeletion() {
        when(repository.existsByDomainAndTopic(any(), any())).thenReturn(false);
        when(repository.findMaxSortOrder()).thenReturn(9); // 항목은 3개뿐이지만 최댓값은 9
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.add(new AdminTopicQueueRequest(Domain.OS, "메모리 관리", null));

        assertThat(captureSaved().getSortOrder()).isEqualTo(10);
    }

    /**
     * V10에서는 "대기 중인 것만" 중복으로 봤다(다 쓴 주제는 다시 넣을 수 있어야 했으므로).
     * 범위는 소진되지 않으므로 이제 같은 이름이 둘 있으면 <b>그 범위만 두 배로 자주</b> 걸린다.
     */
    @Test
    @DisplayName("같은 분야에 같은 범위가 이미 있으면 막는다 — 두 벌이면 순환이 그쪽으로 쏠린다")
    void rejectsDuplicateRange() {
        when(repository.existsByDomainAndTopic(Domain.OS, "메모리 관리")).thenReturn(true);

        assertThatThrownBy(() -> service.add(new AdminTopicQueueRequest(Domain.OS, "메모리 관리", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미 있습니다");
        verify(repository, never()).save(any());
    }

    /* ── 다음 차례 판정 ───────────────────────────────────────── */

    @Test
    @DisplayName("아직 안 쓴 범위가 다음 차례다 — 새로 넣은 범위가 한 바퀴를 기다리면 안 된다")
    void marksNeverUsedRangeAsNext() {
        when(repository.findAllByOrderBySortOrderAsc()).thenReturn(List.of(
                used(1L, Domain.DATABASE, "인덱스", 1, LocalDate.of(2026, 8, 15)),
                item(2L, Domain.BACKEND_FRAMEWORK, "Spring 트랜잭션", 2)));

        assertThat(nextTopicOf(service.getAll())).isEqualTo("Spring 트랜잭션");
    }

    @Test
    @DisplayName("전부 쓴 상태면 가장 오래 안 쓴 범위가 다음 차례다 — 배치의 규칙과 같아야 한다")
    void marksLeastRecentlyUsedAsNext() {
        when(repository.findAllByOrderBySortOrderAsc()).thenReturn(List.of(
                used(1L, Domain.DATABASE, "인덱스", 1, LocalDate.of(2026, 8, 15)),
                used(2L, Domain.BACKEND_FRAMEWORK, "Spring 트랜잭션", 2, LocalDate.of(2026, 8, 7)),
                used(3L, Domain.NETWORK, "TCP", 3, LocalDate.of(2026, 8, 19))));

        assertThat(nextTopicOf(service.getAll())).isEqualTo("Spring 트랜잭션");
    }

    @Test
    @DisplayName("목록은 사람이 정한 순서 그대로 나간다 — 차례 순으로 재정렬하면 ↑↓가 안 먹는 것처럼 보인다")
    void keepsHumanOrderInList() {
        when(repository.findAllByOrderBySortOrderAsc()).thenReturn(List.of(
                used(1L, Domain.DATABASE, "인덱스", 1, LocalDate.of(2026, 8, 15)),
                item(2L, Domain.BACKEND_FRAMEWORK, "Spring 트랜잭션", 2)));

        assertThat(service.getAll()).extracting(TopicQueueItemResponse::topic)
                .containsExactly("인덱스", "Spring 트랜잭션");
    }

    /* ── 순서 이동 ────────────────────────────────────────────── */

    @Test
    @DisplayName("위로 이동하면 앞 항목과 순서값을 맞바꾼다")
    void moveUpSwapsWithNeighbor() {
        TopicQueueItem first = item(1L, Domain.OS, "메모리 관리", 1);
        TopicQueueItem second = item(2L, Domain.NETWORK, "TCP", 2);
        when(repository.findById(2L)).thenReturn(Optional.of(second));
        when(repository.findAllByOrderBySortOrderAsc()).thenReturn(List.of(first, second));

        service.move(2L, TopicQueueService.Direction.UP);

        assertThat(second.getSortOrder()).isEqualTo(1);
        assertThat(first.getSortOrder()).isEqualTo(2);
    }

    @Test
    @DisplayName("맨 위에서 위로 눌러도 아무 일도 일어나지 않는다 — 오류가 아니라 할 일이 없는 것")
    void moveUpAtTopDoesNothing() {
        TopicQueueItem first = item(1L, Domain.OS, "메모리 관리", 1);
        when(repository.findById(1L)).thenReturn(Optional.of(first));
        when(repository.findAllByOrderBySortOrderAsc()).thenReturn(List.of(first));

        service.move(1L, TopicQueueService.Direction.UP);

        assertThat(first.getSortOrder()).isEqualTo(1);
        verify(events, never()).publishEvent(any(TopicQueueChanged.class)); // 파일도 그대로다
    }

    /**
     * V10에서는 이미 쓴 항목의 이동을 막았다(소진된 티켓의 순서에는 뜻이 없었으므로).
     * 범위는 계속 돌기 때문에 순서가 여전히 뜻을 갖는다 — 막으면 목록 절반이 굳어 버린다.
     */
    @Test
    @DisplayName("이미 쓴 범위도 순서를 바꿀 수 있다 — 범위는 계속 돌아오므로 순서가 살아 있다")
    void canMoveUsedRange() {
        TopicQueueItem first = used(1L, Domain.OS, "메모리 관리", 1, LocalDate.of(2026, 8, 11));
        TopicQueueItem second = used(2L, Domain.NETWORK, "TCP", 2, LocalDate.of(2026, 8, 15));
        when(repository.findById(1L)).thenReturn(Optional.of(first));
        when(repository.findAllByOrderBySortOrderAsc()).thenReturn(List.of(first, second));

        service.move(1L, TopicQueueService.Direction.DOWN);

        assertThat(first.getSortOrder()).isEqualTo(2);
        assertThat(second.getSortOrder()).isEqualTo(1);
    }

    /* ── 파일 → DB 동기화 ─────────────────────────────────────── */

    @Test
    @DisplayName("배치가 파일에 적은 사용 기록을 DB로 되돌려 받는다 — 이게 없으면 한 범위만 계속 걸린다")
    void appliesUsageRecordFromFile() {
        TopicQueueItem item = item(3L, Domain.DATABASE, "인덱스", 1);
        when(repository.findById(3L)).thenReturn(Optional.of(item));
        when(repository.findMaxSortOrder()).thenReturn(1);

        TopicQueueService.SyncResult result = service.syncFrom(new TopicQueueFile(null, List.of(
                new TopicQueueFile.Entry(3L, "DATABASE", "인덱스", null, "2026-08-19", 1))));

        assertThat(result.usedApplied()).isEqualTo(1);
        assertThat(item.getLastUsedAt()).isEqualTo(LocalDate.of(2026, 8, 19));
        assertThat(item.getUsedCount()).isEqualTo(1);
    }

    /**
     * 같은 파일을 여러 번 읽는 것이 정상 경로다(부팅할 때마다 훑는다). 여기서 편수를 무조건
     * 올리면 <b>앱을 켤 때마다 숫자가 불어나</b>, 범위가 말랐는지 판단하는 근거가 망가진다.
     */
    @Test
    @DisplayName("같은 기록을 두 번 읽어도 편수가 늘지 않는다 — 부팅마다 훑는 것이 정상이다")
    void doesNotDoubleCountOnSecondBoot() {
        TopicQueueItem item = used(3L, Domain.DATABASE, "인덱스", 1, LocalDate.of(2026, 8, 19));
        when(repository.findById(3L)).thenReturn(Optional.of(item));
        when(repository.findMaxSortOrder()).thenReturn(1);
        int before = item.getUsedCount();

        TopicQueueService.SyncResult result = service.syncFrom(new TopicQueueFile(null, List.of(
                new TopicQueueFile.Entry(3L, "DATABASE", "인덱스", null, "2026-08-19", before))));

        assertThat(result.usedApplied()).isZero();
        assertThat(item.getUsedCount()).isEqualTo(before);
    }

    /**
     * 화면에서 지운 범위의 기록이 파일에 남아 있을 수 있다(배치가 적고 커밋한 뒤 사람이 지운 경우).
     * 그걸 되살리면 <b>지웠는데 다시 나타나는</b> 최악의 동작이 된다.
     */
    @Test
    @DisplayName("DB에 없는 id의 기록은 무시한다 — 지운 범위가 되살아나면 안 된다")
    void ignoresRecordForDeletedItem() {
        when(repository.findById(99L)).thenReturn(Optional.empty());
        when(repository.findMaxSortOrder()).thenReturn(0);

        TopicQueueService.SyncResult result = service.syncFrom(new TopicQueueFile(null, List.of(
                new TopicQueueFile.Entry(99L, "OS", "지운 범위", null, "2026-08-19", 1))));

        assertThat(result.usedApplied()).isZero();
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("id 없는 줄은 손으로 적은 것으로 보고 DB로 가져온다 — 파일을 직접 고치는 길을 살려 둔다")
    void adoptsHandWrittenEntries() {
        when(repository.findMaxSortOrder()).thenReturn(4);
        when(repository.existsByDomainAndTopic(any(), any())).thenReturn(false);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TopicQueueService.SyncResult result = service.syncFrom(new TopicQueueFile(null, List.of(
                new TopicQueueFile.Entry(null, "backend_framework", " Spring 트랜잭션 ", "메모", null, null))));

        assertThat(result.imported()).isEqualTo(1);
        TopicQueueItem saved = captureSaved();
        assertThat(saved.getDomain()).as("소문자 분야도 읽는다(배치와 같은 규칙)")
                .isEqualTo(Domain.BACKEND_FRAMEWORK);
        assertThat(saved.getTopic()).isEqualTo("Spring 트랜잭션");
        assertThat(saved.getSortOrder()).isEqualTo(5);
    }

    /**
     * 배치가 쓴 뒤 아직 앱을 안 켠 상태에서 그 줄에 id가 없을 수 있다. 기록을 버리고
     * 새것처럼 들여오면 그 범위가 <b>곧바로 다음 차례</b>가 되어 순환이 한쪽으로 쏠린다.
     */
    @Test
    @DisplayName("손으로 적은 줄에 사용 기록이 있으면 그대로 들여온다")
    void adoptsEntryWithItsUsageRecord() {
        when(repository.findMaxSortOrder()).thenReturn(0);
        when(repository.existsByDomainAndTopic(any(), any())).thenReturn(false);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.syncFrom(new TopicQueueFile(null, List.of(
                new TopicQueueFile.Entry(null, "OS", "메모리 관리", null, "2026-08-11", 2))));

        TopicQueueItem saved = captureSaved();
        assertThat(saved.isNeverUsed()).isFalse();
        assertThat(saved.getUsedCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("분야가 잘못된 줄은 건너뛰고 부팅을 막지 않는다 — 배치가 이미 요약 화면에 경고를 띄운다")
    void skipsMalformedEntriesQuietly() {
        when(repository.findMaxSortOrder()).thenReturn(0);

        TopicQueueService.SyncResult result = service.syncFrom(new TopicQueueFile(null, List.of(
                new TopicQueueFile.Entry(null, "SPRING", "빈 생명주기", null, null, null),
                new TopicQueueFile.Entry(null, "OS", "   ", null, null, null))));

        assertThat(result.imported()).isZero();
        verify(repository, never()).save(any());
        verify(events, never()).publishEvent(any(TopicQueueChanged.class));
    }

    @Test
    @DisplayName("이미 있는 범위는 흡수하지 않는다 — 두 벌이면 순환이 그쪽으로 쏠린다")
    void doesNotAdoptExistingRange() {
        when(repository.findMaxSortOrder()).thenReturn(0);
        when(repository.existsByDomainAndTopic(Domain.OS, "메모리 관리")).thenReturn(true);

        TopicQueueService.SyncResult result = service.syncFrom(new TopicQueueFile(null, List.of(
                new TopicQueueFile.Entry(null, "OS", "메모리 관리", null, null, null))));

        assertThat(result.imported()).isZero();
    }

    /* ── 테스트 재료 ─────────────────────────────────────────── */

    private String nextTopicOf(List<TopicQueueItemResponse> items) {
        return items.stream().filter(TopicQueueItemResponse::next)
                .map(TopicQueueItemResponse::topic).findFirst().orElse(null);
    }

    private TopicQueueItem captureSaved() {
        ArgumentCaptor<TopicQueueItem> captor = ArgumentCaptor.forClass(TopicQueueItem.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    /** id는 DB가 채우는 값이라 테스트에서는 리플렉션으로 넣는다(엔티티에 setter를 열지 않기 위해). */
    private TopicQueueItem item(Long id, Domain domain, String topic, int sortOrder) {
        TopicQueueItem item = TopicQueueItem.fresh(domain, topic, null, sortOrder);
        ReflectionTestUtils.setField(item, "id", id);
        return item;
    }

    private TopicQueueItem used(Long id, Domain domain, String topic, int sortOrder, LocalDate lastUsedAt) {
        TopicQueueItem item = item(id, domain, topic, sortOrder);
        item.recordUse(lastUsedAt, 1);
        return item;
    }
}
