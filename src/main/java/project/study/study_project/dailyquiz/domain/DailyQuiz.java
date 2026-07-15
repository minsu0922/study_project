package project.study.study_project.dailyquiz.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.domain.Submission;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 오늘의 퀴즈 세트 — DB의 {@code daily_quiz} 테이블과 대응(docs/12, ADR-0005, V5).
 *
 * <p>사용자 × 날짜당 <b>딱 1세트</b>. 1세트 보장은 코드가 아니라 DB의 UNIQUE 제약
 * (uk_dailyquiz_user_date)이 한다 — 지연 생성의 동시 첫 조회 경합에서 최후의 벽(ADR-0005).
 *
 * <p>설계 메모:
 * <ul>
 *   <li><b>애그리거트 루트</b>: 항목(DailyQuizItem)의 생성({@link #addItem})과 풀이 반영
 *       ({@link #connectSubmission})은 전부 이 클래스를 거친다. "항목은 풀렸는데 세트 완료
 *       도장은 안 찍힌" 어중간한 상태를 만들 수 없게 변경 단위를 묶는다(ReviewItem과 같은 원칙).
 *   <li><b>{@code userId}는 연관관계 없이 Long 컬럼</b> — Submission/ReviewItem과 같은 이유.
 *       조회가 항상 "토큰에서 꺼낸 내 id" 필터일 뿐 User의 다른 필드를 읽을 일이 없다.
 *   <li><b>{@code completedAt}은 저장한다</b> — "시간이 흐르면 저절로 바뀌는 값은 저장하지
 *       않는다"(docs/10) 원칙과 충돌하지 않는다. 완료는 시간이 아니라 <b>마지막 제출이라는
 *       사건</b>으로 확정되는 값이고, 스트릭 계산이 매번 항목을 전수 조사하지 않도록
 *       세트에 도장을 찍어 두는 것이다(docs/12).
 *   <li><b>스트릭은 여기 없다</b> — 연속 일수는 completedAt들에서 파생되는 값이라 저장하지
 *       않고 서비스가 조회 시점에 계산한다(저장하면 자정마다 "끊김" 갱신 배치가 필요해진다).
 * </ul>
 */
@Entity
@Table(name = "daily_quiz")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DailyQuiz {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 세트 주인 id — JWT의 sub에서 꺼낸 값만 넣는다(요청 본문 값은 신뢰하지 않음, docs/06). */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 이 세트가 속한 날(서버 LocalDate 기준). 자정이 지나면 새 세트가 만들어진다(어제 세트는 리셋). */
    @Column(name = "quiz_date", nullable = false)
    private LocalDate quizDate;

    /** 모든 항목을 푼 시각. null = 미완료. 스트릭 계산의 재료(docs/12). */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 세트 항목들 — 세트와 생명주기를 함께하므로 CASCADE 저장(세트 save 한 번에 항목까지).
     * {@code @OrderBy}로 항상 표시 순서(seq)대로 읽는다(Problem.choices와 같은 판단).
     */
    @OneToMany(mappedBy = "dailyQuiz", cascade = CascadeType.ALL)
    @OrderBy("seq ASC")
    private List<DailyQuizItem> items = new ArrayList<>();

    private DailyQuiz(Long userId, LocalDate quizDate) {
        this.userId = userId;
        this.quizDate = quizDate;
    }

    /** 빈 세트 생성 — 항목은 {@link #addItem}으로 채운다. 정적 팩터리로만 생성(Submission과 같은 규칙). */
    public static DailyQuiz of(Long userId, LocalDate quizDate) {
        return new DailyQuiz(userId, quizDate);
    }

    /**
     * 문제를 세트에 추가한다. seq는 추가 순서(1..N)로 자동 부여 — 배합·셔플이 끝난 최종
     * 순서대로 호출하는 건 서비스 책임이고, 엔티티는 "빈 번호 없이 순서대로"만 보장한다.
     */
    public void addItem(Problem problem, DailyQuizSource source) {
        items.add(new DailyQuizItem(this, problem, items.size() + 1, source));
    }

    /**
     * 채점된 제출을 세트에 반영한다 — QuizService.submit 경유로만 호출되는 유일한 쓰기 경로.
     *
     * <p>규칙(docs/12): 이 문제가 세트에 있고 <b>아직 안 푼 상태일 때만</b> 연결한다.
     * 같은 문제를 다시 풀어도 첫 제출이 계속 연결돼 있다(진행률은 첫 풀이 기준).
     * 마지막 항목이었으면 완료 도장(completedAt)까지 한 번에 찍는다 — "항목은 다 풀렸는데
     * 세트는 미완료"인 순간이 존재하지 않도록 같은 변경 단위로 묶는 것이 이 메서드의 존재 이유.
     *
     * <p>항목 탐색이 메모리 순회인 이유: 세트는 최대 10개라 쿼리보다 순회가 싸고,
     * 어차피 세트를 이미 로딩한 트랜잭션 안이다.
     *
     * @return 세트에 반영됐으면 true(세트에 없는 문제·이미 푼 문제면 false — 호출부는 무시)
     */
    public boolean connectSubmission(Submission submission, LocalDateTime now) {
        DailyQuizItem target = items.stream()
                .filter(item -> !item.isSolved()
                        && item.getProblem().getId().equals(submission.getProblem().getId()))
                .findFirst()
                .orElse(null);
        if (target == null) {
            return false;
        }
        target.connect(submission);
        if (items.stream().allMatch(DailyQuizItem::isSolved)) {
            this.completedAt = now; // 마지막 문제였다 — 오늘 완료 🎉
        }
        return true;
    }

    public boolean isCompleted() {
        return completedAt != null;
    }
}
