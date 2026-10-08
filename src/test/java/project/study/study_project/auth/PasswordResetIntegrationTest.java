package project.study.study_project.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.TestFixtures;
import project.study.study_project.auth.dto.LoginRequest;
import project.study.study_project.auth.dto.PasswordResetRequest;
import project.study.study_project.auth.dto.SignupRequest;
import project.study.study_project.auth.dto.SignupResponse;
import project.study.study_project.auth.service.AuthService;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.service.AccountService;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 복구 코드(V32) — 가입 때 받은 코드로 비밀번호를 다시 정하고, 쓴 코드는 다시 통하지 않는지. */
@SpringBootTest
@Transactional
class PasswordResetIntegrationTest {

    private static final String NEW_PASSWORD = "newPassw0rd";

    @Autowired
    private AuthService authService;
    @Autowired
    private AccountService accountService;
    @Autowired
    private TestFixtures fixtures;

    @Test
    @DisplayName("가입 때 받은 복구 코드로 비밀번호를 다시 정하면 새 비밀번호로 로그인된다")
    void resetWithCodeFromSignup() {
        SignupResponse signup = signup();

        authService.resetPassword(new PasswordResetRequest(signup.username(), signup.recoveryCode(), NEW_PASSWORD));

        assertThat(authService.login(new LoginRequest(signup.username(), NEW_PASSWORD)).accessToken()).isNotBlank();
        assertThatThrownBy(() -> authService.login(new LoginRequest(signup.username(), TestFixtures.PASSWORD)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("줄표를 빼거나 소문자로 쳐도 같은 코드로 본다")
    void codeIsNormalized() {
        SignupResponse signup = signup();
        String sloppy = signup.recoveryCode().replace("-", " ").toLowerCase();

        authService.resetPassword(new PasswordResetRequest(signup.username(), sloppy, NEW_PASSWORD));

        assertThat(authService.login(new LoginRequest(signup.username(), NEW_PASSWORD)).accessToken()).isNotBlank();
    }

    @Test
    @DisplayName("한 번 쓴 코드는 다시 통하지 않고, 응답으로 받은 새 코드는 통한다")
    void usedCodeIsRotated() {
        SignupResponse signup = signup();
        String next = authService.resetPassword(
                new PasswordResetRequest(signup.username(), signup.recoveryCode(), NEW_PASSWORD)).recoveryCode();

        assertThat(next).isNotEqualTo(signup.recoveryCode());
        assertRejected(signup.username(), signup.recoveryCode());

        authService.resetPassword(new PasswordResetRequest(signup.username(), next, "another9Pass"));
        assertThat(authService.login(new LoginRequest(signup.username(), "another9Pass")).accessToken()).isNotBlank();
    }

    @Test
    @DisplayName("틀린 코드와 없는 아이디는 같은 오류로 답한다")
    void wrongCodeAndUnknownUserLookTheSame() {
        SignupResponse signup = signup();

        assertRejected(signup.username(), "AAAA-AAAA-AAAA-AAAA-AAAA");
        assertRejected("nobody" + key(), signup.recoveryCode());
    }

    @Test
    @DisplayName("코드를 받은 적 없는 옛 계정은 재설정이 막히고, 마이페이지에서 받으면 풀린다")
    void legacyAccountMustIssueFirst() {
        User legacy = fixtures.user(Role.USER);   // 가입 API를 거치지 않아 코드가 없다
        assertThat(accountService.hasRecoveryCode(legacy.getId())).isFalse();
        assertRejected(legacy.getUsername(), "AAAA-AAAA-AAAA-AAAA-AAAA");

        String code = accountService.issueRecoveryCode(legacy.getId(), TestFixtures.PASSWORD);

        assertThat(accountService.hasRecoveryCode(legacy.getId())).isTrue();
        authService.resetPassword(new PasswordResetRequest(legacy.getUsername(), code, NEW_PASSWORD));
        assertThat(authService.login(new LoginRequest(legacy.getUsername(), NEW_PASSWORD)).accessToken()).isNotBlank();
    }

    @Test
    @DisplayName("재발급은 지금 비밀번호가 맞아야 하고, 전 코드를 무효로 만든다")
    void reissueRequiresPasswordAndInvalidatesOldCode() {
        SignupResponse signup = signup();
        assertThatThrownBy(() -> accountService.issueRecoveryCode(signup.id(), "wrong-password1"))
                .isInstanceOf(BusinessException.class);

        accountService.issueRecoveryCode(signup.id(), TestFixtures.PASSWORD);

        assertRejected(signup.username(), signup.recoveryCode());
    }

    private void assertRejected(String username, String code) {
        assertThatThrownBy(() -> authService.resetPassword(new PasswordResetRequest(username, code, NEW_PASSWORD)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AUTH_006));
    }

    private SignupResponse signup() {
        return authService.signup(new SignupRequest("reset" + key(), TestFixtures.PASSWORD, "닉" + key()));
    }

    private String key() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
