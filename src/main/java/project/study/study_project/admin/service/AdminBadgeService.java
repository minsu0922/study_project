package project.study.study_project.admin.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

/**
 * 콘솔 메뉴 배지의 개수를 한 번에 센다. 각 개수의 뜻은 원래 API와 같다 — 그 서비스를 그대로 부른다.
 * 따로 세는 쿼리를 새로 쓰지 않는 이유: 배지와 각 화면의 숫자가 어긋나면 안 된다.
 */
@Service
@RequiredArgsConstructor
public class AdminBadgeService {

    private final LlmProblemService llmProblemService;
    private final LlmDocumentService llmDocumentService;
    private final TopicQueueService topicQueueService;
    private final ProblemReportService problemReportService;
    private final CommentReportService commentReportService;
    private final PostService postService;
    private final ProblemTitleBackfillService titleBackfillService;
    private final ChoiceRationaleBackfillService rationaleBackfillService;

    @Transactional(readOnly = true)
    public AdminBadgeCounts counts() {
        long concept = llmProblemService.pendingCount(false);
        long code = llmProblemService.pendingCount(true);
        return new AdminBadgeCounts(
                concept + code, concept, code,
                llmDocumentService.pendingCount(),
                topicQueueService.count(TopicKind.CONCEPT),
                problemReportService.pendingCount(),
                commentReportService.pendingCount(),
                postService.errataPendingCount(),
                titleBackfillService.untitledCount(),
                rationaleBackfillService.missingRationaleCount());
    }
}
