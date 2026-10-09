package project.study.study_project.global.config;

import project.study.study_project.user.config.AdminAccountInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 관리자 계정은 가입 화면을 거치지 않는다. 닉네임을 가입 때 받게 되면서(2026-10-04) 이 계정만
 * 닉네임 없이 남으므로, 여기서 따로 붙여 준다.
 */
class AdminAccountInitializerTest {

    private UserRepository userRepository;
    private AdminAccountInitializer initializer;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(any())).thenReturn("hashed");
        initializer = new AdminAccountInitializer(userRepository, encoder);
        ReflectionTestUtils.setField(initializer, "adminUsername", "admin");
        ReflectionTestUtils.setField(initializer, "adminPassword", "pw");
        ReflectionTestUtils.setField(initializer, "adminNickname", "관리자");
    }

    @Test
    @DisplayName("관리자를 새로 만들 때 닉네임을 함께 넣는다")
    void createsAdminWithNickname() {
        when(userRepository.findAll()).thenReturn(List.of());

        initializer.run(null);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getRole()).isEqualTo(Role.ADMIN);
        assertThat(saved.getValue().getNickname()).isEqualTo("관리자");
    }

    @Test
    @DisplayName("이미 있는 관리자 계정에 닉네임이 없으면 채운다")
    void fillsMissingNicknameOfExistingAdmin() {
        User admin = User.builder().username("admin").passwordHash("h").role(Role.ADMIN).build();
        when(userRepository.findAll()).thenReturn(List.of(admin));
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(userRepository.existsByNickname("관리자")).thenReturn(false);

        initializer.run(null);

        assertThat(admin.getNickname()).isEqualTo("관리자");
        verify(userRepository).save(admin);
    }

    @Test
    @DisplayName("관리자가 이미 정한 닉네임은 건드리지 않는다")
    void keepsExistingNickname() {
        User admin = User.builder().username("admin").passwordHash("h").role(Role.ADMIN).build();
        admin.changeNickname("테테테");
        when(userRepository.findAll()).thenReturn(List.of(admin));
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));

        initializer.run(null);

        assertThat(admin.getNickname()).isEqualTo("테테테");
        verify(userRepository, never()).save(any());
    }
}
