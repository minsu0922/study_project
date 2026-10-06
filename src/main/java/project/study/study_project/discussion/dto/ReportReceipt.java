package project.study.study_project.discussion.dto;

import project.study.study_project.discussion.domain.CommentReport;
import project.study.study_project.report.domain.ReportStatus;

/**
 * 신고한 사람에게 돌려주는 접수증. 접수됐다는 것만 알린다.
 *
 * <p>신고함의 한 줄({@link CommentReportItem})을 그대로 돌려주지 않는 이유: 거기에는 글쓴이의
 * 로그인 아이디와 정지 여부가 있다. 관리자가 판단하려고 보는 값이고 신고한 사람이 알 것이 아니다.
 */
public record ReportReceipt(Long id, ReportStatus status) {

    public static ReportReceipt of(CommentReport report) {
        return new ReportReceipt(report.getId(), report.getStatus());
    }
}
