package project.study.study_project.quiz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.auth.jwt.JwtTokenProvider;
import project.study.study_project.document.domain.Document;
import project.study.study_project.document.repository.DocumentRepository;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 키워드 검색 통합 테스트 (2026-09-08).
 *
 * <h2>왜 필요해졌나</h2>
 *
 * <p>기능 점검(REVIEW_2026-09-07-features 3장)에서 나온 2순위다. 문제 목록은 도메인·난이도·
 * 상태만, 문서 목록은 도메인만 받고 있어 <b>말로 찾는 길이 어디에도 없었다.</b>
 * 지금은 문제 98개라 필터로 견디지만, 이 프로젝트는 배치가 <b>매일 문제를 만들어 쌓는</b>
 * 구조다. 200~300개가 되면 "그 TIME_WAIT 문제 다시 보고 싶다"를 할 방법이 없어진다.
 *
 * <h2>왜 유일한 값으로 검사하나</h2>
 *
 * <p>이 테스트는 롤백되지만 <b>다른 문제·문서가 이미 DB에 들어 있다</b>(로컬에도 CI에도).
 * "네트워크 문제 2건"처럼 절대 개수를 단정하면 그 환경에서만 통과하는 테스트가 된다.
 * 그래서 검색어에 UUID를 섞어 <b>이 테스트가 만든 것만 걸리게</b> 한다 — 데이터가 얼마나
 * 있든 성립한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class KeywordSearchIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired ProblemRepository problemRepository;
    @Autowired DocumentRepository documentRepository;
    @Autowired JwtTokenProvider jwtTokenProvider;

    private String token;
    /** 이 실행에서만 쓰는 말. 다른 데이터에 절대 없으므로 걸리는 것은 우리가 넣은 것뿐이다. */
    private String 고유어;

    @BeforeEach
    void setUp() {
        User user = userRepository.save(User.builder()
                .username("search" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("bcrypt-not-needed-here")
                .role(Role.USER)
                .build());
        token = jwtTokenProvider.createToken(user.getId(), Role.USER);
        고유어 = "zzq" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    @DisplayName("문제 — 지문에 든 말로 찾는다")
    void findsProblemByQuestionText() throws Exception {
        problemRepository.save(ox("소켓이 " + 고유어 + " 상태로 쌓인다"));
        problemRepository.save(ox("전혀 상관없는 다른 문제"));

        mockMvc.perform(get("/api/problems").param("keyword", 고유어)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value(hasSize(1)));
    }

    @Test
    @DisplayName("문제 — 없는 말로 찾으면 빈 목록이다(전체가 아니다)")
    void unknownKeywordReturnsNothing() throws Exception {
        // 조건을 잘못 이으면 "못 찾았으니 전체를 준다"가 되기 쉽다. 그러면 사용자는
        // 검색이 된 줄 알고 엉뚱한 목록을 훑는다 — 아무것도 안 나오는 것보다 나쁘다.
        mockMvc.perform(get("/api/problems").param("keyword", "없는말" + 고유어)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value(hasSize(0)));
    }

    @Test
    @DisplayName("문제 — 검색어가 비어 있으면 거르지 않는다")
    void blankKeywordDoesNotFilter() throws Exception {
        problemRepository.save(ox("빈 검색어 확인용 " + 고유어));

        // 화면이 빈 칸을 그대로 보내는 일이 흔하다. 그때 "아무것도 없음"이 되면
        // 목록이 통째로 사라진 것처럼 보인다.
        mockMvc.perform(get("/api/problems").param("keyword", "  ")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").isNumber());
    }

    @Test
    @DisplayName("문서 — 제목으로도 본문으로도 찾는다")
    void findsDocumentByTitleAndBody() throws Exception {
        documentRepository.save(doc("제목에 " + 고유어 + " 들어감", "본문은 평범하다", "t1"));
        documentRepository.save(doc("평범한 제목", "본문 깊은 곳에 " + 고유어 + " 가 있다", "t2"));
        documentRepository.save(doc("상관없는 문서", "상관없는 본문", "t3"));

        mockMvc.perform(get("/api/documents").param("keyword", 고유어))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value(hasSize(2)));
    }

    @Test
    @DisplayName("문서 — 대소문자를 가리지 않는다")
    void documentSearchIgnoresCase() throws Exception {
        documentRepository.save(doc("TIME_WAIT " + 고유어.toUpperCase() + " 정리", "본문", "t4"));

        mockMvc.perform(get("/api/documents").param("keyword", 고유어.toLowerCase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value(hasSize(1)));
    }

    @Test
    @DisplayName("문제 — 근거 문서로 좁힌다(문서 → 문제 고리)")
    void findsProblemsByDocumentSlug() throws Exception {
        String slug = "doc-" + 고유어;
        problemRepository.save(ox("이 문서로 만든 문제 1", slug));
        problemRepository.save(ox("이 문서로 만든 문제 2", slug));
        problemRepository.save(ox("다른 문서로 만든 문제", "doc-other-" + 고유어));
        problemRepository.save(ox("근거 문서가 없는 문제", null));

        mockMvc.perform(get("/api/problems").param("documentSlug", slug)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value(hasSize(2)));
    }

    @Test
    @DisplayName("문제 — 근거 문서와 검색어를 함께 걸 수 있다")
    void documentSlugCombinesWithKeyword() throws Exception {
        // 화면이 둘을 함께 보낼 일은 드물지만, 필터끼리 배타적이면 나중에 조합할 때
        // 하나가 조용히 무시된다. and로 이어졌는지 확인해 둔다.
        String slug = "doc-" + 고유어;
        problemRepository.save(ox("소켓 이야기 " + 고유어, slug));
        problemRepository.save(ox("전혀 다른 이야기", slug));

        mockMvc.perform(get("/api/problems")
                        .param("documentSlug", slug)
                        .param("keyword", 고유어)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value(hasSize(1)));
    }

    /* ── 헬퍼 ── */

    private Problem ox(String question) {
        return ox(question, null);
    }

    private Problem ox(String question, String documentSlug) {
        return Problem.create(Domain.NETWORK, Difficulty.BEGINNER, ProblemType.OX,
                null, question, "O", "해설", documentSlug);
    }

    private Document doc(String title, String body, String suffix) {
        return Document.create(Domain.NETWORK, title,
                "search-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 8),
                body, null, java.util.Set.of());
    }
}
