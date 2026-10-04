package project.study.study_project.discussion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.auth.jwt.JwtTokenProvider;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "ratelimit.enabled=false")
@AutoConfigureMockMvc
@Transactional
class NicknameIntegrationTest {

    private static final String PATH = "/api/me/nickname";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("비로그인은 닉네임을 볼 수도 정할 수도 없다")
    void requiresLogin() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mockMvc.perform(put(PATH).contentType("application/json").content(body("민수")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("처음에는 비어 있고, 정하면 그 값이 돌아온다")
    void setsNickname() throws Exception {
        String token = bearer();

        mockMvc.perform(get(PATH).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").doesNotExist());

        mockMvc.perform(put(PATH).header("Authorization", token)
                        .contentType("application/json").content(body("민수_" + suffix())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").exists());
    }

    @Test
    @DisplayName("남이 쓰는 이름은 409 DISCUSSION_004")
    void rejectsDuplicate() throws Exception {
        String name = "dup" + suffix();
        mockMvc.perform(put(PATH).header("Authorization", bearer())
                        .contentType("application/json").content(body(name)))
                .andExpect(status().isOk());

        mockMvc.perform(put(PATH).header("Authorization", bearer())
                        .contentType("application/json").content(body(name)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DISCUSSION_004"));
    }

    /** 화면에서 두 사람이 구분되지 않는다 — user 표의 콜레이션(ai_ci)이 같은 값으로 본다. */
    @Test
    @DisplayName("대소문자만 다른 이름도 중복이다")
    void rejectsCaseOnlyDifference() throws Exception {
        String name = "Case" + suffix();
        mockMvc.perform(put(PATH).header("Authorization", bearer())
                        .contentType("application/json").content(body(name)))
                .andExpect(status().isOk());

        mockMvc.perform(put(PATH).header("Authorization", bearer())
                        .contentType("application/json").content(body(name.toLowerCase())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DISCUSSION_004"));
    }

    @Test
    @DisplayName("같은 이름으로 다시 저장하는 것은 중복이 아니다")
    void allowsSavingOwnNicknameAgain() throws Exception {
        String token = bearer();
        String name = "same" + suffix();
        mockMvc.perform(put(PATH).header("Authorization", token)
                        .contentType("application/json").content(body(name)))
                .andExpect(status().isOk());
        mockMvc.perform(put(PATH).header("Authorization", token)
                        .contentType("application/json").content(body(name)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("2~12자, 한글·영문·숫자·밑줄만 받는다")
    void validatesFormat() throws Exception {
        String token = bearer();
        for (String bad : new String[]{"가", "열세글자를넘기는아주긴닉네임", "공백 있음", "<b>굵게</b>", ""}) {
            mockMvc.perform(put(PATH).header("Authorization", token)
                            .contentType("application/json").content(body(bad)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    @DisplayName("운영진으로 보이는 닉네임으로는 바꿀 수 없다 — 관리자 본인은 쓸 수 있다")
    void reservedNicknameIsForAdminsOnly() throws Exception {
        mockMvc.perform(put(PATH).header("Authorization", bearer())
                        .contentType("application/json").content(body("운영자" + suffix().substring(0, 3))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("DISCUSSION_010"));

        User admin = userRepository.save(User.builder()
                .username("nick" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash(passwordEncoder.encode("password123"))
                .role(Role.ADMIN)
                .build());
        mockMvc.perform(put(PATH).header("Authorization",
                                "Bearer " + jwtTokenProvider.createToken(admin.getId(), Role.ADMIN))
                        .contentType("application/json").content(body("운영자" + suffix().substring(0, 3))))
                .andExpect(status().isOk());
    }

    private String body(String nickname) {
        return "{\"nickname\":\"%s\"}".formatted(nickname);
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 6);
    }

    private String bearer() {
        User user = userRepository.save(User.builder()
                .username("nick" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash(passwordEncoder.encode("password123"))
                .role(Role.USER)
                .build());
        return "Bearer " + jwtTokenProvider.createToken(user.getId(), Role.USER);
    }
}
