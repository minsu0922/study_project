package project.study.study_project.quiz.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.dailyquiz.service.DailyQuizService;
import project.study.study_project.document.repository.DocumentRepository;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.quiz.domain.Choice;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.domain.Submission;
import project.study.study_project.quiz.dto.QuizChoiceResult;
import project.study.study_project.quiz.dto.QuizProblemItem;
import project.study.study_project.quiz.dto.QuizResponse;
import project.study.study_project.quiz.dto.QuizSubmitRequest;
import project.study.study_project.quiz.dto.QuizSubmitResponse;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.quiz.repository.SubmissionRepository;
import project.study.study_project.quiz.support.MatchToken;
import project.study.study_project.review.service.ReviewService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
    /** 근거 문서 링크의 실재 확인용(docs/15 3단계) — 죽은 링크를 학습자에게 보여주지 않기 위해. */
    private final DocumentRepository documentRepository;

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
     * 문제 하나만 — 목록 화면에서 <b>이걸 풀자</b>고 눌러 들어올 때(docs/18, 2026-08-29).
     *
     * <p><b>왜 필요했나.</b> 문제 목록은 "다음에 풀 문제 하나를 고르게 하는" 화면인데, 정작
     * 고른 뒤에 갈 곳이 없었다. {@code /api/quiz}는 무작위 세트만 주므로 목록에서 3번 문제를
     * 눌러도 엉뚱한 열 문제가 나온다 — 화면을 만들어 놓고 링크만 죽어 있는 상태가 된다.
     *
     * <p>응답을 {@link QuizResponse}(목록)로 감싸는 것은 의도다. 풀이 화면은 이미 "문제 배열"을
     * 받아 도는 구조라, 한 건짜리 전용 형태를 새로 만들면 화면에 분기가 하나 생긴다.
     * 한 칸짜리 세트로 주면 기존 흐름을 그대로 탄다.
     *
     * <p>ESSAY는 거절한다. 자동 채점 대상이 아니라 풀이 화면이 채점을 못 하는데, 목록에는
     * 관리자가 손으로 넣은 서술형이 섞일 수 있다(관리 화면에는 유형 제한이 없다).
     *
     * @throws BusinessException 없는 id면 QUIZ_001, 서술형이면 QUIZ_002
     */
    @Transactional(readOnly = true)
    public QuizResponse getOne(Long problemId) {
        Problem problem = problemRepository.findById(problemId)
                .orElseThrow(() -> new BusinessException(ErrorCode.QUIZ_001));
        if (!problem.getType().isAutoScored()) {
            throw new BusinessException(ErrorCode.QUIZ_002);
        }
        return new QuizResponse(List.of(QuizProblemItem.from(problem)));
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

        // 보기별 결과는 객관식에만 있다. OX·단답형에서 빈 목록을 내리는 것은 null보다 낫다 —
        // 화면이 유형마다 다른 검사를 하지 않고 "비었으면 안 그린다" 하나로 끝난다.
        // (구조화 출력 스키마가 "값 없음"을 빈 배열로 받는 것과 같은 규약이다.)
        List<QuizChoiceResult> choiceResults = problem.getType() == ProblemType.MULTIPLE_CHOICE
                ? QuizChoiceResult.from(problem.getChoices())
                : List.of();

        return new QuizSubmitResponse(
                problem.getId(), result.correct(), result.correctAnswer(),
                problem.getExplanation(), submission.getId(),
                existingDocumentSlug(problem.getDocumentSlug()), choiceResults);
    }

    /**
     * 근거 문서 slug를 <b>실제로 존재할 때만</b> 돌려준다(docs/15 3단계).
     *
     * <p>문제의 {@code document_slug}는 FK가 아니라 이름표라 가리키는 문서가 없을 수 있다.
     * 가장 흔한 경우는 <b>문제는 승인했는데 근거 문서는 아직 검수 대기</b>인 상황 —
     * 검수 순서를 강제하지 않으므로 정상적으로 자주 생긴다. 확인 없이 내려보내면
     * 학습자가 링크를 눌렀을 때 "문서를 찾을 수 없습니다"를 만난다.
     *
     * <p>slug가 없는 문제(대부분의 기존 문제)는 조회 자체를 하지 않는다 — 값이 없는데
     * 쿼리를 날리면 제출마다 쓸데없는 왕복이 한 번씩 늘어난다.
     */
    private String existingDocumentSlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return null;
        }
        return documentRepository.findExistingSlugs(List.of(slug)).isEmpty() ? null : slug;
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
            case MATCHING -> gradeMatching(problem, userAnswer);
            case ORDERING -> gradeOrdering(problem, userAnswer);
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

    /**
     * 순서 배열: userAnswer = 학습자가 배열한 <b>보기 id</b>를 순서대로 {@code |}로 이은 것
     * (예 {@code "12|9|11|10"}). 그 id들을 seq로 바꿔 {@code problem.answer}("3|2|1|4")와 대조한다.
     *
     * <p><b>왜 정답은 seq, 제출은 id인가.</b> 화면의 보기 번호는 요청마다 다시 매겨지므로
     * ({@code QuizChoiceItem.shuffledFrom}) 채점 기준이 될 수 없고, 학습자가 확실히 알 수 있는
     * 것은 보기 id뿐이다. 반대로 정답은 화면과 무관하게 DB 안에서 고정돼야 하는데, id는 문제를
     * 지웠다 다시 등록하면 바뀐다. 그래서 <b>바깥에서 들어오는 것은 id, 안에 적어 두는 것은 seq</b>다.
     *
     * <p><b>개수가 다르거나 모르는 id가 오면 오답이 아니라 400</b>이다 — 객관식과 같은 판단이다
     * ({@link #gradeMultipleChoice} 주석). 학습자는 화면의 항목을 전부 배열해야 제출 버튼을 누를
     * 수 있으므로, 어긋난 제출은 사용자의 실수가 아니라 클라이언트 버그다. 조용히 오답으로
     * 처리하면 <b>복습 사다리와 오답노트가 그 버그만큼 오염된다</b>.
     *
     * <p><b>부분 정답은 주지 않는다.</b> {@code Submission.correct}가 boolean이라 "네 칸 중 셋"을
     * 담을 자리가 없다. 다만 제출 원문이 {@code user_answer}에 남으므로, 나중에 부분 채점이
     * 필요해지면 지나간 제출까지 거슬러 분석할 수 있다(지금 버리지 않는 것이 요점이다).
     */
    private GradingResult gradeOrdering(Problem problem, String userAnswer) {
        List<Choice> arranged = choicesByIdOrder(problem, userAnswer.split("\\|"));
        String submittedSeqOrder = arranged.stream()
                .map(c -> String.valueOf(c.getSeq()))
                .collect(Collectors.joining("|"));
        // 정답 문자열에 사람이 넣은 공백("3 | 2")이 있어도 같게 보이도록 공백만 지우고 비교한다.
        String expected = problem.getAnswer().replaceAll("\\s", "");
        return new GradingResult(expected.equals(submittedSeqOrder),
                AnswerDisplay.correctAnswerOf(problem));
    }

    /**
     * 짝짓기: userAnswer = {@code "왼쪽보기id-오른쪽토큰"} 쌍을 {@code |}로 이은 것
     * (예 {@code "12-a3f19c024b71|9-77bc0e5d1a3f"}). 네 쌍이 <b>모두</b> 맞아야 정답이다.
     *
     * <p><b>정답은 {@code answer}가 아니라 행에 있다</b>(V16). 왼쪽 보기를 id로 찾아 그 행의
     * {@code matchText}로 토큰을 다시 계산하고, 학습자가 보낸 토큰과 같은지 본다. 서버가 섞은
     * 순서를 기억할 필요가 없는 이유는 {@link MatchToken} 주석에 있다.
     *
     * <p><b>순서는 보지 않는다.</b> 학습자가 어느 쌍부터 이었는지는 채점과 무관하다 —
     * 아래에서 왼쪽 id로 행을 찾아 각 쌍을 독립적으로 판정하므로 자연히 순서에 영향받지 않는다.
     */
    private GradingResult gradeMatching(Problem problem, String userAnswer) {
        String[] entries = userAnswer.split("\\|");
        if (entries.length != problem.getChoices().size()) {
            throw new BusinessException(ErrorCode.COMMON_001);
        }

        Set<Long> seenLeft = new HashSet<>();
        boolean allCorrect = true;
        for (String entry : entries) {
            int dash = entry.indexOf('-');
            if (dash < 0) {
                throw new BusinessException(ErrorCode.COMMON_001);
            }
            Choice left = choiceById(problem, entry.substring(0, dash).trim());
            // 같은 왼쪽 항목을 두 번 이은 제출 — 화면에서 나올 수 없는 모양이라 클라이언트 버그다.
            if (!seenLeft.add(left.getId())) {
                throw new BusinessException(ErrorCode.COMMON_001);
            }
            String expectedToken = MatchToken.of(problem.getId(), left.getMatchText());
            // 한 쌍이 어긋나도 끝까지 돈다 — 남은 쌍에 잘못된 id가 섞여 있으면 그건 400으로
            // 알려야 하는 상태이고, 여기서 일찍 빠져나오면 그 검사를 건너뛴다.
            if (!entry.substring(dash + 1).trim().equals(expectedToken)) {
                allCorrect = false;
            }
        }
        return new GradingResult(allCorrect, AnswerDisplay.correctAnswerOf(problem));
    }

    /** 제출된 id 배열을 보기 행으로 — 개수·중복·소속을 모두 검사한다(어긋나면 400). */
    private List<Choice> choicesByIdOrder(Problem problem, String[] rawIds) {
        if (rawIds.length != problem.getChoices().size()) {
            throw new BusinessException(ErrorCode.COMMON_001);
        }
        List<Choice> found = new ArrayList<>(rawIds.length);
        Set<Long> seen = new HashSet<>();
        for (String rawId : rawIds) {
            Choice choice = choiceById(problem, rawId.trim());
            if (!seen.add(choice.getId())) {
                throw new BusinessException(ErrorCode.COMMON_001); // 같은 항목을 두 번 배열했다
            }
            found.add(choice);
        }
        return found;
    }

    /** 이 문제의 보기 중 그 id를 가진 행. 숫자가 아니거나 남의 보기면 400(객관식과 같은 규칙). */
    private Choice choiceById(Problem problem, String rawId) {
        long id;
        try {
            id = Long.parseLong(rawId);
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.COMMON_001);
        }
        return problem.getChoices().stream()
                .filter(c -> c.getId() == id)
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMON_001));
    }
}
