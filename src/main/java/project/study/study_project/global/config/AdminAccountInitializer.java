package project.study.study_project.global.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;

/**
 * 최초 관리자 계정 자동 생성 — 앱 부팅 시 ADMIN 계정이 하나도 없으면 설정값으로 만들어 준다.
 *
 * <p>왜 Flyway 마이그레이션(SQL)이 아니라 코드인가:
 * <ul>
 *   <li>마이그레이션에 넣으려면 <b>BCrypt 해시를 하드코딩</b>해야 한다. 해시가 저장소에 박제되면
 *       비밀번호를 바꿀 때마다 마이그레이션을 새로 파야 하고, 같은 해시가 모든 환경에 복제된다.
 *   <li>여기서는 부팅 시 {@link PasswordEncoder}로 해시를 만들므로 비밀번호를
 *       설정/환경변수(ADMIN_EMAIL, ADMIN_PASSWORD)로 환경마다 다르게 줄 수 있다.
 * </ul>
 *
 * <p>"ADMIN이 한 명도 없을 때만" 만든다 — 이미 있으면 아무것도 하지 않으므로 매 부팅마다
 * 실행돼도 안전(멱등)하다. 다른 계정을 관리자로 승격하는 기능은 로드맵(회원 관리)에서 다룬다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminAccountInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${admin.username}")
    private String adminUsername;

    @Value("${admin.password}")
    private String adminPassword;

    /**
     * 관리자가 토론에서 보일 이름. 닉네임은 가입 화면에서 받는데 이 계정은 가입을 거치지 않아
     * 여기서 붙인다. 기본값을 "관리자"로 두면 그 이름을 남이 먼저 가져갈 수도 없다(유일 제약).
     */
    @Value("${admin.nickname:관리자}")
    private String adminNickname;

    @Override
    public void run(ApplicationArguments args) {
        boolean adminExists = userRepository.findAll().stream()
                .anyMatch(u -> u.getRole() == Role.ADMIN); // 회원 수가 적은 MVP라 전체 스캔으로 충분
        String username = adminUsername.trim().toLowerCase();
        if (adminExists) {
            fillMissingNickname(username);
            return;
        }
        User admin = User.builder()
                // 아이디는 소문자로 낮춰 저장한다 — AuthService.normalize와 같은 규칙이어야
                // 설정에 대문자를 적어 둔 날 "만들어졌는데 로그인이 안 되는" 계정이 생기지 않는다.
                .username(username)
                .passwordHash(passwordEncoder.encode(adminPassword))
                .role(Role.ADMIN)
                .build();
        admin.changeNickname(adminNickname);
        userRepository.save(admin);
        // 비밀번호는 절대 로그에 남기지 않는다 — 아이디까지만.
        log.info("초기 관리자 계정 생성: {} (비밀번호는 application.yml의 admin.password / 환경변수 ADMIN_PASSWORD)", adminUsername);
    }

    /**
     * 닉네임이 생기기 전(V21 이전)에 만들어진 관리자 계정에 닉네임을 채운다.
     * 이미 정해 둔 닉네임은 건드리지 않는다 — 마이페이지에서 바꾼 값을 부팅이 되돌리면 안 된다.
     */
    private void fillMissingNickname(String username) {
        userRepository.findByUsername(username)
                .filter(admin -> admin.getNickname() == null)
                .filter(admin -> !userRepository.existsByNickname(adminNickname))
                .ifPresent(admin -> {
                    admin.changeNickname(adminNickname);
                    userRepository.save(admin);
                    log.info("관리자 계정에 닉네임을 채웠습니다: {}", username);
                });
    }
}
