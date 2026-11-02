package project.study.study_project.admin.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.admin.dto.AdminBadgeCounts;
import project.study.study_project.discussion.service.CommentReportService;
import project.study.study_project.discussion.service.PostService;
import project.study.study_project.llm.domain.TopicKind;
import org.springframework.beans.factory.ObjectProvider;
import project.study.study_project.llm.dto.GeneratedPublishSummary;
import project.study.study_project.llm.service.ChoiceRationaleBackfillService;
import project.study.study_project.llm.service.GeneratedPublisher;
import project.study.study_project.llm.service.LlmDocumentService;
import project.study.study_project.llm.service.LlmProblemService;
import project.study.study_project.llm.service.ProblemTitleBackfillService;
import project.study.study_project.llm.service.TopicQueueService;
import project.study.study_project.report.service.ProblemReportService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 배지 개수를 모으는 자리 — 각 개수가 제자리에 들어가는지, 문제 대기는 개념+코드의 합인지 본다. */
class AdminBadgeServiceTest {

    @Test
    @DisplayName("개수 여덟을 한 응답에 모으고, 문제 대기는 개념과 코드의 합이다")
    void collectsAllCounts() {
        LlmProblemService problems = mock(LlmProblemService.class);
        LlmDocumentService documents = mock(LlmDocumentService.class);
        TopicQueueService topics = mock(TopicQueueService.class);
        ProblemReportService reports = mock(ProblemReportService.class);
        CommentReportService comments = mock(CommentReportService.class);
        PostService posts = mock(PostService.class);
        ProblemTitleBackfillService titles = mock(ProblemTitleBackfillService.class);
        ChoiceRationaleBackfillService rationales = mock(ChoiceRationaleBackfillService.class);
        when(problems.pendingCount(false)).thenReturn(5L);
        when(problems.pendingCount(true)).thenReturn(2L);
        when(documents.pendingCount()).thenReturn(1L);
        when(topics.count(TopicKind.CONCEPT)).thenReturn(49L);
        when(reports.pendingCount()).thenReturn(3L);
        when(comments.pendingCount()).thenReturn(4L);
        when(posts.errataPendingCount()).thenReturn(6L);
        when(titles.untitledCount()).thenReturn(7L);
        when(rationales.missingRationaleCount()).thenReturn(8L);
        when(topics.count(TopicKind.CODE)).thenReturn(8L);

        GeneratedPublisher publisher = mock(GeneratedPublisher.class);
        when(publisher.summary()).thenReturn(new GeneratedPublishSummary(true, 3, null));
        @SuppressWarnings("unchecked")
        ObjectProvider<GeneratedPublisher> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(publisher);

        AdminBadgeCounts c = new AdminBadgeService(problems, documents, topics, reports, comments, posts,
                titles, rationales, provider).counts();

        assertThat(c).extracting(AdminBadgeCounts::problems, AdminBadgeCounts::conceptProblems,
                        AdminBadgeCounts::codeProblems, AdminBadgeCounts::documents, AdminBadgeCounts::topics,
                        AdminBadgeCounts::reports, AdminBadgeCounts::commentReports, AdminBadgeCounts::errata,
                        AdminBadgeCounts::untitled, AdminBadgeCounts::missingRationale, AdminBadgeCounts::codeTopics)
                .containsExactly(7L, 5L, 2L, 1L, 49L, 3L, 4L, 6L, 7L, 8L, 8L);
        assertThat(c.codeNextRunAt().getDayOfWeek()).isEqualTo(java.time.DayOfWeek.SUNDAY);
        assertThat(c.publish().count()).isEqualTo(3);
    }

    @Test
    @DisplayName("보내기가 꺼져 빈이 없으면 보낼 변경 칩을 숨긴다")
    void publishUnavailableWhenDisabled() {
        @SuppressWarnings("unchecked")
        ObjectProvider<GeneratedPublisher> provider = mock(ObjectProvider.class);

        AdminBadgeCounts c = new AdminBadgeService(mock(LlmProblemService.class), mock(LlmDocumentService.class),
                mock(TopicQueueService.class), mock(ProblemReportService.class), mock(CommentReportService.class),
                mock(PostService.class), mock(ProblemTitleBackfillService.class),
                mock(ChoiceRationaleBackfillService.class), provider).counts();

        assertThat(c.publish().available()).isFalse();
    }
}
