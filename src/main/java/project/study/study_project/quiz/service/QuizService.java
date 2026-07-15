package project.study.study_project.quiz.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.dailyquiz.service.DailyQuizService;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.quiz.domain.Choice;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.domain.Submission;
import project.study.study_project.quiz.dto.QuizProblemItem;
import project.study.study_project.quiz.dto.QuizResponse;
import project.study.study_project.quiz.dto.QuizSubmitRequest;
import project.study.study_project.quiz.dto.QuizSubmitResponse;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.quiz.repository.SubmissionRepository;
import project.study.study_project.review.service.ReviewService;

import java.util.Arrays;
import java.util.List;

/**
 * 퀴즈 서비스 — 문제 무작위 조회와 답안 채점. 스펙은 docs/03, 채점 규칙은 docs/01.
 *
 * <p>조회 메서드가 {@code @Transactional(readOnly = true)}인 이유는 문서 서비스와 동일:
 * open-in-view=false라 LAZY 컬렉션(객관식 보기)은 트랜잭션 안에서만 읽을 수 있으므로,
 * DTO 변환까지 트랜잭션 경계 안에서 끝낸다.
 */
@Service
@RequiredArgsConstructor
public class QuizService {

    /** 스펙(docs/03)의 size 기본값/상한. 컨트롤러가 아닌 여기 두는 이유: 정책은 서비스 책임. */
    public static final int DEFAULT_SIZE = 10;
    private static final int MAX_SIZE = 50;

    private final ProblemRepository problemRepository;
    private final SubmissionRepository submissionRepository;
    private final ReviewService reviewService;
    private final DailyQuizService dailyQuizService;

    /**
     * 필터로 문제 N개 무작위 조회(풀이용 — 정답/해설 미포함).
     *
     * @param type ESSAY(서술형)를 요청하면 {@link ErrorCode#QUIZ_002}(400).
     *             서술형은 MVP 자동채점 대상이 아니라 풀 수 없는 문제를 내려주면 안 되기 때문.
     * @param size 요청 개수. 1~50으로 보정(clamp) — 범위 밖이면 에러 대신 조용히 경계값으로 맞춘다.
     *             조회 API에서 "51개 요청"은 악의보다 실수에 가까워, 굳이 400으로 튕겨
     *             클라이언트 재시도를 강제할 이유가 없다고 판단(트레이드오프: 명시성 ↓, 편의성 ↑).
     */
    @Transactional(readOnly = true)
    public QuizResponse getQuiz(Domain domain, Difficulty level, ProblemType type, int size) {
        if (type != null && !type.isAutoScored()) {
            throw new BusinessException(ErrorCode.QUIZ_002);
        }
        int limit = Math.min(Math.max(size, 1), MAX_SIZE);

        // 네이티브 쿼리는 enum을 자동 변환하지 못하므로 name() 문자열로 넘긴다(리포지토리 주석 참고).
        List<Problem> problems = problemRepository.findRandomForQuiz(
                domain == null ? null : domain.name(),
                level == null ? null : level.name(),
                type == null ? null : type.name(),
                limit
        );
        return new QuizResponse(problems.stream().map(QuizProblemItem::from).toList());
    }

    /**
     * 답안 제출 → 즉시 채점 → 이력 저장 → 정답·해설 반환. (docs/03 POST /api/quiz/submit)
     *
     * <p>정답이든 오답이든 <b>Submission은 항상 저장</b>한다 — 오답만 저장하면 "몇 번 만에
     * 맞혔는지" 같은 학습 이력을 잃고, 오답노트가 "다시 풀어서 맞힌 문제"를 구분할 수 없게 된다.
     *
     * <p>쓰기 트랜잭션(readOnly 아님): 채점(읽기)과 저장(쓰기)을 한 트랜잭션으로 묶어
     * "채점은 됐는데 이력은 안 남는" 어중간한 상태를 방지한다.
     *
     * @param userId JWT에서 꺼낸 제출자 id(컨트롤러의 @AuthenticationPrincipal)
     */
    @Transactional
    public QuizSubmitResponse submit(Long userId, QuizSubmitRequest request) {
        Problem problem = problemRepository.findById(request.problemId())
                .orElseThrow(() -> new BusinessException(ErrorCode.QUIZ_001));
        // ESSAY 등 자동채점 불가 타입 방어. GET /api/quiz가 ESSAY를 안 내려주지만,
        // problemId는 클라이언트가 임의로 보낼 수 있으므로 제출 쪽에서도 반드시 다시 검사한다.
        if (!problem.getType().isAutoScored()) {
            throw new BusinessException(ErrorCode.QUIZ_002);
        }

        GradingResult result = grade(problem, request.userAnswer());

        Submission submission = submissionRepository.save(
                Submission.of(userId, problem, request.userAnswer(), result.correct()));

        // 복습 사다리 반영(로드맵 4, docs/10) — 같은 트랜잭션에 합류시켜 "이력은 남았는데
        // 복습 상태만 안 바뀐" 상태를 원천 차단. 별도 복습 제출 API 없이 이 한 곳이
        // ReviewItem의 유일한 쓰기 경로다(갱신 규칙의 정합성 관리 지점 최소화).
        reviewService.onSubmission(userId, problem, result.correct());

        // 오늘의 퀴즈 세트 반영(로드맵 6, docs/12) — 같은 이유로 같은 트랜잭션에 합류.
        // 세트에 없는 문제면 서비스가 조용히 무시하므로 일반 풀이 경로에 영향 없다.
        dailyQuizService.onSubmission(userId, submission);

        return new QuizSubmitResponse(
                problem.getId(), result.correct(), result.correctAnswer(),
                problem.getExplanation(), submission.getId());
    }

    /** 채점 결과 — 정오 여부 + 사람이 읽는 정답 표기. 서비스 내부 전용이라 private record. */
    private record GradingResult(boolean correct, String correctAnswer) {
    }

    /**
     * 타입별 채점 분기 — 규칙 표는 docs/01 "채점 로직 요약"과 1:1.
     *
     * <p>switch식에 default가 없는 이유: enum 전체(ESSAY 포함)를 나열하면 컴파일러가
     * 누락을 잡아준다. 나중에 타입이 추가되면 여기서 컴파일 에러가 나서 채점 규칙을
     * 빠뜨린 채 배포하는 사고를 막는다. (ESSAY는 위에서 이미 걸러졌으므로 도달 불가)
     */
    private GradingResult grade(Problem problem, String userAnswer) {
        return switch (problem.getType()) {
            case MULTIPLE_CHOICE -> gradeMultipleChoice(problem, userAnswer);
            case OX -> gradeOx(problem, userAnswer);
            case SHORT_ANSWER -> gradeShortAnswer(problem, userAnswer);
            case ESSAY -> throw new BusinessException(ErrorCode.QUIZ_002); // 도달 불가(위에서 차단)
        };
    }

    /**
     * 객관식: userAnswer = 선택한 choiceId 문자열. 해당 보기의 is_correct로 판정.
     *
     * <p>보기 id가 숫자가 아니거나 <b>이 문제의 보기가 아니면 "오답"이 아니라 400(COMMON_001)</b>이다.
     * 사용자는 화면의 보기 중에서 고를 뿐이라 잘못된 id가 올 수 없다 — 온다면 그건 사용자의
     * 실수가 아니라 클라이언트 버그이므로, 조용히 오답 처리해 이력을 오염시키는 대신 에러로 알린다.
     */
    private GradingResult gradeMultipleChoice(Problem problem, String userAnswer) {
        long choiceId;
        try {
            choiceId = Long.parseLong(userAnswer.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.COMMON_001);
        }
        Choice selected = problem.getChoices().stream()
                .filter(c -> c.getId() == choiceId)
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMON_001));
        // 정답 표기(정답 보기의 text)는 오답노트와 공용 규칙인 AnswerDisplay에 위임
        return new GradingResult(selected.isCorrect(), AnswerDisplay.correctAnswerOf(problem));
    }

    /**
     * OX: 대소문자 무시 비교(docs/01). "o"도 정답 처리.
     * "O/X 외의 입력"(예 "0", "yes")은 에러가 아니라 <b>그냥 오답</b> — 단답형과 마찬가지로
     * 사용자가 직접 입력할 수 있는 값이므로, 틀린 입력은 틀린 답으로 취급하는 것이 자연스럽다.
     */
    private GradingResult gradeOx(Problem problem, String userAnswer) {
        boolean correct = problem.getAnswer().equalsIgnoreCase(userAnswer.trim());
        return new GradingResult(correct, AnswerDisplay.correctAnswerOf(problem));
    }

    /**
     * 단답형: {@code answer}의 {@code |} 구분 복수 정답 중 하나와
     * {@code trim + toLowerCase} 정규화 후 일치하면 정답(docs/01).
     * 대표 정답(응답 표기)은 첫 번째 토큰(docs/03) — AnswerDisplay가 담당.
     */
    private GradingResult gradeShortAnswer(Problem problem, String userAnswer) {
        String normalized = userAnswer.trim().toLowerCase();
        boolean correct = Arrays.stream(problem.getAnswer().split("\\|")) // |는 정규식 메타문자라 이스케이프
                .anyMatch(a -> a.trim().toLowerCase().equals(normalized));
        return new GradingResult(correct, AnswerDisplay.correctAnswerOf(problem));
    }
}
