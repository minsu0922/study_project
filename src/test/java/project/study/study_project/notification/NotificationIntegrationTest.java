package project.study.study_project.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.TestFixtures;
import project.study.study_project.discussion.domain.Post;
import project.study.study_project.discussion.dto.CommentItem;
import project.study.study_project.discussion.dto.CommentWriteRequest;
import project.study.study_project.discussion.service.CommentService;
import project.study.study_project.notification.domain.NotificationType;
import project.study.study_project.notification.dto.NotificationItem;
import project.study.study_project.notification.service.NotificationService;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.report.domain.ReportReason;
import project.study.study_project.report.dto.ProblemReportRequest;
import project.study.study_project.report.service.ProblemReportService;
import project.study.study_project.user.domain.User;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 알림함(V31) — 누구에게 무엇이 쌓이는지, 읽음 처리가 내 것에만 닿는지. */
@SpringBootTest
@Transactional
class NotificationIntegrationTest {

    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private CommentService commentService;
    @Autowired
    private ProblemReportService reportService;
    @Autowired
    private NotificationService notificationService;

    @Test
    @DisplayName("내 글에 남이 댓글을 달면 글쓴이에게 알림이 온다")
    void commentNotifiesPostAuthor() {
        Problem problem = fixtures.problem();
        User author = fixtures.solver(problem);
        User commenter = fixtures.solver(problem);
        Post post = fixtures.post(problem, author.getId());

        commentService.write(commenter.getId(), new CommentWriteRequest(post.getId(), null, "댓글"));

        List<NotificationItem> items = itemsOf(author);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).type()).isEqualTo(NotificationType.POST_COMMENT);
        assertThat(items.get(0).link()).isEqualTo("/post.html?id=" + post.getId());
        assertThat(items.get(0).read()).isFalse();
        assertThat(notificationService.unreadCount(author.getId())).isEqualTo(1);
        assertThat(itemsOf(commenter)).isEmpty();
    }

    @Test
    @DisplayName("내 글에 내가 댓글을 달면 알림이 오지 않는다")
    void ownCommentDoesNotNotify() {
        Problem problem = fixtures.problem();
        User author = fixtures.solver(problem);
        Post post = fixtures.post(problem, author.getId());

        commentService.write(author.getId(), new CommentWriteRequest(post.getId(), null, "내 댓글"));

        assertThat(itemsOf(author)).isEmpty();
    }

    @Test
    @DisplayName("답글은 댓글 쓴 사람과 글쓴이에게 따로 알린다")
    void replyNotifiesParentAuthorAndPostAuthor() {
        Problem problem = fixtures.problem();
        User author = fixtures.solver(problem);
        User commenter = fixtures.solver(problem);
        User replier = fixtures.solver(problem);
        Post post = fixtures.post(problem, author.getId());
        CommentItem parent = commentService.write(commenter.getId(),
                new CommentWriteRequest(post.getId(), null, "댓글"));

        commentService.write(replier.getId(), new CommentWriteRequest(post.getId(), parent.id(), "답글"));

        assertThat(itemsOf(commenter)).extracting(NotificationItem::type)
                .containsExactly(NotificationType.COMMENT_REPLY);
        assertThat(itemsOf(author)).extracting(NotificationItem::type)
                .containsExactly(NotificationType.POST_COMMENT, NotificationType.POST_COMMENT);
    }

    @Test
    @DisplayName("제보가 인정되면 제보한 사람에게 관리자 답과 함께 알린다")
    void acceptedReportNotifiesReporter() {
        Problem problem = fixtures.problem();
        User reporter = fixtures.solver(problem);
        Long reportId = reportService.report(reporter.getId(),
                new ProblemReportRequest(problem.getId(), ReportReason.WRONG_ANSWER, "정답이 틀렸습니다")).id();

        reportService.accept(reportId, "보기를 고쳤습니다");

        List<NotificationItem> items = itemsOf(reporter);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).type()).isEqualTo(NotificationType.REPORT_ACCEPTED);
        assertThat(items.get(0).message()).contains("보기를 고쳤습니다");
    }

    @Test
    @DisplayName("읽음 처리는 내 알림에만 닿는다")
    void markReadOnlyTouchesOwnNotification() {
        Problem problem = fixtures.problem();
        User author = fixtures.solver(problem);
        User commenter = fixtures.solver(problem);
        Post post = fixtures.post(problem, author.getId());
        commentService.write(commenter.getId(), new CommentWriteRequest(post.getId(), null, "댓글"));
        Long notificationId = itemsOf(author).get(0).id();

        notificationService.markRead(commenter.getId(), notificationId);
        assertThat(notificationService.unreadCount(author.getId())).isEqualTo(1);

        notificationService.markRead(author.getId(), notificationId);
        assertThat(notificationService.unreadCount(author.getId())).isZero();
    }

    @Test
    @DisplayName("모두 읽음은 안 읽은 수를 0으로 만든다")
    void markAllRead() {
        Problem problem = fixtures.problem();
        User author = fixtures.solver(problem);
        User commenter = fixtures.solver(problem);
        Post post = fixtures.post(problem, author.getId());
        commentService.write(commenter.getId(), new CommentWriteRequest(post.getId(), null, "하나"));
        commentService.write(commenter.getId(), new CommentWriteRequest(post.getId(), null, "둘"));

        notificationService.markAllRead(author.getId());

        assertThat(notificationService.unreadCount(author.getId())).isZero();
        assertThat(itemsOf(author)).allMatch(NotificationItem::read);
    }

    private List<NotificationItem> itemsOf(User user) {
        return notificationService.list(user.getId(), PageRequest.of(0, 20)).content();
    }
}
