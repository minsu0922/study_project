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

    /** 승인으로 생성된 problem.id (FK 아님 — 이력 성격, V6 주석 참고). */
    @Column(name = "approved_problem_id")
    private Long approvedProblemId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    private GeneratedProblemDraft(Domain domain, Difficulty difficulty, ProblemType type,
                                  String question, String answer, String explanation,
                                  String choicesJson, String model) {
        this.domain = domain;
        this.difficulty = difficulty;
        this.type = type;
        this.question = question;
        this.answer = answer;
        this.explanation = explanation;
        this.choicesJson = choicesJson;
        this.status = DraftStatus.PENDING;
        this.model = model;
    }

    /** 생성 직후 저장용 팩터리 — 초안은 항상 PENDING으로 태어난다. */
    public static GeneratedProblemDraft pending(Domain domain, Difficulty difficulty, ProblemType type,
                                                String question, String answer, String explanation,
                                                String choicesJson, String model) {
        return new GeneratedProblemDraft(domain, difficulty, type,
                question, answer, explanation, choicesJson, model);
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

    private void requirePending() {
        if (this.status != DraftStatus.PENDING) {
            throw new BusinessException(ErrorCode.LLM_002);
        }
    }
}
