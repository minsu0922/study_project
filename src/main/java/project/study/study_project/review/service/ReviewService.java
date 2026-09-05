package project.study.study_project.review.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.global.response.PageResponse;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.review.domain.ReviewItem;
import project.study.study_project.review.domain.ReviewStatus;
import project.study.study_project.review.dto.ReviewListItem;
import project.study.study_project.review.dto.ReviewTodayItem;
import project.study.study_project.review.repository.ReviewItemRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * 복습 추천 서비스 — 간격 사다리 상태 전이 + 복습 목록 조회. 스펙은 docs/10, 결정은 ADR-0004.
 *
 * <p><b>간격 사다리(망각곡선)</b>: 복습할 때마다 잊는 속도가 느려지므로 복습 간격을
 * 1 → 3 → 7 → 14 → 30일로 점점 벌린다. 틀리면 처음(1일)으로 리셋, 마지막 칸에서 맞히면 졸업.
 * 여기서 "며칠 뒤"는 24시간 단위가 아니라 <b>학습일</b> 단위다({@link #dueAfter} 참고).
 *
 * <p><b>쓰기 경로는 {@link #onSubmission} 하나뿐</b> — 별도 "복습 제출" API를 만들지 않고
 * 기존 채점 경로(QuizService.submit)가 이 메서드를 부른다. 채점 경로가 하나면 ReviewItem
 * 갱신 규칙도 한 곳에만 존재한다(정합성 관리 지점 최소화, docs/10).
 */
@Service
@RequiredArgsConstructor
public class ReviewService {

    /**
     * 간격 사다리 — index가 stage, 값이 "그 칸에서 다음 복습까지 며칠"인지다(docs/10, ADR-0004).
     * SM-2 같은 적응형 알고리즘 대신 고정 배열인 이유: 자가 평가 입력(얼마나 쉬웠는지)이 없는
     * MVP에서는 조절할 근거 데이터가 없다. 값 변경은 이 상수 수정으로 끝난다(ADR 재검토 대상 아님).
     * public인 이유: 테스트가 기대 간격을 여기서 읽는다 — 사다리 값을 바꾸면 테스트도 따라온다.
     */
    public static final int[] INTERVAL_DAYS = {1, 3, 7, 14, 30};

    /**
     * 학습일이 바뀌는 기준 시각 — 새벽 4시.
     *
     * <p><b>왜 자정이 아닌가.</b> 밤 12시를 넘겨 공부하는 사람이 있다. 새벽 1시에 푼 것을
     * 벌써 "다음 날"로 세면 그 사람은 한자리에 앉아 이틀 치 복습을 받게 된다. 경계를 새벽
     * 4시에 두면 자정을 넘긴 공부가 전날 몫으로 남는다(간격 반복 도구들의 관행).
     */
    private static final LocalTime DAY_ROLLOVER = LocalTime.of(4, 0);

    /** 목록 조회 size 상한(docs/10). 정책은 서비스 책임(QuizService.MAX_SIZE와 같은 판단). */
    private static final int MAX_SIZE = 50;

    private final ReviewItemRepository reviewItemRepository;

    /**
     * "지금부터 {@code days}일 뒤"의 복습 예정 시각 — <b>시각이 아니라 학습일로 센다</b>.
     *
     * <p><b>왜 {@code now.plusDays(days)}가 아닌가(2026-09-05 수정).</b> 그건 정확히 24시간
     * 뒤다. 밤 10시에 틀린 문제는 다음 날 밤 10시에야 복습 목록에 오른다. 그런데 사람은
     * 대개 비슷한 시간대에 공부하므로, 매일 저녁 9시에 들어오는 사용자는 <b>거의 매번</b>
     * "오늘 복습할 문제가 없어요"를 보게 된다 — 어제 틀린 문제가 한 시간 차이로 안 뜬다.
     * 간격 반복에서 "하루"는 24시간이 아니라 <b>공부하는 하루</b>여야 한다.
     *
     * <p>그래서 지금이 어느 학습일인지 먼저 정하고({@link #DAY_ROLLOVER} 기준), 거기에
     * {@code days}를 더한 날이 열리는 시각(새벽 4시)을 예정 시각으로 삼는다. 그 결과 실제
     * 간격은 {@code days-1}일 남짓에서 {@code days}일 사이로 <b>짧아지는 쪽</b>으로 흔들린다.
     * 늦은 밤에 풀수록 짧아지는 셈인데(밤 11시 → 5시간 뒤), 하루가 통째로 밀려 복습을 아예
     * 못 하는 것보다 몇 시간 이른 편이 낫다고 봤다. 간격의 정확도보다 "들어오면 할 게 있다"가
     * 이 기능의 값어치다.
     *
     * <p><b>{@code static} 순수 함수인 이유</b>: 경계값(밤 11시·새벽 1시·4시 정각)은 안에서
     * {@code now()}를 부르는 한 테스트할 수 없다. {@code Clock} 빈을 주입하는 방법도 있지만,
     * 시각을 인자로 받게 하면 빈 구성이라는 비용 없이 테스트가 원하는 시각을 직접 넣는다.
     * {@code public}인 이유는 {@link #INTERVAL_DAYS}와 같다 — 통합 테스트가 기대값을 여기서
     * 얻어 가므로, 규칙을 바꾸면 테스트가 저절로 따라온다.
     *
     * @param now  기준 시각(보통 {@code LocalDateTime.now()})
     * @param days 사다리 간격(일)
     */
    public static LocalDateTime dueAfter(LocalDateTime now, int days) {
        // 새벽 4시 전이면 아직 어제의 학습일이다 — 자정 넘겨 푼 것을 전날 공부로 친다.
        LocalDate studyDay = now.toLocalTime().isBefore(DAY_ROLLOVER)
                ? now.toLocalDate().minusDays(1)
                : now.toLocalDate();
        return studyDay.plusDays(days).atTime(DAY_ROLLOVER);
    }

    /**
     * 채점 결과를 사다리에 반영한다 — QuizService.submit()이 Submission 저장 직후 호출.
     *
     * <p>상태 전이 표(docs/10 — 이 표가 곧 이 메서드의 명세):
     * <table>
     *   <tr><th>결과</th><th>항목 없음</th><th>LEARNING</th><th>GRADUATED</th></tr>
     *   <tr><td>오답</td><td>생성(stage 0, 내일)</td><td>stage 0 리셋</td><td>LEARNING 복귀 + stage 0</td></tr>
     *   <tr><td>정답</td><td>아무것도 안 함</td><td>stage+1(마지막 칸이면 졸업)</td><td>그대로</td></tr>
     * </table>
     *
     * <p><b>{@code Propagation.MANDATORY}</b>: 반드시 호출자(submit)의 트랜잭션에 합류만 하고,
     * 트랜잭션 없이 단독 호출되면 예외를 던진다. "채점·이력은 남았는데 복습 상태만 안 바뀐"
     * 어중간한 상태를 금지하는 설계(docs/10)를 애노테이션으로 강제하는 것 — 나중에 누가
     * 트랜잭션 밖에서 부르면 조용히 어긋나는 대신 즉시 실패한다.
     *
     * <p><b>동시 제출 경합</b>: 같은 문제를 동시에 2번 제출하면(더블클릭) 둘 다 "항목 없음"으로
     * 보고 INSERT를 시도할 수 있다 — 이때 중복 행을 막는 건 이 코드가 아니라 DB의 UNIQUE
     * 제약(V4)이다. 한쪽이 제약 위반으로 실패하면 그 제출 트랜잭션째 롤백되는데, 더블클릭
     * 중복 제출은 하나만 성공하면 충분하므로 재시도 로직은 넣지 않았다(의도된 단순화).
     *
     * <p><b>예정일 전에 맞혀도 승급한다(트레이드오프)</b>: 엄격한 간격 반복은 예정일 전 풀이를
     * 무시하지만, "일부러 미리 푼 사용자를 승급 안 시키는" 동작이 더 이상하다고 판단(docs/10).
     * 규칙이 단순할수록 사용자가 시스템을 예측할 수 있다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onSubmission(Long userId, Problem problem, boolean correct) {
        Optional<ReviewItem> found = reviewItemRepository.findByUserIdAndProblemId(userId, problem.getId());
        LocalDateTime now = LocalDateTime.now();

        if (!correct) {
            if (found.isEmpty()) {
                // 처음 틀림 → 사다리 진입. 첫 복습은 첫 칸 간격(1일) 뒤 = 다음 학습일.
                reviewItemRepository.save(
                        ReviewItem.firstWrong(userId, problem, dueAfter(now, INTERVAL_DAYS[0])));
            } else {
                // 또 틀림 → 맨 아래 칸으로 리셋. GRADUATED였어도 LEARNING으로 복귀(엔티티가 처리).
                found.get().resetToStart(dueAfter(now, INTERVAL_DAYS[0]));
            }
            return;
        }

        // 정답인데 사다리에 없다? → 틀린 적 없는 문제 = 복습할 이유가 없다. 아무것도 안 만든다.
        found.ifPresent(item -> {
            if (item.getStatus() != ReviewStatus.LEARNING) {
                return; // 졸업 후 또 맞힘 → 그대로 (전이 표의 "그대로" 칸)
            }
            int nextStage = item.getStage() + 1;
            if (nextStage >= INTERVAL_DAYS.length) {
                item.graduate(); // 마지막 칸에서 정답 → 졸업 🎓
            } else {
                // 승급 — 다음 복습은 "승급 후 칸"의 간격만큼 뒤(칸이 오를수록 멀어진다).
                item.promote(nextStage, dueAfter(now, INTERVAL_DAYS[nextStage]));
            }
        });
        // 변경 감지(dirty checking)로 UPDATE — MANDATORY로 합류한 submit 트랜잭션이 커밋할 때 반영된다.
    }

    /**
     * 오늘의 복습 — 복습 예정 시각이 지난 LEARNING 항목을 오래 밀린 순으로(docs/10).
     * 빈 목록은 에러가 아니라 정상("오늘 복습할 게 없음").
     *
     * <p>{@code @Transactional(readOnly = true)}: open-in-view=false라 LAZY 접근(객관식 보기)이
     * 트랜잭션 안에서만 가능하므로 DTO 조립까지 경계 안에서 끝낸다(퀴즈/오답노트와 동일).
     * 보기 로딩의 N+1은 default_batch_fetch_size로 완화.
     */
    @Transactional(readOnly = true)
    public PageResponse<ReviewTodayItem> getTodayReviews(Long userId, Pageable pageable) {
        Page<ReviewItem> page = reviewItemRepository.findDue(userId, LocalDateTime.now(), clamp(pageable));
        return PageResponse.from(page.map(ReviewTodayItem::from));
    }

    /**
     * 내 복습 현황(졸업 포함) — 진척 확인용.
     *
     * <p><b>{@code problemIds}가 있으면 그 문제들만</b> 돌려준다 — 오답노트가 복습 단계 배지를
     * 그릴 때 쓰는 경로다(2026-08-20 신설). 그전에는 배지 하나를 그리려고 오답노트가
     * <b>내 복습 현황 전체를 50개씩 최대 20페이지 순차로</b> 받아 갔다. 화면에 필요한 것은
     * 지금 페이지에 보이는 문제 20개의 단계뿐인데 1,000개를 다 받은 셈이라, 문제를 풀수록
     * 오답노트가 먼저 느려졌다(docs/REVIEW_2026-08-20 §4.1).
     *
     * <p>필터가 있을 때 <b>{@code status}는 무시한다</b>. 둘을 겹쳐 받으면 "이 문제들 중
     * 학습 중인 것만"이라는 조합이 생기는데, 부르는 쪽(배지 그리기)은 졸업 여부와 무관하게
     * 그 문제의 현재 상태를 알아야 하므로 쓸 일이 없다. 쓰지 않을 조합을 열어 두면
     * 언젠가 누군가 그 뜻을 되짚어야 한다.
     *
     * @param status     상태 필터(선택, null이면 전체). {@code problemIds}가 있으면 쓰이지 않는다
     * @param problemIds 조회할 문제 id(선택). 개수는 {@link #MAX_SIZE}로 잘라 IN 목록이 무한정
     *                   길어지지 않게 한다 — 목록 길이가 곧 쿼리 크기라 상한이 없으면 클라이언트가
     *                   보내는 만큼 DB가 받아야 한다
     */
    @Transactional(readOnly = true)
    public PageResponse<ReviewListItem> getMyReviews(Long userId, ReviewStatus status,
                                                     List<Long> problemIds, Pageable pageable) {
        // due 판정 기준 시각은 페이지 전체에 하나 — 항목마다 now()를 부르면 기준이 미세하게 갈린다.
        LocalDateTime now = LocalDateTime.now();
        Page<ReviewItem> page = (problemIds == null || problemIds.isEmpty())
                ? reviewItemRepository.findAllOfUser(userId, status, clamp(pageable))
                : reviewItemRepository.findAllOfUserByProblemIds(userId, capIds(problemIds), clamp(pageable));
        return PageResponse.from(page.map(r -> ReviewListItem.from(r, now)));
    }

    /**
     * IN 목록 길이 상한. 넘치면 앞에서부터 자른다 — 에러 대신 조용히 맞추는 것은
     * {@link #clamp}와 같은 판단이고, 이 값을 쓰는 화면(오답노트 한 페이지 20건)이
     * 상한에 닿을 일이 애초에 없어 잘려서 곤란해지는 경우가 없다.
     */
    private List<Long> capIds(List<Long> problemIds) {
        return problemIds.size() > MAX_SIZE ? problemIds.subList(0, MAX_SIZE) : problemIds;
    }

    /**
     * size 상한(50) 보정 — 초과 요청은 에러 대신 조용히 경계값으로 맞춘다(QuizService와 같은
     * 트레이드오프: 명시성 ↓, 편의성 ↑). 전역 max-page-size(100)보다 좁은 스펙이라 여기서 한 번 더 자른다.
     */
    private Pageable clamp(Pageable pageable) {
        return pageable.getPageSize() > MAX_SIZE
                ? PageRequest.of(pageable.getPageNumber(), MAX_SIZE)
                : pageable;
    }
}
