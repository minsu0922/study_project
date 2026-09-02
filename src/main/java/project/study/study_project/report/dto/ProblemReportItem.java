package project.study.study_project.report.dto;

import project.study.study_project.report.domain.ProblemReport;
import project.study.study_project.report.domain.ReportReason;
import project.study.study_project.report.domain.ReportStatus;

import java.time.LocalDateTime;

/**
 * 제보함 한 줄 — 관리자 화면이 읽는 모양.
 *
 * <p><b>제보자를 담지 않는다.</b> 엔티티에는 {@code userId}가 있지만 화면으로는 내보내지 않는다.
 * 1인 서비스라 알아도 쓸 데가 없고, 누가 냈는지가 보이기 시작하면 판단이 내용이 아니라 사람에
 * 끌린다. 필요해지면 그때 넣는다 — 한 번 내보낸 필드는 되돌리기 어렵다.
 *
 * <p><b>지문 전문을 담는 이유</b>는 반대다. 제보함에서 "이 지적이 맞나"를 판단하려면 문제를
 * 읽어야 하는데, 앞부분만 잘라 보내면 관리자가 매번 문제 관리 화면으로 건너가야 한다.
 * 판단에 필요한 것은 아끼지 않는다.
 *
 * @param problemTitle 목록용 한 줄 제목. 없는 문제가 있어 {@code null}일 수 있다(V13 이전 문제)
 * @param reasonLabel  사유 코드를 사람이 읽는 문장으로 — 화면이 코드-문구 표를 따로 갖지 않게
 *                     서버가 함께 준다(문구의 주인은 enum 하나뿐이라는 규칙, ReportReason 주석)
 */
public record ProblemReportItem(
        Long id,
        Long problemId,
        String problemTitle,
        String question,
        ReportReason reason,
        String reasonLabel,
        String detail,
        ReportStatus status,
        String adminNote,
        LocalDateTime createdAt,
        LocalDateTime resolvedAt
) {

    public static ProblemReportItem from(ProblemReport report) {
        return new ProblemReportItem(
                report.getId(),
                report.getProblem().getId(),
                report.getProblem().getTitle(),
                report.getProblem().getQuestion(),
                report.getReason(),
                report.getReason().getLabel(),
                report.getDetail(),
                report.getStatus(),
                report.getAdminNote(),
                report.getCreatedAt(),
                report.getResolvedAt()
        );
    }
}
