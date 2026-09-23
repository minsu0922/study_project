package project.study.study_project.llm.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import project.study.study_project.TestDomains;
import project.study.study_project.admin.dto.AdminTopicQueueRequest;
import project.study.study_project.global.common.DomainCode;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.llm.domain.TopicQueueItem;
import project.study.study_project.llm.dto.TopicQueueFile;
import project.study.study_project.llm.dto.TopicQueueItemResponse;
import project.study.study_project.llm.repository.TopicQueueItemRepository;
import project.study.study_project.llm.support.DefaultDomains;

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
        service = new TopicQueueService(repository, events, DefaultDomains.catalog());
    }

    /* ── 추가 ─────────────────────────────────────────────────── */

    @Test
    @DisplayName("새 범위는 맨 뒤에 붙는다 — 순서는 사람이 정한 대로 유지된다")
    void addsToTheEnd() {
        when(repository.existsByDomainAndTopic(any(), any())).thenReturn(false);
        when(repository.findMaxSortOrder()).thenReturn(7);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.add(new AdminTopicQueueRequest(TestDomains.BACKEND_FRAMEWORK, "  Spring 트랜잭션  ", null));

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

        service.add(new AdminTopicQueueRequest(TestDomains.OS, "메모리 관리", null));

        assertThat(captureSaved().getSortOrder()).isEqualTo(10);
    }

    /**
     * V10에서는 "대기 중인 것만" 중복으로 봤다(다 쓴 주제는 다시 넣을 수 있어야 했으므로).
     * 범위는 소진되지 않으므로 이제 같은 이름이 둘 있으면 <b>그 범위만 두 배로 자주</b> 걸린다.
     */
    @Test
    @DisplayName("같은 분야에 같은 범위가 이미 있으면 막는다 — 두 벌이면 순환이 그쪽으로 쏠린다")
    void rejectsDuplicateRange() {
        when(repository.existsByDomainAndTopic(TestDomains.OS, "메모리 관리")).thenReturn(true);

        assertThatThrownBy(() -> service.add(new AdminTopicQueueRequest(TestDomains.OS, "메모리 관리", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미 있습니다");
        verify(repository, never()).save(any());
    }

    /* ── 수정 (2026-09-14) ────────────────────────────────────── */

    /**
     * <b>이 기능이 존재하는 이유가 이 한 줄이다.</b> 수정이 없던 동안 제목을 고치려면 삭제 후
     * 재등록밖에 없었는데, 그러면 {@code lastUsedAt}·{@code usedCount}가 날아가 그 줄이
     * <b>아직 안 쓴 범위</b>로 되살아난다. 다음 차례 규칙이 "안 쓴 것 먼저"라, 제목만
     * 다듬으려던 사람이 순환 순서를 통째로 흔들게 된다.
     *
     * <p>그 실패는 조용하다 — 목록은 멀쩡해 보이고, 며칠 뒤 엉뚱한 범위로 문서가 나와야 안다.
     * 그래서 "사용 기록이 그대로인가"를 기능의 본체로 보고 여기서 못 박는다.
     */
    @Test
    @DisplayName("수정해도 사용 기록과 순서는 그대로다 — 이게 삭제 후 재등록과 갈리는 지점")
    void editKeepsUsageAndOrder() {
        TopicQueueItem target = used(3L, TestDomains.OS, "옛 제목", 5, LocalDate.of(2026, 9, 1));
        when(repository.findById(3L)).thenReturn(Optional.of(target));
        when(repository.existsByDomainAndTopicAndIdNot(TestDomains.OS, "새 제목", 3L)).thenReturn(false);

        service.update(3L, new AdminTopicQueueRequest(TestDomains.OS, "새 제목", "왜 고쳤는지"));

        assertThat(target.getTopic()).isEqualTo("새 제목");
        assertThat(target.getMemo()).isEqualTo("왜 고쳤는지");
        assertThat(target.getLastUsedAt())
                .as("기록이 지워지면 그 줄이 곧바로 다음 차례가 된다")
                .isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(target.getUsedCount()).isEqualTo(1);
        assertThat(target.getSortOrder()).as("순서는 ↑↓의 몫이다").isEqualTo(5);
    }

    /**
     * 중복 검사에서 <b>자기 자신을 빼는지</b>. 추가와 같은 검사를 그대로 쓰면 분야와 주제를
     * 그대로 둔 채 메모만 고쳐도 막힌다 — 기능을 안 만드느니만 못한 상태가 된다.
     */
    @Test
    @DisplayName("이름을 그대로 두고 메모만 고칠 수 있다 — 자기 자신은 중복이 아니다")
    void editAllowsKeepingItsOwnName() {
        TopicQueueItem target = item(3L, TestDomains.OS, "메모리 관리", 5);
        when(repository.findById(3L)).thenReturn(Optional.of(target));
        when(repository.existsByDomainAndTopicAndIdNot(TestDomains.OS, "메모리 관리", 3L)).thenReturn(false);

        service.update(3L, new AdminTopicQueueRequest(TestDomains.OS, "메모리 관리", "메모만 붙였다"));

        assertThat(target.getMemo()).isEqualTo("메모만 붙였다");
    }

    @Test
    @DisplayName("다른 줄과 이름이 겹치면 막는다 — 두 벌이면 순환이 그쪽으로 쏠리는 건 추가와 같다")
    void editRejectsAnotherRowsName() {
        when(repository.findById(3L)).thenReturn(Optional.of(item(3L, TestDomains.OS, "옛 제목", 5)));
        when(repository.existsByDomainAndTopicAndIdNot(TestDomains.OS, "남의 제목", 3L)).thenReturn(true);

        assertThatThrownBy(() -> service.update(3L,
                new AdminTopicQueueRequest(TestDomains.OS, "남의 제목", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미 있습니다");
    }

    /**
     * 분야 변경은 <b>막지 않는다</b>. 위험한 값인 것은 맞지만(이 범위로 만들 문서와 사흘치
     * 문제의 분야가 함께 바뀐다), 막으면 잘못 고른 분야를 되돌릴 길이 삭제 후 재등록뿐이라
     * 위 {@code editKeepsUsageAndOrder}가 막으려는 사고로 되돌아간다. 경고는 화면이 맡는다.
     */
    @Test
    @DisplayName("분야도 고칠 수 있다 — 막으면 잘못 고른 분야를 되돌릴 길이 재등록뿐이다")
    void editCanChangeDomain() {
        TopicQueueItem target = used(3L, TestDomains.OS, "메모리 관리", 5, LocalDate.of(2026, 9, 1));
        when(repository.findById(3L)).thenReturn(Optional.of(target));
        when(repository.existsByDomainAndTopicAndIdNot(TestDomains.DATABASE, "메모리 관리", 3L)).thenReturn(false);

        service.update(3L, new AdminTopicQueueRequest(TestDomains.DATABASE, "메모리 관리", null));

        assertThat(target.getDomain()).isEqualTo(TestDomains.DATABASE);
        assertThat(target.getUsedCount()).as("분야를 바꿔도 기록은 그대로다").isEqualTo(1);
    }

    /* ── 일괄 맨 위로 (2026-09-14) ────────────────────────────── */

    /**
     * <b>이 기능이 생긴 이유가 이 테스트다.</b> {@code TOP}을 여러 번 누르면 나중에 누른 것이
     * 위로 가서 <b>고른 순서의 역순</b>으로 쌓인다. 셋만 올려도 뒤집히니, 올린 뒤 다시
     * ↑↓로 정렬해야 했다.
     */
    @Test
    @DisplayName("고른 것들이 상대 순서를 지킨 채 덩어리로 올라간다 — TOP을 여러 번 누르면 뒤집힌다")
    void moveToTopKeepsRelativeOrder() {
        TopicQueueItem a = item(1L, TestDomains.OS, "1번", 1);
        TopicQueueItem b = item(2L, TestDomains.OS, "2번", 2);
        TopicQueueItem c = item(3L, TestDomains.OS, "3번", 3);
        TopicQueueItem d = item(4L, TestDomains.OS, "4번", 4);
        when(repository.findAllByOrderBySortOrderAsc()).thenReturn(List.of(a, b, c, d));

        // 일부러 뒤죽박죽으로 보낸다 — 화면이 체크한 순서를 실어 보내도 결과가 같아야 한다
        service.moveToTop(List.of(4L, 2L));

        assertThat(b.getSortOrder()).as("2번이 4번보다 앞이었으므로 그대로 앞이다").isEqualTo(1);
        assertThat(d.getSortOrder()).isEqualTo(2);
        assertThat(a.getSortOrder()).as("나머지도 원래 순서를 지키며 뒤로 밀린다").isEqualTo(3);
        assertThat(c.getSortOrder()).isEqualTo(4);
    }

    @Test
    @DisplayName("없는 id가 섞여도 나머지는 옮긴다 — 여러 쪽에 걸쳐 고르는 동안 지워졌을 수 있다")
    void moveToTopIgnoresMissingIds() {
        TopicQueueItem a = item(1L, TestDomains.OS, "1번", 1);
        TopicQueueItem b = item(2L, TestDomains.OS, "2번", 2);
        when(repository.findAllByOrderBySortOrderAsc()).thenReturn(List.of(a, b));

        service.moveToTop(List.of(2L, 999L));

        assertThat(b.getSortOrder()).isEqualTo(1);
        assertThat(a.getSortOrder()).isEqualTo(2);
    }

    @Test
    @DisplayName("고른 것이 하나도 없으면 파일을 다시 내보내지 않는다 — 헛 커밋거리를 만들지 않는다")
    void moveToTopWithNothingDoesNotTouchFile() {
        service.moveToTop(List.of());

        verify(events, never()).publishEvent(any());
    }

    /* ── 정렬 (2026-09-14) ────────────────────────────────────── */

    /**
     * 정렬의 첫 줄과 "다음 차례" 배지가 <b>같은 줄</b>을 가리켜야 한다. 둘이 갈리면
     * 화면이 두 가지 답을 내놓는 셈이고, 그때 사람은 어느 쪽을 믿을지 알 수 없다.
     */
    @Test
    @DisplayName("다음 차례 순으로 정렬하면 첫 줄이 곧 '다음 차례'다 — 규칙이 갈라지면 어긋난다")
    void nextUpSortAgreesWithTheBadge() {
        List<TopicQueueItem> items = List.of(
                used(1L, TestDomains.OS, "오래전에 씀", 1, LocalDate.of(2026, 1, 1)),
                used(2L, TestDomains.OS, "최근에 씀", 2, LocalDate.of(2026, 9, 1)),
                item(3L, TestDomains.OS, "아직 안 씀", 3));
        when(repository.findAllByOrderBySortOrderAsc()).thenReturn(items);

        List<TopicQueueItemResponse> sorted = service
                .search(null, null, TopicQueueService.TopicUsage.ALL, PageRequest.of(0, 20))
                .content();

        assertThat(sorted).extracting(TopicQueueItemResponse::topic)
                .containsExactly("아직 안 씀", "오래전에 씀", "최근에 씀");
        assertThat(sorted.get(0).next())
                .as("첫 줄에 배지가 없으면 정렬과 배지가 다른 규칙을 쓰고 있다는 뜻이다")
                .isTrue();
    }

    /**
     * <b>안 쓴 것끼리는 내가 놓은 순서가 전부를 정한다.</b> 2026-09-14에 정렬 선택지를 없앨 수
     * 있었던 근거이고, 화면의 ↑↓가 여전히 뜻을 갖는 이유다.
     *
     * <p>지금 대기열은 여든 줄 중 일흔넷이 안 쓴 것이다. 그 무리에서는 규칙 ①②가 전부
     * 동점이라 ③(내가 놓은 순서)만 남는다 — 그래서 "먼저 나올 순서"와 "내가 놓은 순서"가
     * 사실상 같은 목록이었고, 둘 중 하나를 고르라고 물을 이유가 없었다.
     *
     * <p>이 성질이 깨지면 ↑↓가 <b>눌러도 안 움직이는 버튼</b>이 된다. 그때는 잠그거나
     * 다른 조작을 줘야 하므로, 조용히 바뀌지 않게 여기서 못 박는다.
     */
    @Test
    @DisplayName("안 쓴 것끼리는 내가 놓은 순서가 그대로 남는다 — ↑↓가 뜻을 갖는 근거")
    void neverUsedRangesKeepHumanOrder() {
        when(repository.findAllByOrderBySortOrderAsc()).thenReturn(List.of(
                item(1L, TestDomains.OS, "먼저 적은 것", 1),
                item(2L, TestDomains.OS, "나중에 적은 것", 2)));

        List<TopicQueueItemResponse> listed = service
                .search(null, null, TopicQueueService.TopicUsage.ALL, PageRequest.of(0, 20))
                .content();

        assertThat(listed).extracting(TopicQueueItemResponse::topic)
                .containsExactly("먼저 적은 것", "나중에 적은 것");
    }

    @Test
    @DisplayName("쓴 적 있는 줄은 내가 놓은 순서와 무관하게 뒤로 간다 — 맨 위로 올려도 밀린다")
    void usedRangesFallBehindRegardlessOfHumanOrder() {
        when(repository.findAllByOrderBySortOrderAsc()).thenReturn(List.of(
                used(1L, TestDomains.OS, "맨 위에 놓았지만 쓴 것", 1, LocalDate.of(2026, 9, 1)),
                item(2L, TestDomains.OS, "뒤에 놓았지만 안 쓴 것", 2)));

        List<TopicQueueItemResponse> listed = service
                .search(null, null, TopicQueueService.TopicUsage.ALL, PageRequest.of(0, 20))
                .content();

        assertThat(listed).extracting(TopicQueueItemResponse::topic)
                .as("이 성질을 화면이 문단으로 설명한다 — 어긋나면 그 설명이 거짓말이 된다")
                .containsExactly("뒤에 놓았지만 안 쓴 것", "맨 위에 놓았지만 쓴 것");
    }

    /* ── 거르기 (2026-09-14) ──────────────────────────────────── */

    /**
     * 대기열이 여든 줄까지 늘면서 <b>"안 쓴 것만 보고 싶다"</b>가 가장 잦은 요구가 됐다.
     * 안 쓴 범위가 다음 차례를 먼저 가져가므로, 순서를 손볼 때 실제로 다투는 것이 그 무리다.
     */
    @Test
    @DisplayName("사용 이력으로 거른다 — 안 쓴 것끼리가 다음 차례를 두고 다투는 무리다")
    void filtersByUsage() {
        when(repository.findAllByOrderBySortOrderAsc()).thenReturn(List.of(
                item(1L, TestDomains.OS, "안 쓴 것", 1),
                used(2L, TestDomains.OS, "쓴 것", 2, LocalDate.of(2026, 9, 1))));

        assertThat(listed(null, TopicQueueService.TopicUsage.NEVER_USED))
                .containsExactly("안 쓴 것");
        assertThat(listed(null, TopicQueueService.TopicUsage.USED))
                .containsExactly("쓴 것");
        assertThat(listed(null, TopicQueueService.TopicUsage.ALL)).hasSize(2);
    }

    @Test
    @DisplayName("분야로 거른다 — 자바만 손보려는데 여든 줄을 훑을 이유가 없다")
    void filtersByDomain() {
        when(repository.findAllByOrderBySortOrderAsc()).thenReturn(List.of(
                item(1L, TestDomains.OS, "운영체제 것", 1),
                item(2L, TestDomains.DATABASE, "디비 것", 2)));

        assertThat(listed(TestDomains.DATABASE, TopicQueueService.TopicUsage.ALL))
                .containsExactly("디비 것");
    }

    /**
     * 거르는 기준이 {@code usedCount}가 아니라 {@code lastUsedAt}인지.
     *
     * <p>둘이 어긋난 줄이 실제로 있었다 — 배치가 날짜를 찍은 뒤 문서가 거절된 경우다.
     * 다음 차례를 정할 때 보는 것도 날짜이므로, 거르는 기준이 다르면 <b>"안 쓴 것"으로
     * 걸러 놓고 그중에 다음 차례가 없는</b> 상태가 된다.
     */
    @Test
    @DisplayName("이력 지우기로 되돌린 줄은 '안 쓴 것'에 잡힌다 — 판정 기준이 날짜 하나여야 한다")
    void usageFilterFollowsTheDateNotTheCount() {
        TopicQueueItem restored = used(1L, TestDomains.OS, "되돌린 것", 1, LocalDate.of(2026, 9, 1));
        restored.clearUsage();
        when(repository.findAllByOrderBySortOrderAsc()).thenReturn(List.of(restored));

        assertThat(listed(null, TopicQueueService.TopicUsage.NEVER_USED))
                .containsExactly("되돌린 것");
    }

    private List<String> listed(DomainCode domain, TopicQueueService.TopicUsage usage) {
        return service.search(null, domain, usage, PageRequest.of(0, 20))
                .content().stream().map(TopicQueueItemResponse::topic).toList();
    }

    /* ── 다음 차례 판정 ───────────────────────────────────────── */

    @Test
    @DisplayName("아직 안 쓴 범위가 다음 차례다 — 새로 넣은 범위가 한 바퀴를 기다리면 안 된다")
    void marksNeverUsedRangeAsNext() {
        when(repository.findAllByOrderBySortOrderAsc()).thenReturn(List.of(
                used(1L, TestDomains.DATABASE, "인덱스", 1, LocalDate.of(2026, 8, 15)),
                item(2L, TestDomains.BACKEND_FRAMEWORK, "Spring 트랜잭션", 2)));

        assertThat(nextTopicOf(service.getAll())).isEqualTo("Spring 트랜잭션");
    }

    @Test
    @DisplayName("전부 쓴 상태면 가장 오래 안 쓴 범위가 다음 차례다 — 배치의 규칙과 같아야 한다")
    void marksLeastRecentlyUsedAsNext() {
        when(repository.findAllByOrderBySortOrderAsc()).thenReturn(List.of(
                used(1L, TestDomains.DATABASE, "인덱스", 1, LocalDate.of(2026, 8, 15)),
                used(2L, TestDomains.BACKEND_FRAMEWORK, "Spring 트랜잭션", 2, LocalDate.of(2026, 8, 7)),
                used(3L, TestDomains.NETWORK, "TCP", 3, LocalDate.of(2026, 8, 19))));

        assertThat(nextTopicOf(service.getAll())).isEqualTo("Spring 트랜잭션");
    }

    @Test
    @DisplayName("목록은 사람이 정한 순서 그대로 나간다 — 차례 순으로 재정렬하면 ↑↓가 안 먹는 것처럼 보인다")
    void keepsHumanOrderInList() {
        when(repository.findAllByOrderBySortOrderAsc()).thenReturn(List.of(
                used(1L, TestDomains.DATABASE, "인덱스", 1, LocalDate.of(2026, 8, 15)),
                item(2L, TestDomains.BACKEND_FRAMEWORK, "Spring 트랜잭션", 2)));

        assertThat(service.getAll()).extracting(TopicQueueItemResponse::topic)
                .containsExactly("인덱스", "Spring 트랜잭션");
    }

    /* ── 순서 이동 ────────────────────────────────────────────── */

    @Test
    @DisplayName("위로 이동하면 앞 항목과 순서값을 맞바꾼다")
    void moveUpSwapsWithNeighbor() {
        TopicQueueItem first = item(1L, TestDomains.OS, "메모리 관리", 1);
        TopicQueueItem second = item(2L, TestDomains.NETWORK, "TCP", 2);
        when(repository.findById(2L)).thenReturn(Optional.of(second));
        when(repository.findAllByOrderBySortOrderAsc()).thenReturn(List.of(first, second));

        service.move(2L, TopicQueueService.Direction.UP);

        assertThat(second.getSortOrder()).isEqualTo(1);
        assertThat(first.getSortOrder()).isEqualTo(2);
    }

    @Test
    @DisplayName("맨 위에서 위로 눌러도 아무 일도 일어나지 않는다 — 오류가 아니라 할 일이 없는 것")
    void moveUpAtTopDoesNothing() {
        TopicQueueItem first = item(1L, TestDomains.OS, "메모리 관리", 1);
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
        TopicQueueItem first = used(1L, TestDomains.OS, "메모리 관리", 1, LocalDate.of(2026, 8, 11));
        TopicQueueItem second = used(2L, TestDomains.NETWORK, "TCP", 2, LocalDate.of(2026, 8, 15));
        when(repository.findById(1L)).thenReturn(Optional.of(first));
        when(repository.findAllByOrderBySortOrderAsc()).thenReturn(List.of(first, second));

        service.move(1L, TopicQueueService.Direction.DOWN);

        assertThat(first.getSortOrder()).isEqualTo(2);
        assertThat(second.getSortOrder()).isEqualTo(1);
    }

    /**
     * <b>2026-09-05 신설 — 맨 위로.</b>
     *
     * <p>↑만 있던 동안은 40번째 줄을 올리는 데 39번을 눌러야 했다. 대기열이 67줄까지 늘고
     * 20줄씩 쪽이 갈리면서 순서를 다시 잡는 일 자체가 막혔다.
     *
     * <p>여기서 지키는 것은 <b>맞바꾸기가 아니라 밀어내기</b>라는 점이다. 1번과 자리를
     * 맞바꾸면 원래 1번이 내가 있던 40번째로 <b>떨어진다</b> — 사람이 기대하는 것은
     * "내가 맨 위로 가고 나머지는 한 칸씩 밀린다"이지 그게 아니다.
     */
    @Test
    @DisplayName("맨 위로 옮기면 나머지가 한 칸씩 밀린다 — 1번과 자리를 맞바꾸는 것이 아니다")
    void moveToTopShiftsOthersDown() {
        TopicQueueItem first = item(1L, TestDomains.OS, "메모리 관리", 1);
        TopicQueueItem second = item(2L, TestDomains.NETWORK, "TCP", 2);
        TopicQueueItem third = item(3L, TestDomains.DATABASE, "인덱스", 3);
        when(repository.findById(3L)).thenReturn(Optional.of(third));
        when(repository.findAllByOrderBySortOrderAsc()).thenReturn(List.of(first, second, third));

        service.move(3L, TopicQueueService.Direction.TOP);

        assertThat(third.getSortOrder()).as("옮긴 줄이 맨 앞").isEqualTo(0);
        assertThat(first.getSortOrder()).as("원래 1번은 한 칸만 밀린다").isEqualTo(1);
        assertThat(second.getSortOrder()).as("사이 순서는 그대로 유지된다").isEqualTo(2);
    }

    @Test
    @DisplayName("이미 맨 위인데 맨 위로 눌러도 아무 일도 없다 — 파일이 괜히 다시 나가면 안 된다")
    void moveToTopAtTopDoesNothing() {
        TopicQueueItem first = item(1L, TestDomains.OS, "메모리 관리", 1);
        TopicQueueItem second = item(2L, TestDomains.NETWORK, "TCP", 2);
        when(repository.findById(1L)).thenReturn(Optional.of(first));
        when(repository.findAllByOrderBySortOrderAsc()).thenReturn(List.of(first, second));

        service.move(1L, TopicQueueService.Direction.TOP);

        assertThat(first.getSortOrder()).isEqualTo(1);
        assertThat(second.getSortOrder()).isEqualTo(2);
        verify(events, never()).publishEvent(any(TopicQueueChanged.class));
    }

    /* ── 사용 기록 지우기 (2026-09-05) ─────────────────────────── */

    /**
     * 기록은 배치가 <b>문서를 만들기로 정한 시점</b>에 찍힌다. 그 뒤 문서가 거절되거나
     * 지워지면 실물 없이 기록만 남고, 그 상태에서는 다음 차례가 영영 안 돌아온다 —
     * "안 쓴 것 먼저" 규칙에 계속 밀리기 때문이다. 실물이 그랬다({@code 기본키와 외래키}).
     *
     * <p>{@code recordUse}는 날짜를 앞으로만 옮기므로 이 되돌리기를 대신할 수 없다.
     */
    @Test
    @DisplayName("사용 기록을 지우면 '안 쓴 범위'로 돌아간다 — 거절된 문서의 기록이 차례를 영영 막는다")
    void resetUsageMakesItUnusedAgain() {
        TopicQueueItem item = used(1L, TestDomains.DATABASE, "기본키와 외래키", 1, LocalDate.of(2026, 9, 7));
        when(repository.findById(1L)).thenReturn(Optional.of(item));

        service.resetUsage(1L);

        assertThat(item.getLastUsedAt()).isNull();
        assertThat(item.getUsedCount()).as("날짜만 지우면 '안 썼는데 1편'이라는 줄이 남는다").isZero();
        verify(events).publishEvent(any(TopicQueueChanged.class)); // 파일도 다시 나가야 한다
    }

    /**
     * 지운 뒤 곧바로 차례가 오는지까지 본다. 엔티티만 비우고 {@code pickNext}가 그것을
     * 반영하지 않으면 화면의 "다음 차례" 배지는 그대로라, 사람 눈에는 안 지워진 것으로 보인다.
     */
    @Test
    @DisplayName("지운 범위가 곧바로 다음 차례가 된다 — 배지까지 따라와야 지운 값을 한다")
    void resetUsageBringsTheTurnBack() {
        TopicQueueItem cleared = used(1L, TestDomains.DATABASE, "기본키와 외래키", 1, LocalDate.of(2026, 9, 7));
        TopicQueueItem other = used(2L, TestDomains.NETWORK, "TCP", 2, LocalDate.of(2026, 8, 15));
        when(repository.findById(1L)).thenReturn(Optional.of(cleared));
        when(repository.findAllByOrderBySortOrderAsc()).thenReturn(List.of(cleared, other));

        service.resetUsage(1L);

        assertThat(service.getAll())
                .filteredOn(TopicQueueItemResponse::next)
                .singleElement()
                .satisfies(r -> assertThat(r.topic()).isEqualTo("기본키와 외래키"));
    }

    /* ── 파일 → DB 동기화 ─────────────────────────────────────── */

    @Test
    @DisplayName("배치가 파일에 적은 사용 기록을 DB로 되돌려 받는다 — 이게 없으면 한 범위만 계속 걸린다")
    void appliesUsageRecordFromFile() {
        TopicQueueItem item = item(3L, TestDomains.DATABASE, "인덱스", 1);
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
        TopicQueueItem item = used(3L, TestDomains.DATABASE, "인덱스", 1, LocalDate.of(2026, 8, 19));
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
                .isEqualTo(TestDomains.BACKEND_FRAMEWORK);
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

    /**
     * <b>Task 7(2026-09-22) 이후 "SPRING"이 걸리는 이유가 바뀌었다.</b> 예전에는
     * {@code TopicQueue.parseDomain}이 {@code DefaultDomains.isKnown}까지 확인해 형식 검사
     * 단계에서 걸렀다. 지금은 {@code parseDomain}이 형식만 보고 통과시키고, 대신
     * {@link #adopt}가 {@code domainCatalog.exists()}로 등록부에 없는 분야를 거른다 — 이
     * 테스트의 {@code service}는 {@code DefaultDomains.catalog()}를 등록부로 쓰므로(setUp)
     * "SPRING"은 여전히 등록 안 된 분야다. 검사 지점만 옮겨졌을 뿐 결과(조용히 건너뜀,
     * 부팅은 안 막힘)는 그대로다 — 아래 {@link #adoptSkipsUnregisteredDomain}이 이 새 방어선을
     * 직접 겨냥한다.
     */
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

    /**
     * Task 7이 새로 연 구멍을 직접 겨냥한다 — {@code TopicQueue.parseDomain}이 형식만 보게
     * 넓혀지면서, 형식은 맞지만 등록부에 없는 분야가 {@link TopicQueueService#adopt}까지
     * 들어올 길이 열렸다. 외래키(500)로 죽기 전에 {@code domainCatalog.exists()}가 조용히
     * 걸러야 한다({@code adopt} Javadoc "Task 7 이후 이 메서드가 유일한 방어선이다" 참고).
     *
     * <p>"MESSAGING_TEST"는 형식(대문자·밑줄)은 완전히 유효하지만 {@code DefaultDomains}의
     * 기본 11개에는 없다 — 이 테스트의 {@code service}가 {@code DefaultDomains.catalog()}를
     * 등록부로 쓰므로(setUp) 딱 "형식은 맞지만 미등록"인 상태를 만든다.
     */
    @Test
    @DisplayName("형식은 맞지만 등록부에 없는 분야는 조용히 건너뛴다 — FK 위반(500)으로 터지기 전에 막는다")
    void adoptSkipsUnregisteredDomain() {
        when(repository.findMaxSortOrder()).thenReturn(0);

        TopicQueueService.SyncResult result = service.syncFrom(new TopicQueueFile(null, List.of(
                new TopicQueueFile.Entry(null, "MESSAGING_TEST", "큐 기본기", null, null, null))));

        assertThat(result.imported()).isZero();
        verify(repository, never()).save(any());
        verify(events, never()).publishEvent(any(TopicQueueChanged.class));
    }

    @Test
    @DisplayName("이미 있는 범위는 흡수하지 않는다 — 두 벌이면 순환이 그쪽으로 쏠린다")
    void doesNotAdoptExistingRange() {
        when(repository.findMaxSortOrder()).thenReturn(0);
        when(repository.existsByDomainAndTopic(TestDomains.OS, "메모리 관리")).thenReturn(true);

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
    private TopicQueueItem item(Long id, DomainCode domain, String topic, int sortOrder) {
        TopicQueueItem item = TopicQueueItem.fresh(domain, topic, null, sortOrder);
        ReflectionTestUtils.setField(item, "id", id);
        return item;
    }

    private TopicQueueItem used(Long id, DomainCode domain, String topic, int sortOrder, LocalDate lastUsedAt) {
        TopicQueueItem item = item(id, domain, topic, sortOrder);
        item.recordUse(lastUsedAt, 1);
        return item;
    }
}

