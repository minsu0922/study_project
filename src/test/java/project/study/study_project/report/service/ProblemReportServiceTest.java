package project.study.study_project.report.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.report.domain.ProblemReport;
import project.study.study_project.report.domain.ReportReason;
import project.study.study_project.report.domain.ReportStatus;
import project.study.study_project.report.dto.ProblemReportItem;
import project.study.study_project.report.dto.ProblemReportRequest;
import project.study.study_project.report.repository.ProblemReportRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 제보 서비스의 <b>규칙</b>을 본다 — 무엇을 거부하고, 무엇을 정규화하고, 언제 신호를 보내는가.
 *
 * <p>HTTP·권한·DB 제약은 여기서 안 본다(그쪽은 {@code ProblemReportIntegrationTest}).
 * 가짜 저장소로 보는 이유는 이 규칙들이 <b>DB 없이 결정되는 판단</b>이기 때문이다 —
 * 진짜 DB를 띄우면 느려지기만 하고 잡는 것은 같다.
 */
@ExtendWith(MockitoExtension.class)
class ProblemReportServiceTest {

    @Mock
    private ProblemReportRepository reportRepository;
    @Mock
    private ProblemRepository problemRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private ProblemReportService service;

    @Test
    @DisplayName("없는 문제를 제보하면 QUIZ_001 — 제보 전용 코드를 만들지 않는다")
    void rejectsUnknownProblem() {
        when(problemRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.report(1L, request(99L, ReportReason.TYPO, null)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.QUIZ_001);

        verify(reportRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("같은 사람이 같은 문제를 두 번 제보하면 REPORT_001")
    void rejectsDuplicate() {
        Problem problem = problem();
        when(problemRepository.findById(any())).thenReturn(Optional.of(problem));
        when(reportRepository.existsByProblem_IdAndUserId(any(), any())).thenReturn(true);

        assertThatThrownBy(() -> service.report(1L, request(1L, ReportReason.WRONG_ANSWER, null)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.REPORT_001);
    }

    /**
     * 더블클릭으로 두 요청이 겹치면 사전 검사를 <b>둘 다 통과</b>하고 두 번째가 UNIQUE 제약에
     * 걸린다. 그 예외가 그대로 새어 나가면 사용자에게는 500이 되는데, 같은 상황이 어떨 땐
     * 안내이고 어떨 땐 서버 오류인 것이 가장 나쁘다.
     */
    @Test
    @DisplayName("사전 검사를 통과해도 DB 제약에 걸리면 같은 REPORT_001로 바꿔 준다")
    void translatesConstraintViolation() {
        when(problemRepository.findById(any())).thenReturn(Optional.of(problem()));
        when(reportRepository.existsByProblem_IdAndUserId(any(), any())).thenReturn(false);
        when(reportRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk_report_problem_user"));

        assertThatThrownBy(() -> service.report(1L, request(1L, ReportReason.WRONG_ANSWER, null)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.REPORT_001);
    }

    @Test
    @DisplayName("공백뿐인 상세는 null로 저장한다 — 빈 문자열과 없음을 뒤에서 구분하지 않게")
    void normalizesBlankDetail() {
        when(problemRepository.findById(any())).thenReturn(Optional.of(problem()));
        when(reportRepository.existsByProblem_IdAndUserId(any(), any())).thenReturn(false);
        when(reportRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        ProblemReportItem saved = service.report(1L, request(1L, ReportReason.TYPO, "   "));

        assertThat(saved.detail()).isNull();
    }

    @Test
    @DisplayName("상세는 앞뒤 공백을 털어 저장한다")
    void trimsDetail() {
        when(problemRepository.findById(any())).thenReturn(Optional.of(problem()));
        when(reportRepository.existsByProblem_IdAndUserId(any(), any())).thenReturn(false);
        when(reportRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        ProblemReportItem saved = service.report(1L, request(1L, ReportReason.OTHER, "  3번도 맞습니다  "));

        assertThat(saved.detail()).isEqualTo("3번도 맞습니다");
    }

    @Test
    @DisplayName("접수된 제보는 PENDING으로 시작한다")
    void startsPending() {
        when(problemRepository.findById(any())).thenReturn(Optional.of(problem()));
        when(reportRepository.existsByProblem_IdAndUserId(any(), any())).thenReturn(false);
        when(reportRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.report(1L, request(1L, ReportReason.TYPO, null)).status())
                .isEqualTo(ReportStatus.PENDING);
    }

    @Test
    @DisplayName("없는 제보를 처리하면 REPORT_002")
    void rejectsUnknownReport() {
        when(reportRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.accept(99L, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.REPORT_002);
    }

    /**
     * <b>순서가 중요하다.</b> 존재 확인이 상태 확인보다 먼저여야 없는 id에 "이미 처리됨"이
     * 나가지 않는다 — 초안 승인 API에서 같은 순서 결함이 실제로 있었다(docs/14).
     * 위 테스트와 이 테스트가 짝을 이뤄 순서를 못 박는다.
     */
    @Test
    @DisplayName("이미 처리된 제보를 다시 처리하면 REPORT_003")
    void rejectsAlreadyResolved() {
        ProblemReport report = ProblemReport.of(problem(), 1L, ReportReason.TYPO, null);
        report.dismiss(null);
        when(reportRepository.findById(1L)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> service.accept(1L, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.REPORT_003);
    }

    /**
     * 인정은 스냅샷을 다시 찍게 해야 하고, 기각은 그러면 안 된다.
     *
     * <p>기각 쪽까지 검사하는 이유: 신호를 <b>안 보내는 것</b>도 결정이다. 보내도 결과는
     * "변경 없음"이라 눈에 안 띄지만, 로그에 빈 갱신이 쌓여 진짜 갱신이 묻힌다.
     */
    @Test
    @DisplayName("인정하면 스냅샷 신호를 보내고, 기각하면 보내지 않는다")
    void publishesOnlyOnAccept() {
        ProblemReport accepted = ProblemReport.of(problem(), 1L, ReportReason.WRONG_ANSWER, null);
        ProblemReport dismissed = ProblemReport.of(problem(), 2L, ReportReason.TYPO, null);
        when(reportRepository.findById(1L)).thenReturn(Optional.of(accepted));
        when(reportRepository.findById(2L)).thenReturn(Optional.of(dismissed));

        service.accept(1L, "맞는 지적");
        service.dismiss(2L, "오타 아님");

        // 둘을 처리했는데 신호가 1회면, 그 1회는 인정 쪽이고 기각은 안 보낸 것이다
        verify(eventPublisher, times(1)).publishEvent(any(Object.class));

        assertThat(accepted.getStatus()).isEqualTo(ReportStatus.ACCEPTED);
        assertThat(accepted.getResolvedAt()).isNotNull();
        assertThat(dismissed.getStatus()).isEqualTo(ReportStatus.DISMISSED);
    }

    /* ── 재료 ─────────────────────────────────────────────── */

    private ProblemReportRequest request(Long problemId, ReportReason reason, String detail) {
        return new ProblemReportRequest(problemId, reason, detail);
    }

    private Problem problem() {
        return Problem.create(Domain.NETWORK, Difficulty.BEGINNER, ProblemType.OX,
                "TCP 3-way handshake", "TCP 연결은 3번의 패킷 교환으로 시작한다.", "O", "해설", null);
    }
}
