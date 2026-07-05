package project.study.study_project.quiz.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/**
 * 객관식 보기 — DB의 {@code choice} 테이블과 대응(문서 01-data-model).
 *
 * <p>설계 메모:
 * <ul>
 *   <li><b>{@code isCorrect}(정답 여부)는 API 응답에 절대 실리면 안 된다</b> — 풀이용 조회(GET /api/quiz)에
 *       정답이 노출되면 퀴즈가 성립하지 않는다. 그래서 DTO(QuizChoiceItem)에는 id/seq/text만 담고,
 *       이 필드는 채점(POST /api/quiz/submit) 로직에서만 읽는다.
 *   <li>{@code problem}은 {@code @ManyToOne} LAZY — 보기에서 문제로 역참조할 일이 거의 없어
 *       즉시 로딩으로 낭비할 이유가 없다.
 *   <li>MVP는 문제당 정답 보기 1개를 가정한다(문서 01). 복수 정답은 로드맵에서 다룬다.
 * </ul>
 */
@Entity
@Table(name = "choice")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Choice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "problem_id", nullable = false)
    private Problem problem;

    /** 보기 내용. 컬럼명 {@code text}는 MySQL 예약어 계열이라 DDL에서 백틱 처리돼 있다(V1 참고). */
    @Column(name = "text", nullable = false, length = 500)
    private String text;

    /** 정답 여부 — 채점 전용. 풀이용 API 응답에 노출 금지(클래스 주석 참고). */
    @Column(name = "is_correct", nullable = false)
    private boolean correct;

    /** 보기 순서(1..N). Problem 쪽 {@code @OrderBy("seq ASC")}가 이 값으로 정렬한다. */
    @Column(nullable = false)
    private int seq;

    private Choice(Problem problem, String text, boolean correct, int seq) {
        this.problem = problem;
        this.text = text;
        this.correct = correct;
        this.seq = seq;
    }

    /**
     * 관리자 등록용 팩터리. seq는 서비스가 입력 순서대로 1..N을 부여한다
     * (관리자가 순서 번호를 직접 관리하게 하면 중복·건너뜀 실수만 늘어난다).
     */
    public static Choice of(Problem problem, String text, boolean correct, int seq) {
        return new Choice(problem, text, correct, seq);
    }
}
