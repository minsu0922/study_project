package project.study.study_project.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import project.study.study_project.user.domain.User;

import java.util.Optional;

/**
 * User 저장소. {@link JpaRepository}를 상속하면 save/findById 등 기본 CRUD가 자동 제공된다.
 *
 * <p>메서드 이름만 규칙대로 지으면 Spring Data JPA가 쿼리를 만들어 준다(쿼리 메서드).
 * <ul>
 *   <li>{@link #existsByEmail} — 회원가입 시 이메일 중복 검사(AUTH_001)용. 존재 여부만 보면 되므로
 *       엔티티를 통째로 가져오는 것보다 가볍다.
 *   <li>{@link #findByEmail} — 로그인 시 이메일로 회원 조회(AUTH_002)용.
 * </ul>
 */
public interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByEmail(String email);

    Optional<User> findByEmail(String email);
}
