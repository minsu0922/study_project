package project.study.study_project.global.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 응답에 CSP가 실리는지, API 문서가 기본 설정에서 닫혀 있는지 — 둘 다 설정 한 줄로 조용히 풀린다. */
@SpringBootTest(properties = "ratelimit.enabled=false")
@AutoConfigureMockMvc
class SecurityHeadersIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("화면과 API 응답에 CSP가 실린다 — 바깥으로 나가는 요청은 jsdelivr의 스크립트·글꼴뿐이다")
    void contentSecurityPolicyIsSent() throws Exception {
        for (String path : new String[]{"/login.html", "/api/domains"}) {
            mockMvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Security-Policy", allOf(
                            containsString("default-src 'self'"),
                            containsString("connect-src 'self'"),
                            containsString("frame-ancestors 'none'"))));
        }
    }

    @Test
    @DisplayName("API 문서는 기본 설정에서 닫혀 있다 — 개발 프로필에서만 연다")
    void apiDocsAreClosedByDefault() throws Exception {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isNotFound());
    }
}
