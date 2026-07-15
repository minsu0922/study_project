package project.study.study_project.dailyquiz.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.domain.Submission;

/**
 * 세트 안의 문제 한 줄 — DB의 {@code daily_quiz_item} 테이블과 대응(docs/12, V5).
 *
 * <p>설계 메모:
 * <ul>
 *   <li><b>"풀었는지"를 boolean이 아니라 {@code submission} 참조로 저장</b> — 플래그만 남기면
 *       정답 여부·제출 시각을 또 조회해야 하지만, 제출 이력을 가리키면 전부 따라온다.
 *       진실의 원천은 여전히 Submission이고 여기는 연결만 한다(docs/12).
 *   <li><b>생성은 {@link DailyQuiz#addItem} 경유로만</b>(생성자 package-private) — seq 부여와
 *       세트 소속을 애그리거트 루트(DailyQuiz)가 책임져, "세트 밖의 항목"이나 "seq가 겹치는
 *       항목" 같은 어중간한 상태를 만들 수 없게 한다.
 *   <li><b>변경 메서드는 {@link #connect} 하나뿐</b> — 항목에 일어나는 사건은 "풀렸다"가
 *       전부다. 연결 해제(un-solve)는 업무 규칙에 없으므로 메서드도 없다.
 * </ul>
 */
@Entity
@Table(name = "daily_quiz_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DailyQuizItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "daily_quiz_id", nullable = false)
    private DailyQuiz dailyQuiz;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "problem_id", nullable = false)
    private Problem problem;

    /** 세트 안 표시 순서(1..N). 부여는 DailyQuiz.addItem이 한다. */
    @Column(nullable = false)
    private int seq;

    /** 어느 배합 칸으로 뽑혔나 — 조회 시점 재계산 불가라 저장(enum 주석 참고). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DailyQuizSource source;

    /** 이 세트에서 푼 제출. null = 아직 안 풂. 정답 여부는 submission.correct에서 읽는다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submission_id")
    private Submission submission;

    DailyQuizItem(DailyQuiz dailyQuiz, Problem problem, int seq, DailyQuizSource source) {
        this.dailyQuiz = dailyQuiz;
        this.problem = problem;
        this.seq = seq;
        this.source = source;
    }

    /** 풀림 여부 — submission 연결 자체가 곧 "풀었다"의 정의다(파생값, 별도 플래그 없음). */
    public boolean isSolved() {
        return submission != null;
    }

    /**
     * 제출과 연결한다("풀렸다" 사건). 이미 풀린 항목에는 호출하면 안 된다 —
     * 첫 제출만 세트 진행률로 인정하는 규칙(docs/12)의 판정은 DailyQuiz.connectSubmission이 한다.
     */
    void connect(Submission submission) {
        this.submission = submission;
    }
}
