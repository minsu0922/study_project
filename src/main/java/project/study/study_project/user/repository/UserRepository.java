package project.study.study_project.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import project.study.study_project.user.domain.User;

import java.util.Optional;

/**
 * User 저장소. {@link JpaRepository}를 상속하면 save/findById 등 기본 CRUD가 자동 제공된다.
 *
 * <p>메서드 이름만 규칙대로 지으면 Spring Data JPA가 쿼리를 만들어 준다(쿼리 메서드).
 * <ul>
 *   <li>{@link #existsByUsername} — 회원가입 시 아이디 중복 검사(AUTH_001)용. 존재 여부만
 *       보면 되므로 엔티티를 통째로 가져오는 것보다 가볍다.
 *   <li>{@link #findByUsername} — 로그인 시 아이디로 회원 조회(AUTH_002)용.
 * </ul>
 *
 * <p>email로 찾는 메서드는 V12에서 없앴다. 컬럼은 남아 있지만 <b>읽는 코드가 없다</b> —
 * 쓰지 않는 조회 메서드를 남겨 두면 "이쪽으로도 로그인이 되나?" 하는 오해를 부른다.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByUsername(String username);

    Optional<User> findByUsername(String username);
}
