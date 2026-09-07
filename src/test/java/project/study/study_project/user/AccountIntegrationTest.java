package project.study.study_project.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.auth.jwt.JwtTokenProvider;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 계정 관리(비밀번호 변경 · 탈퇴) 통합 테스트 (2026-09-08).
 *
 * <h2>왜 이 기능이 뒤늦게 생겼나</h2>
 *
 * <p>기능 점검(REVIEW_2026-09-07-features 4장)에서 나온 3순위다. 한 번 정한 비밀번호를
 * <b>바꿀 수도, 계정을 지울 수도 없었다.</b> 가입 화면에 확인 칸을 넣어 오타로 잃는 일은
 * 막았지만(2026-09-07), 스스로 관리할 수단이 아예 없는 것은 그대로였다.
 *
 * <p><b>비밀번호 재설정(찾기)은 여기 없다.</b> 이메일을 안 받으므로 보낼 곳이 없다 —
 * 구현을 미룬 것이 아니라 <b>지금 구조에서 불가능</b>하다. 소셜 로그인이 붙으면 그 자체가
 * 복구 경로가 되므로 그때 함께 정한다. 화면은 그 사실을 숨기지 않고 적는다.
 *
 * <h2>왜 둘 다 지금 비밀번호를 요구하나</h2>
 *
 * <p>둘 다 <b>되돌릴 수 없거나 위험한</b> 동작이다. 잠깐 자리를 비운 사이 남이 브라우저를
 * 만지면, 확인 절차가 없을 때 비밀번호가 바뀌어 계정을 통째로 빼앗긴다. 토큰이 있다는 것은
 * "이 브라우저가 로그인했다"이지 "지금 앉아 있는 사람이 본인이다"가 아니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AccountIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired ProblemRepository problemRepository;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;

    private static final String 지금비밀번호 = "OldPass12345";
    private static final String 새비밀번호 = "NewPass67890";

    private User user;
    private String token;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.builder()
                .username("acct" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash(passwordEncoder.encode(지금비밀번호))
                .role(Role.USER)
                .build());
        token = jwtTokenProvider.createToken(user.getId(), Role.USER);
    }

    /* ── 비밀번호 변경 ── */

    @Test
    @DisplayName("비밀번호를 바꾸면 새 것으로 로그인되고 옛 것으로는 안 된다")
    void changePassword() throws Exception {
        mockMvc.perform(patch("/api/me/password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("currentPassword", 지금비밀번호, "newPassword", 새비밀번호))))
                .andExpect(status().isOk());

        // 저장된 것이 <해시>인지도 함께 본다. 원문이 들어가면 이 단언이 통과하지 못한다.
        User 바뀐 = userRepository.findById(user.getId()).orElseThrow();
        assertThat(바뀐.getPasswordHash()).isNotEqualTo(새비밀번호);
        assertThat(passwordEncoder.matches(새비밀번호, 바뀐.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches(지금비밀번호, 바뀐.getPasswordHash())).isFalse();
    }

    @Test
    @DisplayName("지금 비밀번호가 틀리면 안 바뀐다")
    void changePasswordRejectsWrongCurrent() throws Exception {
        mockMvc.perform(patch("/api/me/password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("currentPassword", "틀린비밀번호1", "newPassword", 새비밀번호))))
                .andExpect(status().isUnauthorized());

        assertThat(passwordEncoder.matches(지금비밀번호,
                userRepository.findById(user.getId()).orElseThrow().getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("새 비밀번호가 규칙에 안 맞으면 거절한다 — 가입과 같은 규칙")
    void changePasswordEnforcesSameRuleAsSignup() throws Exception {
        // 가입은 8자 이상 + 영문·숫자를 요구한다. 변경만 느슨하면 그 문으로 약한 것이 들어온다.
        mockMvc.perform(patch("/api/me/password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("currentPassword", 지금비밀번호, "newPassword", "short"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    /* ── 탈퇴 ── */

    @Test
    @DisplayName("탈퇴하면 계정과 내 기록이 함께 사라진다 — 손자 행(데일리 항목)까지")
    void withdrawRemovesAccountAndRecords() throws Exception {
        /* 자식 행이 있는 채로 지워지는지가 이 테스트의 요점이다.
         *
         * <데일리 세트를 반드시 만든다.> 처음에는 제출만 남기고 통과시켰는데, 브라우저로
         * 눌러 보니 500이 났다 — 데일리 세트가 있는 계정에서만 터졌다.
         * daily_quiz는 아래에 daily_quiz_item을 거느린 <손자가 있는> 유일한 갈래라,
         * 그것이 없는 계정으로는 삭제 순서 문제가 드러나지 않는다.
         * 테스트가 통과한 것이 아니라 <테스트가 그 상황을 안 만들고 있었다.> */
        Problem p = problemRepository.save(ox());
        mockMvc.perform(post("/api/quiz/submit")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("problemId", p.getId(), "userAnswer", "X"))))
                .andExpect(status().isOk());

        // 오늘의 세트를 만든다(처음 부르면 그 자리에서 만들어진다 — DailyQuizService)
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/me/daily-quiz")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("password", 지금비밀번호))))
                .andExpect(status().isOk());

        /* flush를 <반드시> 부른다.
         *
         * 이 테스트는 @Transactional이라 롤백되고, 그 안에서는 삭제 SQL이 커밋 직전까지
         * 미뤄진다. 게다가 findById는 영속성 컨텍스트에서 답해 버리므로 DB까지 가지도
         * 않는다 — 실제로 이 테스트는 <외래 키 위반이 나는 코드를 통과시켰다.>
         * 브라우저로 눌러 봤을 때만 500이 났다.
         *
         * flush가 대기 중인 SQL을 지금 내보내므로, 제약을 어기면 여기서 터진다. */
        userRepository.flush();
        assertThat(userRepository.findById(user.getId())).isEmpty();
    }

    @Test
    @DisplayName("비밀번호가 틀리면 탈퇴되지 않는다")
    void withdrawRejectsWrongPassword() throws Exception {
        mockMvc.perform(delete("/api/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("password", "틀린비밀번호1"))))
                .andExpect(status().isUnauthorized());

        assertThat(userRepository.findById(user.getId())).isPresent();
    }

    /* ── 헬퍼 ── */

    private String json(Map<String, ?> body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private Problem ox() {
        return Problem.create(Domain.NETWORK, Difficulty.BEGINNER, ProblemType.OX,
                null, "탈퇴 확인용 " + UUID.randomUUID(), "O", "해설", null);
    }
}
