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
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;

import java.time.LocalDateTime;

/**
 * LLM이 생성한 개념 문서 초안 — {@code generated_document_draft} 테이블(V8)과 대응. docs/15.
 *
 * <p>{@link GeneratedProblemDraft}의 문서판이다. 구조를 일부러 똑같이 맞췄다 —
 * 상태 전이를 엔티티 메서드로만 허용하고, 이미 처리된 초안에 다시 호출하면 예외(LLM_002).
 * 두 초안이 다르게 동작하면 관리자 화면과 서비스 코드에서 매번 "문서는 어땠더라"를
 * 다시 확인해야 하는데, 그 확인 비용이 곧 버그가 된다.
 *
 * <p><b>상태를 필드로 두고 setter를 열지 않는 이유</b>: {@code draft.setStatus(APPROVED)}가
 * 가능하면 승인 처리와 문서 등록이 갈라질 수 있다 — 상태만 APPROVED인데 정식 문서는 없는
 * 유령 행이 생긴다. {@link #approve(Long)}는 document.id를 <b>반드시</b> 받게 해서
 * "승인했다면 그 결과물이 있다"를 타입으로 강제한다.
 *
 * <p><b>검증 결과를 컬럼으로 저장하지 않는 이유</b>: 제목 불일치·필수 절 누락 같은 판정은
 * 저장하지 않고 조회할 때마다 다시 계산한다({@code LlmDocumentService}). 검증 규칙은 실물을
 * 보며 계속 손볼 것이라, 저장해 두면 <b>이미 흡수된 초안은 옛 규칙으로 굳어</b> 규칙을
 * 고쳐도 화면이 안 바뀐다. 본문 몇 KB를 훑는 계산이라 비용도 무시할 만하다.
 */
@Entity
@Table(name = "generated_document_draft")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GeneratedDocumentDraft {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Domain domain;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 150)
    private String slug;

    /** 마크다운 본문. 3,000자를 넘나들어 TEXT로는 모자랄 수 있어 LONGTEXT(V8). */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "content_md", nullable = false)
    private String contentMd;

    /** 태그 {@code ["security","auth"]} JSON. 파싱은 서비스 계층(ObjectMapper)이 담당. */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "tags_json")
    private String tagsJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DraftStatus status;

    /** 생성에 사용한 모델명 — 모델 교체 시 품질 비교(승인율)를 낼 수 있게 기록. */
    @Column(nullable = false, length = 50)
    private String model;

    @Column(name = "reject_reason", length = 500)
    private String rejectReason;

    /** 승인으로 생성된 document.id (FK 아님 — 이력 성격, V8 주석 참고). */
    @Column(name = "approved_document_id")
    private Long approvedDocumentId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    private GeneratedDocumentDraft(Domain domain, String title, String slug,
                                   String contentMd, String tagsJson, String model) {
        this.domain = domain;
        this.title = title;
        this.slug = slug;
        this.contentMd = contentMd;
        this.tagsJson = tagsJson;
        this.status = DraftStatus.PENDING;
        this.model = model;
    }

    /** 흡수 직후 저장용 팩터리 — 초안은 항상 PENDING으로 태어난다. */
    public static GeneratedDocumentDraft pending(Domain domain, String title, String slug,
                                                 String contentMd, String tagsJson, String model) {
        return new GeneratedDocumentDraft(domain, title, slug, contentMd, tagsJson, model);
    }

    /** 승인 처리 — 생성된 document.id를 이력으로 남긴다. 이미 처리된 초안이면 LLM_002. */
    public void approve(Long documentId) {
        requirePending();
        this.status = DraftStatus.APPROVED;
        this.approvedDocumentId = documentId;
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
