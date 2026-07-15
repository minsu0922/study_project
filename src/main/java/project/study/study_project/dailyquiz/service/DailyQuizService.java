package project.study.study_project.dailyquiz.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.dailyquiz.domain.DailyQuiz;
import project.study.study_project.dailyquiz.domain.DailyQuizSource;
import project.study.study_project.dailyquiz.dto.DailyQuizResponse;
import project.study.study_project.dailyquiz.repository.DailyQuizRepository;
import project.study.study_project.global.common.Domain;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.domain.Submission;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.quiz.repository.SubmissionRepository;
import project.study.study_project.review.repository.ReviewItemRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 오늘의 퀴즈 서비스 — 지연 생성 + 배합(4/3/3) + 스트릭 계산 + 제출 반영. 스펙은 docs/12, 결정은 ADR-0005.
 *
 * <p><b>지연 생성(lazy)</b>: 세트는 자정 배치가 아니라 "그날 첫 조회" 때 만든다.
 * 이 앱은 로컬 PC에서 돌아 자정에 꺼져 있을 수 있는데, 배치 방식이면 그날 세트가
 * 영영 없다 — 지연 생성은 스케줄러라는 부품 자체를 없앤다(ADR-0005).
 *
 * <p><b>배합 레시피(docs/12)</b>: 복습 4(사다리 due) + 취약 3(내 정답률 하위 도메인) +
 * 새 문제 3(미풀이). 각 칸의 부족분은 새 문제가 흡수하고, 새 문제도 바닥나면
 * GENERAL(기출 무작위)로 채우되, 그래도 모자라면 작은 세트를 허용한다 —
 * 개수를 맞추려고 같은 문제를 두 번 넣는 것보다 "오늘은 7개"가 정직하다.
 */
@Service
@RequiredArgsConstructor
public class DailyQuizService {

    /** 세트 크기와 배합 목표(복습/취약/새 문제). 값 튜닝은 상수 수정으로 끝난다(ReviewService.INTERVAL_DAYS와 같은 판단). */
    public static final int SET_SIZE = 10;
    public static final int REVIEW_TARGET = 4;
    public static final int WEAK_TARGET = 3;

    /** 취약 판정 최소 제출 수 — 이 미만인 도메인은 표본 부족으로 판정에서 제외(docs/12). */
    public static final int MIN_SUBMISSIONS_FOR_WEAK = 5;

    private final DailyQuizRepository dailyQuizRepository;
    private final ReviewItemRepository reviewItemRepository;
    private final SubmissionRepository submissionRepository;
    private final ProblemRepository problemRepository;

    /**
     * 오늘 세트 조회 — 없으면 만들어서 반환(멱등). GET /api/me/daily-quiz의 본체.
     *
     * <p>쓰기 트랜잭션인 이유: "없으면 생성"이 조회 안에 들어 있다. 조회/생성을 메서드로
     * 쪼개면 트랜잭션 경계(프록시 self-invocation) 문제가 생겨, 한 트랜잭션으로 묶는 쪽이 단순하다.
     *
     * <p><b>동시 첫 조회 경합</b>(탭 2개가 같은 순간 생성 시도): 중복 세트를 막는 건 이 코드가
     * 아니라 DB의 UNIQUE(user_id, quiz_date)다. 진 쪽 트랜잭션은 제약 위반으로 실패하는데,
     * 그 재시도(승자가 만든 세트 다시 읽기)는 트랜잭션이 끝난 뒤에만 가능하므로 경계 밖인
     * 컨트롤러가 담당한다(DailyQuizController 주석 참고).
     */
    @Transactional
    public DailyQuizResponse getToday(Long userId) {
        LocalDate today = LocalDate.now();
        DailyQuiz quiz = dailyQuizRepository.findWithItems(userId, today)
                .orElseGet(() -> generate(userId, today));
        return DailyQuizResponse.from(quiz, calcStreak(userId, today));
    }

    /**
     * 채점된 제출을 오늘 세트에 반영한다 — QuizService.submit()이 Submission 저장 직후 호출.
     *
     * <p>{@code Propagation.MANDATORY}: 반드시 호출자(submit)의 트랜잭션에 합류만 한다 —
     * "채점·이력은 남았는데 세트 진행률만 안 바뀐" 어중간한 상태를 금지(ReviewService.onSubmission과
     * 같은 패턴). 반영 자체는 애그리거트 루트(DailyQuiz.connectSubmission)에 위임하고,
     * 변경은 dirty checking으로 커밋 시 반영된다.
     *
     * <p>오늘 세트가 없으면(세트를 만들기 전에 일반 퀴즈부터 푼 경우) 조용히 무시한다 —
     * 여기서 세트를 만들어 주면 "풀지도 않은 날의 세트"가 생긴다. 세트 생성은 조회 경로의 책임.
     * 반대로 세트 문제를 세트 밖(일반 퀴즈·복습 페이지)에서 풀어도 반영된다 — 사용자 입장에서
     * "아까 풀었는데 왜 미완료지?"가 더 이상하다(docs/12).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onSubmission(Long userId, Submission submission) {
        dailyQuizRepository.findByUserIdAndQuizDate(userId, LocalDate.now())
                .ifPresent(quiz -> quiz.connectSubmission(submission, LocalDateTime.now()));
    }

    /**
     * 세트 생성 — 배합 레시피(4/3/3)대로 뽑아 저장한다. 흐름:
     * ① 복습 칸: 사다리 due를 오래 밀린 순으로 최대 4개(복습 페이지와 같은 쿼리·같은 우선순위)
     * ② 취약 칸: 정답률 낮은 도메인부터 최대 3개(문제가 모자라면 다음 취약 도메인에서 이어 뽑음)
     * ③ 새 문제 칸: 남은 자리 전부(목표 3 + 앞 칸들의 부족분 흡수)
     * ④ 채움 칸: 새 문제도 바닥났으면 기출 무작위. 그래도 모자라면 작은 세트로 확정.
     */
    private DailyQuiz generate(Long userId, LocalDate today) {
        // LinkedHashMap<문제 id, 뽑힌 칸> — 순서 보존 + "이미 뽑힌 문제" 중복 검사를 겸한다.
        Map<Problem, DailyQuizSource> picks = new LinkedHashMap<>();

        // ① 복습 칸
        reviewItemRepository.findDue(userId, LocalDateTime.now(), PageRequest.of(0, REVIEW_TARGET))
                .forEach(item -> picks.put(item.getProblem(), DailyQuizSource.REVIEW));

        // ② 취약 칸 — 가장 약한 도메인부터 순회하며 채운다.
        int weakNeed = WEAK_TARGET;
        for (Domain domain : weakDomainsOrderedByAccuracy(userId)) {
            if (weakNeed <= 0) break;
            List<Problem> found = problemRepository.findRandomExcluding(
                    domain.name(), excludeIds(picks), weakNeed);
            found.forEach(p -> picks.put(p, DailyQuizSource.WEAK));
            weakNeed -= found.size();
        }

        // ③ 새 문제 칸 — 남은 자리 전부를 새 문제에 준다. 복습·취약이 목표를 못 채웠으면
        //    그 부족분이 자연히 여기 합산된다(docs/12 "부족분은 새 문제가 흡수").
        int remaining = SET_SIZE - picks.size();
        if (remaining > 0) {
            problemRepository.findRandomUnsolved(userId, excludeIds(picks), remaining)
                    .forEach(p -> picks.put(p, DailyQuizSource.NEW));
        }

        // ④ 채움 칸 — 새 문제 풀 고갈(전부 풀어봄). 기출 무작위로 메꾼다.
        //    여기가 자주 실행되면 문제 공급(B안·LLM 생성)을 논의할 때다(docs/12 재검토 트리거).
        remaining = SET_SIZE - picks.size();
        if (remaining > 0) {
            problemRepository.findRandomExcluding(null, excludeIds(picks), remaining)
                    .forEach(p -> picks.put(p, DailyQuizSource.GENERAL));
        }

        // 순서 셔플 — 뽑은 순서(복습 4개가 항상 선두)로 내면 세트 앞부분이 "복습 코너"처럼
        // 굳는다. 매일 다른 흐름이 퀴즈답고, 배합 정보는 source에 남아 있으니 잃는 것도 없다.
        List<Map.Entry<Problem, DailyQuizSource>> shuffled = new ArrayList<>(picks.entrySet());
        Collections.shuffle(shuffled);

        DailyQuiz quiz = DailyQuiz.of(userId, today);
        shuffled.forEach(e -> quiz.addItem(e.getKey(), e.getValue()));
        // CASCADE로 항목까지 한 번에 INSERT. 여기서 UNIQUE 위반이 나면(동시 생성 경합의 패자)
        // 예외가 컨트롤러까지 올라가 재조회로 처리된다.
        return dailyQuizRepository.save(quiz);
    }

    /**
     * 취약 도메인을 정답률 오름차순(가장 약한 것부터)으로 — 표본 부족(제출 5회 미만) 도메인은
     * 쿼리의 HAVING이 이미 걸렀다. 정렬을 자바에서 하는 이유는 리포지토리 주석 참고(최대 10행).
     */
    private List<Domain> weakDomainsOrderedByAccuracy(Long userId) {
        return submissionRepository.aggregateUserDomainStats(userId, MIN_SUBMISSIONS_FOR_WEAK).stream()
                .sorted(Comparator.comparingDouble(s -> (double) s.getCorrectCount() / s.getTotal()))
                .map(SubmissionRepository.UserDomainStat::getDomain)
                .toList();
    }

    /**
     * NOT IN에 넘길 제외 id 목록. 비어 있으면 {@code NOT IN ()} SQL 문법 오류가 나므로
     * 존재하지 않는 id(-1)를 채워 "아무것도 제외 안 함"을 표현한다(ProblemRepository 규약).
     */
    private List<Long> excludeIds(Map<Problem, DailyQuizSource> picks) {
        if (picks.isEmpty()) {
            return List.of(-1L);
        }
        return picks.keySet().stream().map(Problem::getId).toList();
    }

    /**
     * 스트릭(연속 완료 일수) — 저장하지 않고 조회 시점에 계산한다(파생값 원칙, docs/12).
     *
     * <p>규칙: 오늘부터, 오늘이 미완료면 어제부터 거슬러 내려가며 연속으로 완료된 날을 센다.
     * "오늘 미완료여도 어제까지 이어져 있으면 살아 있다" — 아직 안 풀었다고 아침부터
     * 스트릭 0을 보여주면 의욕이 꺾인다(오늘 풀면 +1이 되는 상태).
     */
    private int calcStreak(Long userId, LocalDate today) {
        List<LocalDate> dates = dailyQuizRepository.findCompletedDatesDesc(userId);
        if (dates.isEmpty()) {
            return 0;
        }
        // 시작점: 최신 완료일이 오늘이면 오늘부터, 어제면 어제부터. 그보다 오래됐으면 이미 끊긴 것.
        LocalDate cursor = dates.get(0);
        if (cursor.isBefore(today.minusDays(1))) {
            return 0;
        }
        int streak = 0;
        for (LocalDate date : dates) { // 최신순 + 하루 1행(UNIQUE) 보장이라 단순 순회로 충분
            if (!date.equals(cursor)) {
                break; // 하루라도 구멍 → 연속 끝
            }
            streak++;
            cursor = cursor.minusDays(1);
        }
        return streak;
    }
}
