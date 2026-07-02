package project.study.study_project.user.domain;

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
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 회원 엔티티 — DB의 {@code user} 테이블과 1:1로 대응한다(문서 01-data-model).
 *
 * <p>설계 메모:
 * <ul>
 *   <li><b>비밀번호는 원문을 저장하지 않는다.</b> BCrypt 해시({@code passwordHash})만 보관한다(→ docs/06).
 *   <li>PK는 대리키(AUTO_INCREMENT). 로그인 ID인 email은 바뀔 수 있으므로 식별자로 쓰지 않는다.
 *   <li>{@code created_at}은 JPA Auditing으로 자동 채운다({@link CreatedDate}). 그래서 수동으로 넣지 않는다.
 *   <li>기본 생성자를 protected로 막은 이유: JPA는 리플렉션으로 객체를 만들어야 해서 no-args 생성자가
 *       필요하지만, 외부 코드가 빈 User를 함부로 만들지 못하게 접근을 좁힌다. 생성은 빌더로만.
 * </ul>
 */
@Entity
@Table(name = "user")
@EntityListeners(AuditingEntityListener.class) // @CreatedDate 등 감사(auditing) 필드 자동 채움
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // MySQL AUTO_INCREMENT에 대응
    private Long id;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING) // enum 이름(USER/ADMIN)을 문자열로 저장 — 순서 변경에 안전
    @Column(nullable = false, length = 20)
    private Role role;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false) // 생성 시각은 최초 1회만 기록
    private LocalDateTime createdAt;

    /**
     * 회원 생성용 빌더. role을 주지 않으면 기본 {@link Role#USER}로 만든다
     * (회원가입 API가 넘기는 값은 email, passwordHash 뿐이라 편의상 기본값 제공).
     */
    @Builder
    private User(String email, String passwordHash, Role role) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = (role != null) ? role : Role.USER;
    }
}
