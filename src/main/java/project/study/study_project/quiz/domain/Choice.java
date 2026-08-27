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

    /**
     * 이 <b>오답</b>이 왜 틀렸는지 한 줄. 정답 보기는 {@code null}이다(V15).
     *
     * <p><b>왜 해설이 아니라 보기에 붙나.</b> 해설이 오답을 "②번"처럼 번호로 가리키면
     * 학습자에게는 반드시 어긋난다 — 보기를 요청마다 다시 섞어 내보내기 때문이다
     * ({@code QuizChoiceItem.shuffledFrom}). 그렇다고 섞기를 버리면 정답 위치 편향이
     * 그대로 노출된다(승인된 17문제의 정답이 1번 6·2번 6·3번 5·<b>4번 0</b>개였다).
     * 설명을 보기 행에 붙여 두면 <b>섞은 순서 그대로 번호를 매겨도 짝이 안 어긋난다</b> —
     * 채점이 이미 보기 {@code id}로 이뤄지는 것과 같은 이치다.
     *
     * <p><b>정답 보기를 비워 두는 이유</b>: 정답의 근거는 {@code Problem.explanation}이
     * 통째로 맡는다. 양쪽에 다 적으면 같은 말이 두 벌이 되고 언젠가 한쪽만 고쳐진다.
     *
     * <p>{@code null}이 되는 또 한 경우: 이 컬럼이 생기기 <b>전에</b> 승인된 문제들.
     * 그 문제들은 오답 설명이 통짜 해설 안에 녹아 있어 옮겨 담을 값이 없다.
     * 화면은 한 보기도 값이 없으면 옛 형식으로 알아보고 통짜 해설을 그대로 그린다.
     */
    @Column(name = "rationale", length = 1000)
    private String rationale;

    /** 보기 순서(1..N). Problem 쪽 {@code @OrderBy("seq ASC")}가 이 값으로 정렬한다. */
    @Column(nullable = false)
    private int seq;

    private Choice(Problem problem, String text, boolean correct, String rationale, int seq) {
        this.problem = problem;
        this.text = text;
        this.correct = correct;
        this.rationale = rationale;
        this.seq = seq;
    }

    /**
     * 관리자 등록용 팩터리. seq는 서비스가 입력 순서대로 1..N을 부여한다
     * (관리자가 순서 번호를 직접 관리하게 하면 중복·건너뜀 실수만 늘어난다).
     */
    public static Choice of(Problem problem, String text, boolean correct, String rationale, int seq) {
        return new Choice(problem, text, correct, rationale, seq);
    }

    /**
     * 오답 설명 없이 만드는 팩터리 — 이 필드가 생기기 전 코드와 테스트가 그대로 컴파일되게 한다.
     *
     * <p>{@code GeneratedProblemItem}이 새 필드를 뒤에 붙이며 짧은 생성자를 남긴 것과 같은
     * 처방이다. 손대야 할 곳이 많을수록 정작 봐야 할 변경이 묻힌다.
     */
    public static Choice of(Problem problem, String text, boolean correct, int seq) {
        return new Choice(problem, text, correct, null, seq);
    }
}
