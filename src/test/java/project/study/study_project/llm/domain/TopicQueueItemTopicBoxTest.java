package project.study.study_project.llm.domain;

import org.junit.jupiter.api.Test;
import project.study.study_project.global.common.DomainCode;

import static org.assertj.core.api.Assertions.assertThat;

class TopicQueueItemTopicBoxTest {

    private TopicQueueItem item() {
        return TopicQueueItem.fresh(DomainCode.of("NETWORK"), "서브넷", null, 1);
    }

    @Test
    void 새_주제는_상자가_없다() {
        TopicQueueItem item = item();

        assertThat(item.getBeginnerTopicBox()).isNull();
        assertThat(item.getAdvancedTopicBox()).isNull();
        assertThat(item.hasTopicBoxes()).isFalse();
    }

    @Test
    void 두_편을_다_써야_상자가_있다고_본다() {
        TopicQueueItem item = item();

        item.editTopicBoxes("입문편 글", null);
        assertThat(item.hasTopicBoxes()).isFalse();

        item.editTopicBoxes("입문편 글", "심화편 글");
        assertThat(item.hasTopicBoxes()).isTrue();
    }

    @Test
    void 줄바꿈을_LF로_맞추고_앞뒤_공백과_겹친_빈줄을_걷는다() {
        TopicQueueItem item = item();

        item.editTopicBoxes("  첫 문단.\r\n\r\n\r\n둘째 문단.\r\n  ", "한 문단\n \n\t\n끝");

        assertThat(item.getBeginnerTopicBox()).isEqualTo("첫 문단.\n\n둘째 문단.");
        assertThat(item.getAdvancedTopicBox()).isEqualTo("한 문단\n\n끝");
    }

    @Test
    void 공백뿐인_글은_안_쓴_것으로_본다() {
        TopicQueueItem item = item();

        item.editTopicBoxes("   \n  ", "\r\n");

        assertThat(item.getBeginnerTopicBox()).isNull();
        assertThat(item.getAdvancedTopicBox()).isNull();
    }

    @Test
    void 상자를_고쳐도_사용_기록과_순서는_그대로다() {
        TopicQueueItem item = TopicQueueItem.imported(DomainCode.of("NETWORK"), "서브넷", null, 7,
                java.time.LocalDate.of(2026, 11, 1), 3);

        item.editTopicBoxes("가", "나");

        assertThat(item.getSortOrder()).isEqualTo(7);
        assertThat(item.getUsedCount()).isEqualTo(3);
        assertThat(item.getLastUsedAt()).isEqualTo(java.time.LocalDate.of(2026, 11, 1));
    }
}
