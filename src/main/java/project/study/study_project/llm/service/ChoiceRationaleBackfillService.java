package project.study.study_project.llm.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.llm.client.ClaudeRationaleGenerator;
import project.study.study_project.llm.client.GeneratedRationale;
import project.study.study_project.llm.client.RationaleGenerator;
import project.study.study_project.llm.dto.RationaleBackfillResponse;
import project.study.study_project.llm.support.ProblemItemRule;
import project.study.study_project.quiz.domain.Choice;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.repository.ProblemRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 오답 설명이 빠진 기존 문제의 보기에 "왜 틀렸는지"를 채운다 — 오답 설명 칸(V15)의 뒤처리.
 *
 * <p>{@link ProblemTitleBackfillService}(제목 칸 V13의 뒤처리)와 같은 자리에 같은 모양으로 둔다.
 * "왜 Flyway 마이그레이션이 아닌가 / 왜 일회용 스크립트가 아닌가"는 저 클래스 주석에 정리돼 있고
 * 답이 여기서도 같다 — 콘텐츠는 마이그레이션에 넣지 않고, 한 번 돌리고 지우면 다음에 같은 일이
 * 생겼을 때 아무 장치도 남지 않는다. 그리고 <b>또 생긴다</b>: 관리자 등록 폼의 설명 칸은 선택이고,
 * 모델도 가끔 한 보기를 빠뜨린다({@code ProblemItemRule}이 경고하는 그 경우).
 *
 * <h2>해설은 건드리지 않는다 — 측정하고 내린 결정이다</h2>
 *
 * <p>새 형식은 해설을 200~400자의 "왜 정답인지"로 좁히고 오답 이야기를 보기 쪽으로 옮긴다.
 * 그래서 처음에는 해설도 다시 쓰려 했는데, 실제로 26건을 재 보니 <b>오답을 언급하는 해설은
 * 4건뿐</b>이었다(초급 0/5, 중급 3/16, 고급 1/5). 나머지 22건은 이미 정답 근거만 적혀 있고
 * 길이만 길다.
 *
 * <p>22건의 멀쩡한 해설을 덮어쓰는 것은 되돌릴 수 없는 변경인데, 얻는 것은 길이뿐이다.
 * 그래서 이 서비스는 <b>더하기만 한다</b>. 4건은 {@code explanationsToCheck}로 화면에 이름만
 * 올리고 사람이 다듬는다 — 4건을 손으로 보는 값이 22건을 위험에 넣는 값보다 싸다.
 *
 * <h2>이 클래스가 실제로 하는 일은 "모델을 믿지 않는 것"이다</h2>
 *
 * <p>부르는 것 자체는 한 줄이고, 나머지는 전부 응답을 검사하는 코드다. 검사 넷 중 셋은
 * 제목 백필에서 값을 치르고 배운 것이고, 넷째는 이 작업에만 있다:
 * <ul>
 *   <li><b>짝짓기는 보기 id로</b> — 순서로 짝지으면 모델이 한 보기를 빠뜨린 순간 전부 한 칸씩
 *       밀리고, 그 상태는 오류를 내지 않는다({@link GeneratedRationale} 주석)
 *   <li><b>모르는 id는 버린다</b> — 요청하지 않은 보기에 설명이 붙는 것을 막는다.
 *       특히 <b>정답 보기</b>에 붙는 것을 막는다({@link Choice#fillRationaleIfAbsent})
 *   <li><b>덮어쓰지 않는다</b> — 판단은 엔티티에 맡긴다(같은 메서드)
 *   <li><b>보기를 번호로 가리키는 설명은 버린다</b> — 초안 검사와 같은 패턴을 쓴다
 *       ({@link ProblemItemRule#choiceNumberReferenceIn}). 이건 자르거나 고칠 수 없다:
 *       "②번과 달리"에서 번호만 지우면 문장이 무너진다. 통째로 버리면 그 보기는 설명이
 *       여전히 {@code null}이라 다음 실행이 다시 집어 온다
 *   <li><b>너무 긴 설명은 자른다</b> — 컬럼이 1000자라 넘치면 저장이 <b>실패</b>한다.
 *       한 건 때문에 채우기 전체가 롤백되는 것보다 잘라 넣고 검수자가 다듬는 편이 낫다
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChoiceRationaleBackfillService {

    /**
     * DB 컬럼 상한(V15의 VARCHAR(1000))에 맞춘 자르기 기준.
     *
     * <p>품질 기준인 {@link ProblemItemRule#RATIONALE_MIN}(30자)과 성격이 다른 값이다.
     * 저쪽은 "너무 짧으면 도움이 안 된다"는 <b>품질</b> 이야기이고, 여기서 막는 것은
     * <b>저장 실패</b>다. 짧은 설명은 그냥 넣는다 — 짧다고 버리면 그 보기는 영영 비어 있고,
     * 짧은 설명이라도 있는 편이 아무것도 없는 것보다 낫다.
     */
    private static final int COLUMN_MAX = 1000;

    /**
     * 해설이 오답을 <b>직접 언급하는가</b>를 재는 패턴 — 사람이 손봐야 할 문제를 골라내는 데만 쓴다.
     *
     * <p>이 패턴에 걸린 문제는 같은 말이 해설과 오답 설명 양쪽에 있게 된다. 학습자는 틀린 직후에
     * 두 곳을 잇달아 읽으므로 되풀이가 눈에 띈다.
     *
     * <p><b>느슨하게 잡았고, 그게 의도다.</b> 여기서 헛짚어도 손해가 "사람이 한 건 더 열어 본다"뿐이다
     * — 자동으로 고치는 것이 없기 때문이다. 반대로 놓치면 되풀이가 그대로 남는다.
     * {@code ProblemItemRule}의 검사들이 "오탐이 미탐보다 비싸다"는 원칙을 따르는 것과 <b>반대</b>인데,
     * 그 원칙은 <b>차단하는</b> 검사에 해당한다. 이건 이름만 올리는 검사다.
     */
    private static final Pattern EXPLANATION_MENTIONS_WRONG =
            Pattern.compile("오답|나머지 (보기|선택지)|틀린 (보기|선택지)");

    private final ProblemRepository problemRepository;
    private final RationaleGenerator rationaleGenerator;

    /**
     * 오답 설명이 빠진 문제를 최대 {@link ClaudeRationaleGenerator#BATCH_SIZE}건 골라 설명을 채운다.
     *
     * <p><b>한 번에 다 끝내지 않는다.</b> 남은 건수를 함께 돌려주므로 화면이 "또 남았다"를 보여 주고
     * 사람이 한 번 더 누르면 된다. 서버가 알아서 반복하지 않는 이유는 그 편이 안전하기 때문이다 —
     * 프롬프트가 잘못돼 이상한 설명이 나오고 있다면, 26건을 다 망친 뒤에 아는 것보다 10건에서
     * 멈추고 눈으로 확인하는 쪽이 낫다.
     *
     * <p><b>{@code @Transactional}이 Claude 호출을 감싸고 있는 것은 알고 한 선택이다.</b>
     * API를 기다리는 수십 초 동안 DB 커넥션을 물고 있는데, 이는 {@code LlmProblemService.generate}가
     * 일부러 피한 바로 그것이다. 여기서 허용한 이유는 <b>변경 감지로 저장하기 때문</b>이다 —
     * 조회한 보기가 영속 상태로 살아 있어야 설명을 써 넣는 것만으로 UPDATE가 나간다.
     * 트랜잭션을 쪼개면 보기 id로 다시 조회해야 한다. 관리자가 어쩌다 한 번 누르는 버튼이고
     * 동시 요청이 없으므로 커넥션 하나를 수십 초 물고 있는 값을 치를 만하다.
     * (매일 도는 배치였다면 반대로 판단했을 것이다 — 제목 백필에서 내린 것과 같은 결론이다.)
     */
    @Transactional
    public RationaleBackfillResponse backfill() {
        List<Problem> targets = problemRepository.findWithMissingRationale(
                PageRequest.of(0, ClaudeRationaleGenerator.BATCH_SIZE));
        if (targets.isEmpty()) {
            return new RationaleBackfillResponse(0, 0, 0, List.of(), List.of());
        }

        List<RationaleGenerator.ProblemWithoutRationale> request = targets.stream()
                .map(this::toRequest)
                .filter(p -> !p.wrongChoices().isEmpty()) // 채울 것이 없는 문제는 보내지 않는다(요금)
                .toList();
        List<GeneratedRationale> generated = rationaleGenerator.generateRationales(request);

        // 보기 id → 설명. 모델이 같은 id를 두 번 냈으면 <먼저 온 것>을 쓴다. 뒤엣것으로 덮으면
        // 어느 쪽이 쓰였는지가 응답 순서에 달려 매번 달라진다 — 재현되지 않는 결과가 가장 나쁘다.
        Map<Long, String> rationaleByChoiceId = generated.stream()
                .filter(r -> r.rationale() != null && !r.rationale().isBlank())
                .collect(Collectors.toMap(GeneratedRationale::choiceId, GeneratedRationale::rationale,
                        (first, duplicate) -> first));

        List<RationaleBackfillResponse.Filled> filled = new ArrayList<>();
        List<Long> toCheck = new ArrayList<>();
        for (Problem problem : targets) {
            boolean touched = false;
            for (Choice choice : problem.getChoices()) {
                String rationale = rationaleByChoiceId.get(choice.getId());
                if (rationale == null || refersToChoiceNumber(choice.getId(), rationale)) {
                    // 모델이 빠뜨렸거나 번호로 가리킨 건. 설명이 여전히 NULL이라 다음 실행이
                    // 다시 집어 온다 — 여기서 재시도하지 않아도 손실이 없는 구조다.
                    continue;
                }
                if (choice.fillRationaleIfAbsent(truncate(rationale))) { // 변경 감지로 커밋 시 UPDATE
                    filled.add(new RationaleBackfillResponse.Filled(
                            problem.getId(), choice.getId(), choice.getText(), choice.getRationale()));
                    touched = true;
                }
            }
            // 설명을 실제로 붙인 문제만 본다. 아무것도 안 붙은 문제의 해설을 손보라고 해 봐야
            // 되풀이될 상대가 없다.
            if (touched && mentionsWrongAnswers(problem.getExplanation())) {
                toCheck.add(problem.getId());
            }
        }

        // 남은 건수는 <다시 세기만> 한다. 빼지 않는다.
        //
        // 2026-08-28에 여기서 실제로 음수가 나왔다(-4, -6). 처음에는 제목 백필을 그대로 본떠
        // "커밋 전이라 방금 채운 것이 그대로 세어진다"고 보고 이번에 끝낸 문제 수를 뺐는데,
        // 그 전제가 틀렸다. JPQL 조회는 <실행 전에 자동으로 flush한다> — 보류 중인 변경이
        // 조회 대상 테이블과 겹치면 Hibernate가 UPDATE를 먼저 내보낸다. 그래서 이 count는
        // 이미 채운 것을 뺀 값이고, 거기서 또 빼면 두 번 빠진다.
        //
        // 커밋 전이라 다른 트랜잭션에는 안 보이지만, <이 트랜잭션 안에서는 보인다>는 것이
        // 요점이다. 원래대로 커넥션을 물고 있기로 한 선택(메서드 주석)이 여기서는 도움이 된다.
        long remaining = problemRepository.countWithMissingRationale();

        log.info("오답 설명 채우기: 대상 {}문제, 모델 응답 {}건, 채움 {}보기, 해설 확인 필요 {}건, 남음 {}문제",
                targets.size(), generated.size(), filled.size(), toCheck.size(), remaining);
        return new RationaleBackfillResponse(targets.size(), filled.size(), remaining, filled, toCheck);
    }

    /** 관리 화면이 카드를 보여 줄지 정하는 데 쓴다 — 0건이면 할 일이 없다. */
    @Transactional(readOnly = true)
    public long missingRationaleCount() {
        return problemRepository.countWithMissingRationale();
    }

    /* ── 도우미 ──────────────────────────────────────────────── */

    /**
     * 엔티티를 프롬프트에 실릴 값 객체로 옮긴다.
     *
     * <p><b>이미 설명이 있는 오답은 빼고 보낸다.</b> 보내 봐야 {@code fillRationaleIfAbsent}가
     * 버릴 값이고, 그동안 토큰 요금만 나간다. 세 오답 중 하나만 비어 있는 문제에서 차이가 난다.
     *
     * <p>정답 보기는 {@code correctChoiceText}로 따로 넘긴다 — 오답 목록에 섞으면 모델이
     * 정답에도 설명을 단다({@code ClaudeRationaleGenerator.buildPrompt} 주석).
     */
    private RationaleGenerator.ProblemWithoutRationale toRequest(Problem problem) {
        String correctText = problem.getChoices().stream()
                .filter(Choice::isCorrect)
                .map(Choice::getText)
                .findFirst()
                .orElse("(정답 보기가 없는 문제)"); // 있을 수 없지만, 여기서 터뜨릴 이유는 없다
        List<RationaleGenerator.ProblemWithoutRationale.WrongChoice> wrong = problem.getChoices().stream()
                .filter(c -> !c.isCorrect() && c.getRationale() == null)
                .map(c -> new RationaleGenerator.ProblemWithoutRationale.WrongChoice(c.getId(), c.getText()))
                .toList();
        return new RationaleGenerator.ProblemWithoutRationale(
                problem.getId(), problem.getQuestion(), problem.getExplanation(), correctText, wrong);
    }

    /**
     * 보기를 번호로 가리키는 설명인가 — 그러면 통째로 버린다.
     *
     * <p>고쳐 쓸 수 없다는 것이 요점이다. "②번과 달리 이쪽은…"에서 번호만 지우면 남는 문장이
     * 무너진다. 그리고 이 설명은 학습자 화면에서 <b>다시 섞인 순서</b> 옆에 붙으므로,
     * 어긋난 채로 나가면 오히려 없느니만 못하다.
     */
    private boolean refersToChoiceNumber(Long choiceId, String rationale) {
        String found = ProblemItemRule.choiceNumberReferenceIn(rationale);
        if (found == null) {
            return false;
        }
        log.warn("오답 설명이 보기를 번호로 가리켜 버린다: choiceId={}, 걸린 부분=\"{}\"", choiceId, found);
        return true;
    }

    /** 해설이 오답을 직접 언급하는가 — 사람이 다듬을 목록에 올릴지만 정한다(상수 주석 참고). */
    private boolean mentionsWrongAnswers(String explanation) {
        return explanation != null && EXPLANATION_MENTIONS_WRONG.matcher(explanation).find();
    }

    /** 컬럼 상한을 넘기면 자른다 — 한 건 때문에 채우기 전체가 롤백되는 것을 막는다(상수 주석 참고). */
    private String truncate(String rationale) {
        String trimmed = rationale.trim();
        if (trimmed.length() <= COLUMN_MAX) {
            return trimmed;
        }
        log.warn("오답 설명이 컬럼 상한을 넘어 자른다: {}자 → {}자", trimmed.length(), COLUMN_MAX);
        return trimmed.substring(0, COLUMN_MAX - 1) + "…";
    }
}
