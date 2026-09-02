package project.study.study_project.report.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import project.study.study_project.quiz.domain.Problem;

import java.time.LocalDateTime;

/**
 * 학습자가 올린 문제 오류 제보 한 건 — DB의 {@code problem_report} 테이블(V17).
 *
 * <p>이 기능이 메우는 구멍은 <b>되먹임의 방향</b>이다. 만드는 쪽(생성 → 검수 → 거절 사유)에는
 * 통로가 있었지만 푸는 쪽에는 없었다. 검수를 통과하고 출제된 <b>뒤에야</b> 드러나는 결함이
 * 가장 값진 신호인데(검수가 놓친 것이므로) 그것을 주울 그릇이 없었다.
 *
 * <p>설계 메모:
 * <ul>
 *   <li><b>{@code userId}는 연관관계 없이 Long 컬럼</b> — ReviewItem·Submission과 같은 이유.
 *       조회가 항상 "토큰에서 꺼낸 내 id" 필터일 뿐, 제보자의 다른 필드를 읽을 일이 없다.
 *       관리자 화면도 "누가 냈나"를 보여 주지 않는다(1인 서비스라 알아도 쓸 데가 없고,
 *       제보자를 보여 주기 시작하면 판단이 사람에 끌린다).
 *   <li><b>{@code problem}은 {@code @ManyToOne} LAZY 연관</b> — 제보함이 지문을 함께 보여 줘야
 *       하고, 되먹임도 "이 지문이 이런 이유로 틀렸다"는 짝이라 객체 탐색이 실제로 필요하다.
 *   <li><b>상태 전이 메서드만 공개</b>하고 setter는 없다 — {@code status}만 바뀌고
 *       {@code resolvedAt}은 안 바뀐 어중간한 행이 생길 수 없게(ReviewItem과 같은 방식).
 *   <li><b>{@code updatedAt}이 없다.</b> 이 행은 한 번 만들어지고 한 번 처리되면 끝이라
 *       "마지막으로 바뀐 시각"이 곧 {@code resolvedAt}이다. 같은 뜻의 컬럼을 둘 두지 않는다.
 * </ul>
 */
@Entity
@Table(name = "problem_report")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProblemReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "problem_id", nullable = false)
    private Problem problem;

    /** 제보자 id — JWT의 sub에서 꺼낸 값만 넣는다(요청 본문의 값은 신뢰하지 않는다, docs/06). */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReportReason reason;

    /** 제보자가 덧붙인 한 줄(선택). 없으면 {@code null} — 빈 문자열로 저장하지 않는다. */
    @Column(length = 500)
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private ReportStatus status;

    /** 인정·기각할 때 관리자가 남기는 메모(선택). "왜 기각했나"를 나중의 자신에게 남기는 자리. */
    @Column(name = "admin_note", length = 500)
    private String adminNote;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 처리 시각. PENDING인 동안은 {@code null}이라 "아직 안 봤다"가 이 컬럼 하나로 드러난다. */
    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    private ProblemReport(Problem problem, Long userId, ReportReason reason, String detail) {
        this.problem = problem;
        this.userId = userId;
        this.reason = reason;
        this.detail = detail;
        this.status = ReportStatus.PENDING;
    }

    /**
     * 새 제보. 정적 팩터리로만 만들어 "PENDING이 아닌 채로 태어난 제보"라는 상태를 없앤다.
     *
     * @param detail 공백뿐이면 서비스가 {@code null}로 정규화해 넘긴다 — 빈 문자열과 없음을
     *               구분해 봐야 화면과 프롬프트 양쪽에서 같은 뜻이라, 그릇을 하나로 줄인다
     */
    public static ProblemReport of(Problem problem, Long userId, ReportReason reason, String detail) {
        return new ProblemReport(problem, userId, reason, detail);
    }

    /**
     * 지적이 맞다고 판단 — 이 건의 사유가 다음 생성 프롬프트에 되먹여진다.
     *
     * <p>이미 처리된 건을 다시 처리하는 것은 막지 않는다(엔티티가 아니라 서비스가 막는다).
     * 여기서 던지면 상태 전이 규칙이 엔티티와 서비스 두 곳에 흩어진다.
     */
    public void accept(String adminNote) {
        this.status = ReportStatus.ACCEPTED;
        this.adminNote = adminNote;
        this.resolvedAt = LocalDateTime.now();
    }

    /** 지적이 틀렸거나 문제가 아니라고 판단 — 되먹임에 쓰이지 않는다. */
    public void dismiss(String adminNote) {
        this.status = ReportStatus.DISMISSED;
        this.adminNote = adminNote;
        this.resolvedAt = LocalDateTime.now();
    }

    /** 아직 사람이 안 본 건인가 — 서비스의 이중 처리 방어가 읽는다. */
    public boolean isPending() {
        return status == ReportStatus.PENDING;
    }
}
