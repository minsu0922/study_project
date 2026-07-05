package project.study.study_project.admin.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.admin.dto.AdminProblemDetail;
import project.study.study_project.admin.dto.AdminProblemRequest;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.global.response.PageResponse;
import project.study.study_project.quiz.domain.Choice;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.quiz.repository.SubmissionRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * 관리자 문제 관리 — 등록/수정/삭제/조회. 이 기능의 핵심은 <b>타입별 규칙 검증</b>이다.
 *
 * <p>왜 애너테이션이 아니라 코드로 검증하나: "type이 객관식이면 choices가 필수이고 answer는
 * 비워야 한다"처럼 <b>한 필드의 값에 따라 다른 필드의 규칙이 바뀌는</b> 조건부 검증은
 * Bean Validation 애너테이션으로 표현할 수 없다. 그래서 형식 검증(필수·길이)만 DTO에 두고,
 * 타입별 규칙은 여기 한 곳에 모아 QUIZ_004로 응답한다. 규칙 표는 docs/01과 1:1.
 */
@Service
@RequiredArgsConstructor
public class AdminProblemService {

    private final ProblemRepository problemRepository;
    private final SubmissionRepository submissionRepository;

    /** 관리 화면 목록 — 최신순, 필터(선택), 정답·해설 포함(ADMIN 전용 경로라 노출 가능). */
    @Transactional(readOnly = true)
    public PageResponse<AdminProblemDetail> getProblems(Domain domain, ProblemType type, Pageable pageable) {
        Page<Problem> page = problemRepository.findForAdmin(domain, type, pageable);
        return PageResponse.from(page.map(AdminProblemDetail::from));
    }

    /** 수정 폼 채우기용 단건 조회. */
    @Transactional(readOnly = true)
    public AdminProblemDetail getProblem(Long id) {
        return AdminProblemDetail.from(findProblem(id));
    }

    /** 문제 등록. 검증 통과 → Problem 저장(객관식이면 보기는 cascade로 함께 INSERT). */
    @Transactional
    public AdminProblemDetail create(AdminProblemRequest request) {
        validateByType(request);
        Problem problem = Problem.create(
                request.domain(), request.difficulty(), request.type(),
                request.question().trim(), normalizeAnswer(request), trimOrNull(request.explanation()));
        problem.replaceChoices(buildChoices(problem, request));
        return AdminProblemDetail.from(problemRepository.save(problem));
    }

    /**
     * 문제 수정 — 폼 전체 제출 방식이라 내용·보기를 통째로 교체한다.
     *
     * <p>주의해서 결정한 점: 이미 제출 이력이 있는 문제도 수정을 <b>허용</b>한다.
     * 오타 교정이 주 용도이기 때문. 대신 정답 자체를 바꾸면 과거 제출의 is_correct와
     * 어긋날 수 있다는 트레이드오프가 있다 — 과거 채점을 재계산하는 건 로드맵(관리자 기능 고도화)으로
     * 미루고, MVP는 "제출 당시의 채점 결과가 이력"이라는 해석을 택한다.
     */
    @Transactional
    public AdminProblemDetail update(Long id, AdminProblemRequest request) {
        validateByType(request);
        Problem problem = findProblem(id);
        problem.update(request.domain(), request.difficulty(), request.type(),
                request.question().trim(), normalizeAnswer(request), trimOrNull(request.explanation()));
        problem.replaceChoices(buildChoices(problem, request));
        return AdminProblemDetail.from(problem); // 변경 감지(dirty checking)로 커밋 시 자동 UPDATE
    }

    /**
     * 문제 삭제. <b>제출 이력이 있으면 거부(QUIZ_003)</b> — 이력이 참조하는 문제가 사라지면
     * 오답노트가 깨진다. DB의 FK RESTRICT(V1)가 최후의 방어선이지만, DB 에러(500)를 내느니
     * 여기서 먼저 검사해 의미 있는 에러(409)로 알려 준다.
     * 숨김(soft delete) 방식은 전 조회에 필터가 번지는 비용이 있어 로드맵으로 미뤘다.
     */
    @Transactional
    public void delete(Long id) {
        Problem problem = findProblem(id);
        if (submissionRepository.existsByProblemId(id)) {
            throw new BusinessException(ErrorCode.QUIZ_003);
        }
        problemRepository.delete(problem); // 보기(choice)는 cascade + DDL CASCADE로 함께 삭제
    }

    /* ── 내부 도우미 ─────────────────────────────────────────────── */

    private Problem findProblem(Long id) {
        return problemRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.QUIZ_001));
    }

    /**
     * 타입별 규칙(docs/01) 위반 시 QUIZ_004 + 구체적 이유.
     * 에러 메시지에 "무엇을 어떻게 고쳐야 하는지"를 담는다 — 관리자 화면이 이 메시지를 그대로 보여준다.
     */
    private void validateByType(AdminProblemRequest r) {
        boolean hasChoices = r.choices() != null && !r.choices().isEmpty();
        boolean hasAnswer = r.answer() != null && !r.answer().isBlank();

        switch (r.type()) {
            case MULTIPLE_CHOICE -> {
                if (!hasChoices || r.choices().size() < 2) {
                    throw new BusinessException(ErrorCode.QUIZ_004, "객관식은 보기를 2개 이상 입력해야 합니다.");
                }
                long correctCount = r.choices().stream().filter(AdminProblemRequest.ChoiceItem::correct).count();
                if (correctCount != 1) {
                    throw new BusinessException(ErrorCode.QUIZ_004,
                            "객관식은 정답 보기가 정확히 1개여야 합니다. (현재 " + correctCount + "개)");
                }
                if (hasAnswer) {
                    throw new BusinessException(ErrorCode.QUIZ_004,
                            "객관식은 answer를 비워야 합니다. 정답은 보기의 정답 체크로 지정합니다.");
                }
            }
            case OX -> {
                requireNoChoices(hasChoices, "OX");
                if (!hasAnswer || !r.answer().trim().toUpperCase().matches("[OX]")) {
                    throw new BusinessException(ErrorCode.QUIZ_004, "OX 문제의 answer는 O 또는 X여야 합니다.");
                }
            }
            case SHORT_ANSWER -> {
                requireNoChoices(hasChoices, "단답형");
                if (!hasAnswer) {
                    throw new BusinessException(ErrorCode.QUIZ_004,
                            "단답형은 answer가 필수입니다. 복수 정답은 |로 구분하세요. (예: arp|address resolution protocol)");
                }
            }
            // 서술형은 자동채점 미지원(MVP) — 등록을 허용하면 풀 수 없는 문제가 생긴다
            case ESSAY -> throw new BusinessException(ErrorCode.QUIZ_002,
                    "서술형(ESSAY)은 자동채점 미지원이라 아직 등록할 수 없습니다.");
        }
    }

    private void requireNoChoices(boolean hasChoices, String typeName) {
        if (hasChoices) {
            throw new BusinessException(ErrorCode.QUIZ_004, typeName + " 문제는 보기(choices)를 입력하지 않습니다.");
        }
    }

    /** 저장 형태 정규화 — OX는 대문자(O/X), 단답형은 trim, 객관식은 null. 채점 로직의 전제와 맞춘다. */
    private String normalizeAnswer(AdminProblemRequest r) {
        return switch (r.type()) {
            case MULTIPLE_CHOICE -> null;
            case OX -> r.answer().trim().toUpperCase();
            case SHORT_ANSWER -> r.answer().trim();
            case ESSAY -> null; // validateByType에서 이미 차단 — 도달 불가
        };
    }

    /** 보기 목록 생성 — seq는 입력 순서대로 1..N 부여(관리자가 번호를 직접 관리하지 않게). */
    private List<Choice> buildChoices(Problem problem, AdminProblemRequest r) {
        List<Choice> choices = new ArrayList<>();
        if (r.type() == ProblemType.MULTIPLE_CHOICE) {
            List<AdminProblemRequest.ChoiceItem> items = r.choices();
            for (int i = 0; i < items.size(); i++) {
                choices.add(Choice.of(problem, items.get(i).text().trim(), items.get(i).correct(), i + 1));
            }
        }
        return choices; // 객관식이 아니면 빈 리스트 → replaceChoices가 기존 보기를 정리
    }

    private String trimOrNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
