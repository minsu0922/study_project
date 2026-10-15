package project.study.study_project.admin.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.admin.dto.AdminBadgeCounts;
import project.study.study_project.discussion.service.CommentReportService;
import project.study.study_project.discussion.service.PostService;
import project.study.study_project.llm.domain.TopicKind;
import project.study.study_project.llm.service.ChoiceRationaleBackfillService;
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

        AdminBadgeCounts c = new AdminBadgeService(problems, documents, topics, reports, comments, posts,
                titles, rationales).counts();

        assertThat(c).isEqualTo(new AdminBadgeCounts(7, 5, 2, 1, 49, 3, 4, 6, 7, 8));
    }
}
