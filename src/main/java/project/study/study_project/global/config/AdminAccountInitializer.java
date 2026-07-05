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

    @Value("${admin.email}")
    private String adminEmail;

    @Value("${admin.password}")
    private String adminPassword;

    @Override
    public void run(ApplicationArguments args) {
        boolean adminExists = userRepository.findAll().stream()
                .anyMatch(u -> u.getRole() == Role.ADMIN); // 회원 수가 적은 MVP라 전체 스캔으로 충분
        if (adminExists) {
            return;
        }
        userRepository.save(User.builder()
                .email(adminEmail)
                .passwordHash(passwordEncoder.encode(adminPassword))
                .role(Role.ADMIN)
                .build());
        // 비밀번호는 절대 로그에 남기지 않는다 — 이메일까지만.
        log.info("초기 관리자 계정 생성: {} (비밀번호는 application.yml의 admin.password / 환경변수 ADMIN_PASSWORD)", adminEmail);
    }
}
