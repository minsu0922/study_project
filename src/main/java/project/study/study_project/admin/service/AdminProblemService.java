package project.study.study_project.admin.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.admin.dto.AdminProblemDetail;
import project.study.study_project.admin.dto.AdminProblemRequest;
import project.study.study_project.global.common.Difficulty;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

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

    /**
     * 짝짓기의 최소 쌍 수. 둘이면 <b>한 쌍을 맞히면 나머지가 저절로 정해져</b> 찍기 확률이 1/2다 —
     * OX와 다를 바 없어진다. 그래도 하한을 둘로 잡은 이유는, 여기는 <b>사고를 막는 선</b>이고
     * 품질 기준("넷이 좋다")은 {@code ProblemItemRule}이 경고로 알리기 때문이다.
     * DB 제약처럼 굴면 셋짜리 좋은 문제를 손으로 등록할 길이 막힌다(V13·V15 주석과 같은 규칙).
     */
    private static final int PAIR_MIN = 2;

    /** 순서 배열의 최소 항목 수 — 둘은 "둘 중 하나"라 순서 문제가 성립하는 최소치다. */
    private static final int ORDER_MIN = 2;

    private final ProblemRepository problemRepository;
    private final SubmissionRepository submissionRepository;

    /** 관리 화면 목록 — 최신순, 필터(선택), 정답·해설 포함(ADMIN 전용 경로라 노출 가능). */
    @Transactional(readOnly = true)
    public PageResponse<AdminProblemDetail> getProblems(Domain domain, Difficulty difficulty, ProblemType type,
                                                        String documentSlug, Pageable pageable) {
        // 빈 문자열은 "안 고름"이다 — 화면의 select가 빈 값을 보낼 수 있어 여기서 null로 맞춘다.
        Page<Problem> page = problemRepository.findForAdmin(domain, difficulty, type, trimOrNull(documentSlug), pageable);
        return PageResponse.from(page.map(AdminProblemDetail::from));
    }

    /**
     * 관리 화면 근거 문서 필터 목록 — 문제에 실제로 붙어 있는 slug만(ProblemRepository 주석 참고).
     */
    @Transactional(readOnly = true)
    public List<String> getDocumentSlugs() {
        return problemRepository.findDistinctDocumentSlugs();
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
                request.domain(), request.difficulty(), request.type(), trimOrNull(request.title()),
                request.question().trim(), normalizeAnswer(request), trimOrNull(request.explanation()),
                trimOrNull(request.documentSlug()));
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
        problem.update(request.domain(), request.difficulty(), request.type(), trimOrNull(request.title()),
                request.question().trim(), normalizeAnswer(request), trimOrNull(request.explanation()),
                trimOrNull(request.documentSlug()));
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
            case MATCHING -> validateMatching(r, hasChoices, hasAnswer);
            case ORDERING -> validateOrdering(r, hasChoices, hasAnswer);
            // 서술형은 자동채점 미지원(MVP) — 등록을 허용하면 풀 수 없는 문제가 생긴다
            case ESSAY -> throw new BusinessException(ErrorCode.QUIZ_002,
                    "서술형(ESSAY)은 자동채점 미지원이라 아직 등록할 수 없습니다.");
        }
    }

    /**
     * 짝짓기 규칙 — 쌍이 {@value #PAIR_MIN}개 이상, 모든 쌍에 오른쪽이 있고, 양쪽 모두 중복이 없다.
     *
     * <p><b>중복을 막는 것이 이 검증의 핵심이다.</b> 오른쪽 문장 둘이 같으면 학습자에게는
     * 어느 쪽에 이어도 되는 것처럼 보이는데, 채점은 토큰으로 하므로 둘 중 하나만 정답이 된다
     * ({@code MatchToken}은 텍스트에서 계산되니 같은 문장은 같은 토큰이 되고, 그러면
     * <b>어느 왼쪽에 이어도 통과</b>해 버린다 — 어느 쪽이든 문제가 성립하지 않는다).
     * 왼쪽 중복도 같은 이유로 막는다.
     *
     * <p><b>answer를 비우게 하는 이유</b>는 객관식과 같다. 정답이 행에 있는데 answer에도 적으면
     * 두 벌이 되고, 언젠가 한쪽만 고쳐진다(docs/01의 타입별 규약).
     */
    private void validateMatching(AdminProblemRequest r, boolean hasChoices, boolean hasAnswer) {
        if (!hasChoices || r.choices().size() < PAIR_MIN) {
            throw new BusinessException(ErrorCode.QUIZ_004,
                    "짝짓기는 쌍을 " + PAIR_MIN + "개 이상 입력해야 합니다.");
        }
        if (hasAnswer) {
            throw new BusinessException(ErrorCode.QUIZ_004,
                    "짝짓기는 answer를 비워야 합니다. 정답은 각 쌍의 왼쪽·오른쪽으로 지정합니다.");
        }
        for (AdminProblemRequest.ChoiceItem item : r.choices()) {
            if (item.matchText() == null || item.matchText().isBlank()) {
                throw new BusinessException(ErrorCode.QUIZ_004,
                        "짝짓기는 모든 쌍에 오른쪽 항목이 있어야 합니다. (\"" + item.text() + "\"의 짝이 비었습니다)");
            }
        }
        requireDistinct(r.choices(), AdminProblemRequest.ChoiceItem::text, "왼쪽 항목");
        requireDistinct(r.choices(), AdminProblemRequest.ChoiceItem::matchText, "오른쪽 항목");
    }

    /**
     * 순서 배열 규칙 — 항목이 {@value #ORDER_MIN}개 이상이고, answer가 <b>1..N의 순열</b>이다.
     *
     * <p><b>왜 순열인지까지 보나.</b> answer는 "3|2|1|4"처럼 seq를 늘어놓은 것인데, 여기에
     * 빠진 번호나 중복이 있으면 <b>어떤 제출로도 맞힐 수 없는 문제</b>가 된다. 채점은 제출한
     * 순서를 seq로 바꿔 이 문자열과 통째로 비교하므로(QuizService.gradeOrdering), 기준 자체가
     * 도달 불가능하면 학습자는 몇 번을 풀어도 틀리고 이유는 화면에 나오지 않는다.
     * 저장 시점에 한 번 재는 것으로 그 부류를 통째로 막는다.
     */
    private void validateOrdering(AdminProblemRequest r, boolean hasChoices, boolean hasAnswer) {
        if (!hasChoices || r.choices().size() < ORDER_MIN) {
            throw new BusinessException(ErrorCode.QUIZ_004,
                    "순서 배열은 항목을 " + ORDER_MIN + "개 이상 입력해야 합니다.");
        }
        if (!hasAnswer) {
            throw new BusinessException(ErrorCode.QUIZ_004,
                    "순서 배열은 answer에 정답 순서를 적어야 합니다. (예: 3|2|1|4 — 입력한 항목의 번호)");
        }
        int size = r.choices().size();
        Set<Integer> seen = new HashSet<>();
        for (String token : r.answer().split("\\|")) { // |는 정규식 메타문자라 이스케이프
            int seq;
            try {
                seq = Integer.parseInt(token.trim());
            } catch (NumberFormatException e) {
                throw new BusinessException(ErrorCode.QUIZ_004,
                        "순서 배열의 answer는 항목 번호만 |로 이어 적습니다. (\"" + token.trim() + "\"은 숫자가 아닙니다)");
            }
            if (seq < 1 || seq > size || !seen.add(seq)) {
                throw new BusinessException(ErrorCode.QUIZ_004,
                        "순서 배열의 answer는 1부터 " + size + "까지를 한 번씩 써야 합니다. (\"" + r.answer() + "\")");
            }
        }
        if (seen.size() != size) {
            throw new BusinessException(ErrorCode.QUIZ_004,
                    "순서 배열의 answer에 빠진 번호가 있습니다. 항목 " + size + "개를 모두 적으세요. (\"" + r.answer() + "\")");
        }
    }

    /** 같은 값이 두 번 들어왔는지 — 어느 열인지 이름을 받아 메시지에 그대로 넣는다. */
    private void requireDistinct(List<AdminProblemRequest.ChoiceItem> items,
                                 Function<AdminProblemRequest.ChoiceItem, String> field,
                                 String columnName) {
        Set<String> seen = new HashSet<>();
        for (AdminProblemRequest.ChoiceItem item : items) {
            String value = field.apply(item).trim();
            if (!seen.add(value)) {
                throw new BusinessException(ErrorCode.QUIZ_004,
                        columnName + "이 겹칩니다. 서로 다르게 적어야 짝이 하나로 정해집니다. (\"" + value + "\")");
            }
        }
    }

    private void requireNoChoices(boolean hasChoices, String typeName) {
        if (hasChoices) {
            throw new BusinessException(ErrorCode.QUIZ_004, typeName + " 문제는 보기(choices)를 입력하지 않습니다.");
        }
    }

    /**
     * 저장 형태 정규화 — 채점 로직의 전제와 맞춘다.
     * OX는 대문자(O/X), 단답형은 trim, 순서 배열은 공백 제거, 객관식·짝짓기는 null(정답이 행에 있다).
     */
    private String normalizeAnswer(AdminProblemRequest r) {
        return switch (r.type()) {
            case MULTIPLE_CHOICE, MATCHING -> null;
            case OX -> r.answer().trim().toUpperCase();
            case SHORT_ANSWER -> r.answer().trim();
            // "3 | 2 | 1 | 4"처럼 사람이 보기 좋게 띄어 적어도 저장은 한 모양으로 눕힌다.
            // 채점 쪽도 공백을 지우고 비교하지만(gradeOrdering), 저장 시점에 눕혀 두면
            // DB를 눈으로 볼 때도 형식이 하나다.
            case ORDERING -> r.answer().replaceAll("\\s", "");
            case ESSAY -> null; // validateByType에서 이미 차단 — 도달 불가
        };
    }

    /**
     * 보기 목록 생성 — seq는 입력 순서대로 1..N 부여(관리자가 번호를 직접 관리하지 않게).
     *
     * <p><b>순서 배열의 seq가 곧 answer의 번호다.</b> 관리자가 "3|2|1|4"라고 적을 때의 3은
     * 입력 화면에서 <b>세 번째 줄</b>을 가리킨다. 그래서 여기서 부여하는 1..N이 그 번호와 같아야
     * 하고, 검증({@link #validateOrdering})도 같은 전제로 1..N의 순열인지 본다.
     */
    private List<Choice> buildChoices(Problem problem, AdminProblemRequest r) {
        List<Choice> choices = new ArrayList<>();
        if (r.type().usesChoiceRows()) {
            List<AdminProblemRequest.ChoiceItem> items = r.choices();
            for (int i = 0; i < items.size(); i++) {
                AdminProblemRequest.ChoiceItem item = items.get(i);
                choices.add(r.type() == ProblemType.MATCHING
                        ? Choice.pair(problem, item.text().trim(), item.matchText().trim(), i + 1)
                        : Choice.of(problem, item.text().trim(), item.correct(),
                                normalizeRationale(item), i + 1));
            }
        }
        return choices; // 행을 안 쓰는 유형이면 빈 리스트 → replaceChoices가 기존 보기를 정리
    }

    /**
     * 오답 설명 정규화 — 빈 값은 {@code null}로 눕히고, <b>정답 보기는 값이 와도 버린다</b>.
     *
     * <p><b>왜 정답 쪽을 버리나.</b> 정답의 근거는 {@code explanation}이 통째로 맡기로 한
     * 설계다({@code Choice.rationale} 주석). 모델이 규칙을 어기고 정답에도 한 줄 적어 보내면
     * 같은 말이 두 곳에 남고, 나중에 해설만 고친 문제가 <b>화면에서 앞뒤가 다른 말을 한다</b>.
     * 규칙을 어긴 입력을 그대로 저장해 두면 어긴 사실조차 보이지 않으므로 여기서 정리한다.
     *
     * <p>빈 문자열을 {@code null}로 눕히는 것은 화면의 판정 때문이다 — 옛 형식(설명이 아예
     * 없는 문제)과 새 형식을 가르는 기준이 "값이 있는 보기가 하나라도 있나"인데,
     * {@code ""}가 섞여 들어오면 <b>있는 것으로 세어져</b> 빈 칸이 그려진다.
     */
    private String normalizeRationale(AdminProblemRequest.ChoiceItem item) {
        if (item.correct() || item.rationale() == null || item.rationale().isBlank()) {
            return null;
        }
        return item.rationale().trim();
    }

    private String trimOrNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
