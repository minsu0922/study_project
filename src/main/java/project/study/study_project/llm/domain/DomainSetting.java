package project.study.study_project.llm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import project.study.study_project.global.common.Domain;

import java.time.LocalDateTime;

/**
 * 분야 설정 한 줄 — {@code domain_setting} 테이블(V19)과 대응.
 *
 * <p><b>행 하나가 enum {@link Domain} 상수 하나다.</b> PK가 자동 증가 숫자가 아니라
 * {@code domain} 그 자체인 이유는 마이그레이션 주석에 적었다 — 읽는 쪽이 늘 "NETWORK의
 * 설정"을 찾지 "3번 행"을 찾지 않는다. 그래서 이 엔티티에는 {@code id} 필드가 없고
 * {@link #domain}이 곧 식별자다.
 *
 * <p><b>이 테이블은 마이그레이션이 채우지 않는다.</b> enum 상수가 늘거나 줄 때마다
 * 마이그레이션을 새로 쓰게 만들면 깜빡한 상수는 설정 행 없이 조용히 배치에서 빠진다.
 * 대신 기동 시 동기화 러너(4번 작업)가 enum을 훑어 없는 행을 만들고 사라진 행을 지운다 —
 * {@link #initial}은 그 동기화가 새 행을 만들 때 쓰는 문이다.
 *
 * <p><b>감사 필드는 {@code @CreatedDate}와 {@code @LastModifiedDate}를 함께 쓴다.</b>
 * {@code TopicQueueItem}은 {@code @CreatedDate}만 두지만, 이 테이블의 {@code updated_at}은
 * NOT NULL이라 그 꼴을 그대로 따르면 최초 저장에서 그 컬럼이 비어 제약 위반이 난다.
 * 관리 화면에서 값을 고치는 테이블이라 "언제 마지막으로 고쳤나"도 실제로 뜻이 있다
 * ({@code ReviewItem}이 이미 이 조합을 쓴다).
 */
@Entity
@Table(name = "domain_setting")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DomainSetting {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "domain", nullable = false, length = 30)
    private Domain domain;

    /** 배치 자동 선택 후보인가. 꺼 두면 순환에서 빠지되 행 자체는 남아 값을 잃지 않는다. */
    @Column(nullable = false)
    private boolean enabled;

    /** 날짜 순환 순서. {@link #changeOrder}로만 바뀐다 — 이유는 그 메서드 주석 참고. */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /** 화면에 뜨는 이름. 비면 목록에서 그 줄이 통째로 사라진 것처럼 보이므로 항상 값이 있어야 한다. */
    @Column(name = "display_name", nullable = false, length = 40)
    private String displayName;

    /**
     * 모델에게 주는 경계 설명(4번 작업의 {@code DomainHints}가 읽는다). 안 쓰면 {@code null}.
     * MySQL TEXT 컬럼이라 String 기본 매핑(VARCHAR)과 구분하기 위해 LONGVARCHAR 지정
     * ({@code Problem.question}과 같은 이유).
     */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column
    private String hint;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    private DomainSetting(Domain domain, boolean enabled, int sortOrder, String displayName, String hint) {
        this.domain = domain;
        this.enabled = enabled;
        this.sortOrder = sortOrder;
        this.displayName = validateDisplayName(displayName);
        this.hint = normalizeHint(hint);
    }

    /**
     * 동기화 러너가 enum에는 있는데 테이블에는 없는 분야를 만날 때 쓰는 문(4번 작업).
     * 관리자가 화면에서 직접 새 행을 추가하는 경로는 없다 — 행 수가 enum 상수 수로 고정이라
     * "추가"라는 개념 자체가 없고, 있는 행을 {@link #edit}으로 고치는 것만 있다.
     */
    public static DomainSetting initial(Domain domain, boolean enabled, int sortOrder,
                                         String displayName, String hint) {
        return new DomainSetting(domain, enabled, sortOrder, displayName, hint);
    }

    /**
     * 배치 참여 여부·화면 이름·힌트를 고친다. {@code sortOrder}는 받지 않는다 —
     * {@link #changeOrder}의 몫이다({@code TopicQueueItem.edit}과 같은 이유: 순서 바꾸기는
     * 여러 행을 짝지어 값을 맞바꾸는 별개의 연산이라, 한 메서드에 같이 두면 "이름만
     * 고치려던" 호출도 실수로 순서를 흔들 수 있다).
     *
     * <p>빈 화면 이름은 막는다 — 막지 않으면 목록에서 그 줄이 빈칸으로 뜨거나 아예
     * 사라진 것처럼 보여 관리자가 설정이 날아갔다고 오해한다.
     */
    public void edit(boolean enabled, String displayName, String hint) {
        this.enabled = enabled;
        this.displayName = validateDisplayName(displayName);
        this.hint = normalizeHint(hint);
    }

    /** 순서 바꾸기 — 두 행의 값을 맞바꾸는 방식이라 서비스가 짝지어 호출한다({@code TopicQueueItem}과 동일). */
    public void changeOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    /**
     * 화면 이름이 비어 있지 않은지 검증한다. {@link #initial}·{@link #edit} 둘 다 이 문을
     * 거친다 — 생성 경로만 막고 수정 경로를 열어 두면, 동기화가 만든 행은 안전한데
     * 관리자가 고친 행만 비게 되는 구멍이 생긴다.
     */
    private static String validateDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("화면 이름은 비울 수 없습니다.");
        }
        return displayName;
    }

    /**
     * 공백만 있는 힌트를 {@code null}로 맞춘다. {@link #initial}·{@link #edit} 둘 다 이 문을
     * 거친다 — {@code edit}에서만 정리하면, 동기화가 {@link #initial}로 만든 행은 빈 문자열을
     * 그대로 들고 있고 관리자가 한 번이라도 고친 행만 {@code null}이 되어, 힌트를 읽는 쪽마다
     * "빈 문자열"과 "null"을 둘 다 처리해야 한다. 한 군데서 한 가지 값으로 맞춰 둔다.
     */
    private static String normalizeHint(String hint) {
        return (hint == null || hint.isBlank()) ? null : hint;
    }
}
