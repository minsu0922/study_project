package project.study.study_project.quiz.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 답안 제출 이력 — DB의 {@code submission} 테이블과 대응(문서 01-data-model).
 * 오답노트의 데이터 소스이기도 하다(별도 오답 테이블 없음 — ADR-0002).
 *
 * <p>설계 메모:
 * <ul>
 *   <li><b>{@code userId}는 연관관계 대신 Long 컬럼</b>으로 뒀다. 제출 조회는 항상 "토큰에서 꺼낸
 *       내 id"로 필터할 뿐 User의 다른 필드(이메일 등)를 읽을 일이 없어서, 굳이 엔티티 연관을
 *       만들어 로딩 비용·복잡도를 지불할 이유가 없다. 참조 무결성은 DB의 FK가 계속 보장한다.
 *   <li><b>{@code problem}은 {@code @ManyToOne} LAZY 연관</b>으로 뒀다. 오답노트에서 문제
 *       지문·해설·정답까지 함께 보여줘야 하므로 객체 탐색이 실제로 필요하다.
 *   <li><b>재제출 허용</b>: 같은 문제를 다시 풀면 행이 하나 더 쌓인다(이력 누적, 문서 01).
 *       "문제당 최신 1건"으로 접는 건 조회(오답노트) 쪽 책임.
 *   <li>생성은 정적 팩터리 {@link #of}로만 — 채점 결과까지 확정된 뒤에만 만들 수 있게 해서
 *       "채점 안 된 제출"이라는 어중간한 상태가 존재하지 않도록 한다.
 * </ul>
 */
@Entity
@Table(name = "submission")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Submission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 제출자 id — JWT의 sub에서 꺼낸 값만 넣는다(요청 본문 값은 신뢰하지 않음, docs/06). */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "problem_id", nullable = false)
    private Problem problem;

    /** 제출 원문 — 객관식=choiceId 문자열, OX="O"/"X", 단답형=입력 텍스트(docs/01). */
    @Column(name = "user_answer", nullable = false, length = 500)
    private String userAnswer;

    /** 채점 결과. 오답노트 조회 조건(is_correct=false)이자 인덱스 두 번째 컬럼(V1 참고). */
    @Column(name = "is_correct", nullable = false)
    private boolean correct;

    @CreatedDate
    @Column(name = "submitted_at", nullable = false, updatable = false)
    private LocalDateTime submittedAt;

    private Submission(Long userId, Problem problem, String userAnswer, boolean correct) {
        this.userId = userId;
        this.problem = problem;
        this.userAnswer = userAnswer;
        this.correct = correct;
    }

    /** 채점이 끝난 제출만 생성할 수 있다(클래스 주석 참고). */
    public static Submission of(Long userId, Problem problem, String userAnswer, boolean correct) {
        return new Submission(userId, problem, userAnswer, correct);
    }
}
