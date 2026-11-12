package project.study.study_project.llm.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import project.study.study_project.TestDomains;
import project.study.study_project.domainsetting.support.DefaultDomains;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.llm.domain.TopicQueueItem;
import project.study.study_project.llm.dto.AdminTopicBoxesRequest;
import project.study.study_project.llm.dto.TopicQueueItemResponse;
import project.study.study_project.llm.repository.TopicQueueItemRepository;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 「글의 주제」 상자 저장(V48) — 사용 기록을 지키고, 파일 내보내기를 부른다. */
@ExtendWith(MockitoExtension.class)
class TopicQueueBoxServiceTest {

    @Mock
    private TopicQueueItemRepository repository;
    @Mock
    private ApplicationEventPublisher events;

    @Test
    void 두_편을_저장하면_응답에_실리고_파일을_다시_내보낸다() {
        TopicQueueItem item = TopicQueueItem.imported(TestDomains.NETWORK, "서브넷", null, 4,
                LocalDate.of(2026, 11, 1), 2);
        when(repository.findById(9L)).thenReturn(Optional.of(item));
        TopicQueueService service = new TopicQueueService(repository, events, DefaultDomains.catalog());

        TopicQueueItemResponse res = service.updateTopicBoxes(9L,
                new AdminTopicBoxesRequest("입문 문단 하나.\r\n\r\n문단 둘.", "심화 문단."));

        assertThat(res.beginnerTopicBox()).isEqualTo("입문 문단 하나.\n\n문단 둘.");
        assertThat(res.advancedTopicBox()).isEqualTo("심화 문단.");
        assertThat(res.hasTopicBoxes()).isTrue();
        assertThat(item.getUsedCount()).as("사용 기록은 그대로").isEqualTo(2);
        assertThat(item.getSortOrder()).isEqualTo(4);
        verify(events).publishEvent(any(TopicQueueChanged.class));
    }

    @Test
    void 입문편_글을_저장하면_메모의_설명할_것_줄을_지운다() {
        TopicQueueItem item = TopicQueueItem.fresh(TestDomains.NETWORK, "서브넷",
                "핵심 개념: 서브넷\n설명할 것: 왜 나누나 / 어떻게 고르나\n다루지 않을 것: NAT", 1);
        when(repository.findById(9L)).thenReturn(Optional.of(item));
        TopicQueueService service = new TopicQueueService(repository, events, DefaultDomains.catalog());

        service.updateTopicBoxes(9L, new AdminTopicBoxesRequest("입문 글", null));

        assertThat(item.getMemo()).isEqualTo("핵심 개념: 서브넷\n다루지 않을 것: NAT");
        assertThat(item.getTopic()).isEqualTo("서브넷");
    }

    @Test
    void 입문편_글이_없으면_메모를_건드리지_않는다() {
        String memo = "핵심 개념: 서브넷\n설명할 것: 왜 나누나";
        TopicQueueItem item = TopicQueueItem.fresh(TestDomains.NETWORK, "서브넷", memo, 1);
        when(repository.findById(9L)).thenReturn(Optional.of(item));
        TopicQueueService service = new TopicQueueService(repository, events, DefaultDomains.catalog());

        service.updateTopicBoxes(9L, new AdminTopicBoxesRequest(null, "심화 글"));

        assertThat(item.getMemo()).isEqualTo(memo);
    }

    @Test
    void 한_편을_비우면_상자_없음이다() {
        TopicQueueItem item = TopicQueueItem.fresh(TestDomains.NETWORK, "서브넷", null, 1);
        item.editTopicBoxes("입문", "심화");
        when(repository.findById(9L)).thenReturn(Optional.of(item));
        TopicQueueService service = new TopicQueueService(repository, events, DefaultDomains.catalog());

        TopicQueueItemResponse res = service.updateTopicBoxes(9L, new AdminTopicBoxesRequest("입문", "  "));

        assertThat(res.advancedTopicBox()).isNull();
        assertThat(res.hasTopicBoxes()).isFalse();
    }

    @Test
    void 글의_주제를_쓴_주제가_다음_차례이고_목록_맨_앞에_선다() {
        TopicQueueItem boxless = TopicQueueItem.fresh(TestDomains.NETWORK, "안 쓴 주제", null, 1);
        TopicQueueItem ready = TopicQueueItem.fresh(TestDomains.NETWORK, "다 쓴 주제", null, 2);
        org.springframework.test.util.ReflectionTestUtils.setField(boxless, "id", 1L);
        org.springframework.test.util.ReflectionTestUtils.setField(ready, "id", 2L);
        ready.editTopicBoxes("입문", "심화");
        when(repository.findAllByKindOrderBySortOrderAsc(any())).thenReturn(java.util.List.of(boxless, ready));
        TopicQueueService service = new TopicQueueService(repository, events, DefaultDomains.catalog());

        var page = service.search(null, null, TopicQueueService.TopicUsage.ALL,
                org.springframework.data.domain.PageRequest.of(0, 10)).content();

        assertThat(page.get(0).topic()).isEqualTo("다 쓴 주제");
        assertThat(page.get(0).next()).isTrue();
        assertThat(page.get(0).position()).isEqualTo(1);
        assertThat(page.get(1).next()).isFalse();
    }

    @Test
    void 없는_주제면_예외다() {
        when(repository.findById(9L)).thenReturn(Optional.empty());
        TopicQueueService service = new TopicQueueService(repository, events, DefaultDomains.catalog());

        assertThatThrownBy(() -> service.updateTopicBoxes(9L, new AdminTopicBoxesRequest("a", "b")))
                .isInstanceOf(BusinessException.class);
    }
}
