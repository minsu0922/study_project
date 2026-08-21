package project.study.study_project.llm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;

import java.time.LocalDateTime;

/**
 * LLM이 생성한 문제 초안 — {@code generated_problem_draft} 테이블(V6)과 대응.
 *
 * <p>설계 메모(docs/13, ADR-0006):
 * <ul>
 *   <li><b>Problem과 별도 테이블인 이유</b>: problem 테이블에 status 컬럼을 얹는 대안도 있었지만,
 *       그러면 퀴즈·복습·오늘의 퀴즈의 <b>모든 조회에 "승인된 것만" 필터가 번져야</b> 하고
 *       하나라도 빠뜨리면 미검수 문제가 사용자에게 노출된다. 초안을 딴 방에 두면
 *       기존 조회는 한 줄도 안 바꿔도 안전하다 — 실수할 수 있는 구조 자체를 없애는 선택.
 *   <li><b>보기는 JSON 문자열</b>({@code choicesJson}): 초안은 채점에 쓰이지 않는 임시 데이터라
 *       choice처럼 정규화하지 않는다(V6 주석 참고). 파싱은 서비스 계층(ObjectMapper)이 담당.
 *   <li><b>상태 전이를 엔티티 메서드로 강제</b>: approve()/reject()만이 상태를 바꿀 수 있고,
 *       이미 처리된 초안에 다시 호출하면 예외(LLM_002) — "승인 버튼 두 번 클릭" 같은
 *       중복 처리를 서비스가 아니라 도메인 규칙으로 막는다.
 * </ul>
 */
@Entity
@Table(name = "generated_problem_draft")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GeneratedProblemDraft {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Domain domain;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private Difficulty difficulty;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProblemType type;

    /**
     * 목록에 뜰 한 줄 제목 — 없으면 {@code null}(V13). 승인 시 {@link project.study.study_project.quiz.domain.Problem}으로
     * 그대로 옮겨진다.
     *
     * <p><b>초안 단계부터 들고 있는 이유</b>는 {@link #documentSlug}와 같다 — 검수자가 검수함
     * 목록을 훑을 때 필요하고, 승인 시점에 만들어 내려면 그때 모델을 다시 불러야 한다.
     * 게다가 제목은 <b>지문을 쓴 모델이 그 자리에서 붙이는 것</b>이 가장 정확하다.
     */
    @Column(length = 120)
    private String title;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(nullable = false)
    private String question;

    @Column(length = 500)
    private String answer;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column
    private String explanation;

    /** 객관식 보기 [{"text":..,"correct":..}] JSON. 객관식이 아니면 NULL. */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "choices_json")
    private String choicesJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DraftStatus status;

    /** 생성에 사용한 모델명 — 모델 교체 시 품질 비교(승인율)를 낼 수 있게 기록. */
    @Column(nullable = false, length = 50)
    private String model;

    @Column(name = "reject_reason", length = 500)
    private String rejectReason;

    /**
     * 근거가 된 개념 문서의 slug — 없으면 {@code null}(V9, 2단계).
     *
     * <p>4일 주기의 2·3·4일차에 만들어진 문제는 그 주기 1일차 문서의 slug를 갖는다.
     * 승인 시 {@code Problem.documentSlug}로 그대로 옮겨진다.
     *
     * <p><b>초안 단계부터 들고 있는 이유</b>: 검수자가 "이 문제가 어느 문서에서 나왔는지"를
     * 보고 판단해야 한다. 승인 시점에 붙이려면 그때 다시 계산해야 하는데, 생성 시점의
     * 근거를 나중에 복원하는 것은 불가능하다(그날의 주기 계산을 되짚어야 한다).
     */
    @Column(name = "document_slug", length = 150)
    private String documentSlug;

    /** 승인으로 생성된 problem.id (FK 아님 — 이력 성격, V6 주석 참고). */
    @Column(name = "approved_problem_id")
    private Long approvedProblemId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    private GeneratedProblemDraft(Domain domain, Difficulty difficulty, ProblemType type, String title,
                                  String question, String answer, String explanation,
                                  String choicesJson, String model, String documentSlug) {
        this.domain = domain;
        this.difficulty = difficulty;
        this.type = type;
        this.title = title;
        this.question = question;
        this.answer = answer;
        this.explanation = explanation;
        this.choicesJson = choicesJson;
        this.status = DraftStatus.PENDING;
        this.model = model;
        this.documentSlug = documentSlug;
    }

    /**
     * 생성 직후 저장용 팩터리 — 초안은 항상 PENDING으로 태어난다.
     *
     * <p>{@code title}과 {@code question}이 나란한 String 두 개라 순서를 바꿔도 컴파일된다.
     * 컬럼 순서(V13)·필드 순서와 같게 맞춰 두었으니 부르는 쪽도 그 순서로 적는다.
     */
    public static GeneratedProblemDraft pending(Domain domain, Difficulty difficulty, ProblemType type,
                                                String title, String question, String answer,
                                                String explanation, String choicesJson,
                                                String model, String documentSlug) {
        return new GeneratedProblemDraft(domain, difficulty, type, title,
                question, answer, explanation, choicesJson, model, documentSlug);
    }

    /** 승인 처리 — 생성된 problem.id를 이력으로 남긴다. 이미 처리된 초안이면 LLM_002. */
    public void approve(Long problemId) {
        requirePending();
        this.status = DraftStatus.APPROVED;
        this.approvedProblemId = problemId;
        this.reviewedAt = LocalDateTime.now();
    }

    /** 거절 처리 — 사유(선택)는 프롬프트 개선 참고용으로 남긴다. */
    public void reject(String reason) {
        requirePending();
        this.status = DraftStatus.REJECTED;
        this.rejectReason = reason;
        this.reviewedAt = LocalDateTime.now();
    }

    /**
     * 복구 — 거절한 초안을 검수 대기로 되돌린다.
     *
     * <p><b>왜 거절만 되돌릴 수 있나.</b> 거절된 초안은 <b>아무것도 만들지 않았다</b>.
     * 대기함에서 빠졌을 뿐이라 되돌려도 잃을 것이 없다. 반면 승인된 초안은 이미 정식
     * {@code problem}을 만들었으므로, PENDING으로 되돌리면 승인 버튼을 또 누를 수 있고
     * <b>똑같은 문제가 두 개</b> 생긴다(문서와 달리 문제에는 slug 같은 중복 방지 장치가 없다).
     * 승인을 취소하고 싶으면 문제 관리에서 그 문제를 지우는 것이 옳은 경로다 —
     * 그쪽은 제출 이력이 있으면 삭제를 막아 주기까지 한다(QUIZ_003).
     *
     * <p><b>거절 사유를 지우지 않는 이유.</b> 남겨 두면 복구한 초안을 다시 볼 때 "저번에
     * 왜 거절했는지"를 알 수 있다. 게다가 컬럼을 새로 만들지 않고도 복구 여부를 구분할 수 있다 —
     * {@code status=PENDING}인데 {@code rejectReason}이 있으면 <b>한 번 거절됐다가 복구된 초안</b>이다.
     *
     * <p>프롬프트 되먹임에서는 저절로 빠진다: 거절 사례 조회가 {@code status = 'REJECTED'}로
     * 거르므로, 상태만 되돌려 놓으면 다음 생성에 더는 실리지 않는다(손댈 코드가 없다).
     *
     * @throws BusinessException 거절 상태가 아닐 때(LLM_002)
     */
    public void restore() {
        if (this.status != DraftStatus.REJECTED) {
            throw new BusinessException(ErrorCode.LLM_002,
                    "거절된 초안만 검수 대기로 되돌릴 수 있습니다. 현재 상태: " + this.status);
        }
        this.status = DraftStatus.PENDING;
        this.reviewedAt = null; // 아직 처리되지 않은 상태로 되돌아간다(NULL = 미처리)
    }

    private void requirePending() {
        if (this.status != DraftStatus.PENDING) {
            throw new BusinessException(ErrorCode.LLM_002);
        }
    }
}
