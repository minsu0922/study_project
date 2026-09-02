package project.study.study_project.report.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.global.response.PageResponse;
import project.study.study_project.llm.service.ReviewCompleted;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.report.domain.ProblemReport;
import project.study.study_project.report.domain.ReportStatus;
import project.study.study_project.report.dto.ProblemReportItem;
import project.study.study_project.report.dto.ProblemReportRequest;
import project.study.study_project.report.dto.ReportFeedbackNote;
import project.study.study_project.report.repository.ProblemReportRepository;

import java.util.List;

/**
 * 문제 오류 제보 — 접수(학습자)와 판정(관리자)을 함께 맡는다.
 *
 * <p><b>왜 서비스를 둘로 안 가르나.</b> 학습자 쪽과 관리자 쪽이 화면도 권한도 다르지만,
 * 둘이 만지는 것은 같은 행이고 같은 상태 기계(PENDING → ACCEPTED/DISMISSED)다. 가르면
 * "이미 처리된 건인가" 같은 규칙이 두 곳에 생기거나, 한쪽이 다른 쪽을 부르는 얇은 층이 는다.
 * 권한은 이미 경로가 가른다({@code /api/me/**} vs {@code /api/admin/**}, SecurityConfig).
 *
 * <p>되먹임 통로는 docs/14의 거절 사유와 <b>같은 파일</b>로 합류한다 —
 * {@code LlmProblemService.findRecentRejectionNotes()}가 여기서 재료를 받아 간다.
 * 왜 파일을 새로 만들지 않았는지는 그쪽 주석에 적었다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProblemReportService {

    /**
     * 프롬프트에 실을 제보 사례 수. 거절 사례({@code REJECTION_NOTE_SIZE}=20)보다 적게 잡았다 —
     * 둘이 같은 블록으로 합쳐지므로 합이 프롬프트를 밀어내지 않아야 하고, 제보는 거절보다
     * 훨씬 드물게 쌓여 10건이면 최근 사례를 거의 다 담는다.
     */
    private static final int FEEDBACK_NOTE_SIZE = 10;

    private final ProblemReportRepository reportRepository;
    private final ProblemRepository problemRepository;
    /** 인정 시 스냅샷을 다시 찍게 하는 신호(ReviewCompleted 주석) — 파일 존재를 여기가 몰라도 되게. */
    private final ApplicationEventPublisher eventPublisher;

    /* ── 학습자 ───────────────────────────────────────────── */

    /**
     * 제보 접수.
     *
     * <p><b>중복을 두 겹으로 막는다.</b> 먼저 세어 보고(사람에게 쓸 만한 메시지를 주려고),
     * 그래도 뚫리면 DB의 UNIQUE 제약이 막는다(더블클릭으로 두 요청이 겹치는 경우). 두 번째
     * 겹을 예외로 다시 잡아 같은 REPORT_001로 바꾸는 이유는, 그러지 않으면 사용자가 보기에
     * <b>같은 상황이 어떨 땐 안내이고 어떨 땐 500</b>이 되기 때문이다.
     *
     * @param userId 토큰에서 꺼낸 제보자 id — 요청 본문으로는 절대 받지 않는다
     */
    @Transactional
    public ProblemReportItem report(Long userId, ProblemReportRequest request) {
        Problem problem = problemRepository.findById(request.problemId())
                .orElseThrow(() -> new BusinessException(ErrorCode.QUIZ_001));

        if (reportRepository.existsByProblem_IdAndUserId(problem.getId(), userId)) {
            throw new BusinessException(ErrorCode.REPORT_001);
        }

        // 공백뿐인 상세는 없는 것으로 본다 — 빈 문자열과 null이 섞이면 화면과 프롬프트 양쪽에서
        // "있는데 비었다"와 "없다"를 구분하는 코드가 필요해진다. 입구에서 하나로 줄인다.
        String detail = (request.detail() == null || request.detail().isBlank()) ? null : request.detail().trim();

        try {
            ProblemReport saved = reportRepository.saveAndFlush(
                    ProblemReport.of(problem, userId, request.reason(), detail));
            log.info("문제 오류 제보 접수: problemId={} reason={}", problem.getId(), request.reason());
            return ProblemReportItem.from(saved);
        } catch (DataIntegrityViolationException e) {
            // UNIQUE(problem_id, user_id) 위반 — 위 검사와 INSERT 사이에 끼어든 두 번째 요청
            throw new BusinessException(ErrorCode.REPORT_001);
        }
    }

    /* ── 관리자 ───────────────────────────────────────────── */

    /**
     * 제보함 목록.
     *
     * <p>정렬을 <b>상태가 정한다</b>: 대기 중은 오래 기다린 것부터(방치가 드러난다),
     * 그 밖은 최근 것부터. 정렬을 화면이 고르게 하지 않은 이유는 저장소 주석에 적었다.
     */
    @Transactional(readOnly = true)
    public PageResponse<ProblemReportItem> getReports(ReportStatus status, Pageable pageable) {
        Page<ProblemReport> page = status == ReportStatus.PENDING
                ? reportRepository.findOldestFirst(status, pageable)
                : reportRepository.findNewestFirst(status, pageable);
        return PageResponse.from(page.map(ProblemReportItem::from));
    }

    /** 관리 화면 배지 — 아직 안 본 제보 수. */
    @Transactional(readOnly = true)
    public long pendingCount() {
        return reportRepository.countByStatus(ReportStatus.PENDING);
    }

    /**
     * 인정 — 지적이 맞다고 판단한다. 이 건이 다음 생성 프롬프트로 되먹여진다.
     *
     * <p><b>문제를 고치지는 않는다.</b> 인정과 수정은 다른 일이다 — 어떻게 고칠지는 문제마다
     * 다르고(정답 교체·보기 수정·삭제), 그 판단을 이 API가 대신할 수 없다. 화면은 인정 버튼
     * 옆에 문제 수정 화면으로 가는 링크를 둔다.
     *
     * <p>{@link ReviewCompleted#problem()}을 발행하는 이유: 인정된 제보가 스냅샷 파일에
     * 실려야 하는데, 그 파일을 쓰는 쪽은 검수 신호만 듣고 있다. 신호를 새로 만들지 않고 이것을
     * 쓰는 것은 <b>듣는 쪽이 하는 일이 정확히 같기 때문</b>이다("문제 쪽 되먹임이 바뀌었으니
     * 다시 찍어라"). 신호 이름이 '검수'인 것은 지금은 조금 넓게 읽어야 하지만, 제보 판정도
     * 사람이 문제를 두고 내리는 검수라는 점에서 어긋나지 않는다.
     */
    @Transactional
    public ProblemReportItem accept(Long id, String adminNote) {
        ProblemReport report = findPending(id);
        report.accept(normalize(adminNote));
        eventPublisher.publishEvent(ReviewCompleted.problem());
        log.info("제보 인정: id={} problemId={}", id, report.getProblem().getId());
        return ProblemReportItem.from(report);
    }

    /**
     * 기각 — 지적이 틀렸거나 문제가 아니라고 판단한다.
     *
     * <p>스냅샷 신호를 발행하지 않는다. 기각된 제보는 되먹임에 쓰이지 않으므로 파일이 바뀔
     * 일이 없다. (신호를 보내도 "변경 없음"으로 끝나지만, 그러면 로그에 빈 갱신이 쌓여
     * 진짜 갱신이 묻힌다 — RejectionNotesExporter가 문서 신호를 흘려보내는 것과 같은 이유.)
     */
    @Transactional
    public ProblemReportItem dismiss(Long id, String adminNote) {
        ProblemReport report = findPending(id);
        report.dismiss(normalize(adminNote));
        log.info("제보 기각: id={} problemId={}", id, report.getProblem().getId());
        return ProblemReportItem.from(report);
    }

    /* ── 되먹임 ───────────────────────────────────────────── */

    /**
     * 인정된 제보를 프롬프트 재료로 읽는다 — {@code LlmProblemService}가 거절 사례와 합쳐 쓴다.
     *
     * <p>사유 문구와 상세를 <b>한 문장으로 이어 붙인다</b>. 모델에게는 "왜 나쁜가"가 한 덩어리로
     * 전달되어야 하고, 구조를 유지해 봐야 프롬프트에서 다시 이어 붙이게 된다.
     */
    @Transactional(readOnly = true)
    public List<ReportFeedbackNote> findAcceptedFeedback() {
        return reportRepository.findAcceptedForFeedback(PageRequest.of(0, FEEDBACK_NOTE_SIZE)).stream()
                .map(r -> new ReportFeedbackNote(
                        r.getProblem().getQuestion(),
                        r.getDetail() == null
                                ? r.getReason().getLabel()
                                : r.getReason().getLabel() + " — " + r.getDetail()))
                .toList();
    }

    /* ── 내부 ─────────────────────────────────────────────── */

    /**
     * 처리 대상 제보를 찾는다. 없으면 404, 이미 처리됐으면 409.
     *
     * <p><b>순서가 중요하다</b> — 존재 여부를 먼저 보고 그다음 상태를 본다. 뒤집으면 없는 id에
     * "이미 처리됨"이 나간다. 초안 승인 API에서 같은 순서 결함을 테스트가 잡은 적이 있다(docs/14).
     */
    private ProblemReport findPending(Long id) {
        ProblemReport report = reportRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_002));
        if (!report.isPending()) {
            throw new BusinessException(ErrorCode.REPORT_003);
        }
        return report;
    }

    /** 공백뿐인 메모는 없는 것으로(접수 쪽 detail과 같은 규칙). */
    private String normalize(String note) {
        return (note == null || note.isBlank()) ? null : note.trim();
    }
}
