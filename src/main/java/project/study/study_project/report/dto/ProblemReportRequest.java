package project.study.study_project.report.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import project.study.study_project.report.domain.ReportReason;

/**
 * 제보 접수 요청 본문.
 *
 * <p>{@code problemId}를 경로가 아니라 본문으로 받는 이유: 이 API의 자원은 "문제"가 아니라
 * <b>새로 만들어지는 제보</b>다({@code POST /api/me/problem-reports}). 경로를
 * {@code /api/quiz/{id}/reports}로 두면 퀴즈 자원 아래에 내 것이 매달리는데, 이 저장소는
 * "내 것은 전부 {@code /api/me/**} 아래"라는 규칙을 지켜 왔다(오답노트·복습과 같은 자리).
 * 그 규칙 덕에 <b>권한 검사가 경로 하나로 끝난다</b>.
 *
 * @param reason 사유 코드. 필수 — 없으면 되먹임에 쓸 수 없는 제보가 쌓인다
 *               (거절 사유를 선택 입력으로 뒀다가 5건이 비어 나간 사고를 되풀이하지 않는다)
 * @param detail 한 줄 상세. 선택 — 문턱을 낮추는 것이 이 기능의 목적이라 강제하지 않는다.
 *               500자는 컬럼 길이와 같은 값이다(넘치면 DB가 아니라 검증에서 걸려야 한다)
 */
public record ProblemReportRequest(
        @NotNull(message = "제보할 문제를 알 수 없습니다.")
        Long problemId,

        @NotNull(message = "사유를 골라 주세요.")
        ReportReason reason,

        @Size(max = 500, message = "상세 설명은 500자까지 쓸 수 있습니다.")
        String detail
) {
}
