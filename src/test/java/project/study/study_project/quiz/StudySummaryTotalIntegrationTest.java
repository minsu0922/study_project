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
import project.study.study_project.TestDomains;
import project.study.study_project.auth.jwt.JwtTokenProvider;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.DomainCode;
import project.study.study_project.llm.repository.DomainSettingRepository;
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
 * 분야별 진척의 <b>분모</b> 통합 테스트 (2026-09-06 화면 개편).
 *
 * <h2>왜 이 필드가 뒤늦게 생겼나</h2>
 *
 * <p>예전에는 <b>일부러 안 줬다.</b> {@code StudySummaryResponse}의 주석이 이유를 적어 두었다 —
 * 배치가 매일 문제를 더해 분모가 커지므로 "어제 40%가 오늘 37%"가 되고, 아무것도 잘못하지
 * 않았는데 뒷걸음질친 것처럼 보인다는 것이다.
 *
 * <p>화면 개편에서 분야별 진도를 <b>막대</b>로 보여 주기로 하면서 분모가 필요해졌다.
 * 숫자만 늘어놓으면 40개 중 31개와 33개 중 9개가 같은 무게로 읽힌다. 걱정 자체는 여전히
 * 맞아서 <b>화면에서</b> 눌렀다 — 주인공은 절대값(31 / 40)이고 퍼센트는 아예 안 쓴다.
 *
 * <h2>왜 통합 테스트인가</h2>
 *
 * <p>조용히 고장 날 자리가 둘이고 단위 테스트로는 하나도 못 본다.
 * <ol>
 *   <li><b>{@code group by}는 해당 행이 없는 분야를 아예 안 준다.</b> 문제가 0개인 분야가
 *       응답에서 사라지면 화면에서 그 분야가 통째로 없어진다 — 정작 "여기부터 해 볼까"의
 *       후보가 안 보이게 된다. 서비스가 {@code DefaultDomains.codes()}로 빈 칸을 채우는지 봐야 한다.
 *   <li><b>프로젝션 인터페이스의 별칭이 게터 이름과 어긋나면 런타임에만 터진다.</b>
 *       부팅도 되고 컴파일도 된다.
 * </ol>
 *
 * <h2>왜 절대 개수가 아니라 "늘어난 만큼"을 보나</h2>
 *
 * <p>이 테스트는 클래스 {@code @Transactional}로 롤백되지만, <b>다른 문제들이 이미 DB에
 * 들어 있다</b>(로컬 개발 DB에도, CI에도). "네트워크는 2개"라고 단정하면 그 환경에서만
 * 통과하는 테스트가 된다. 그래서 <b>먼저 재고, 문제를 넣고, 그만큼 늘었는지</b> 본다 —
 * 데이터가 얼마나 있든 성립한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StudySummaryTotalIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired ProblemRepository problemRepository;
    /** 기대 분야 수를 등록부에서 읽는다 — 분야는 관리자가 화면에서 늘리고 줄인다. */
    @Autowired DomainSettingRepository domainSettingRepository;
    @Autowired JwtTokenProvider jwtTokenProvider;

    private String token;

    @BeforeEach
    void setUp() {
        User user = userRepository.save(User.builder()
                .username("summary" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("bcrypt-not-needed-here")
                .role(Role.USER)
                .build());
        token = jwtTokenProvider.createToken(user.getId(), Role.USER);
    }

    @Test
    @DisplayName("분야별 진척에 그 분야의 전체 문제 수가 함께 온다")
    void domainProgressCarriesTotal() throws Exception {
        long networkBefore = totalOf(TestDomains.NETWORK);
        long databaseBefore = totalOf(TestDomains.DATABASE);

        problemRepository.save(ox(TestDomains.NETWORK, "네트워크 분모 확인 1"));
        problemRepository.save(ox(TestDomains.NETWORK, "네트워크 분모 확인 2"));
        problemRepository.save(ox(TestDomains.DATABASE, "DB 분모 확인 1"));

        mockMvc.perform(summaryRequest())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.domains[?(@.domain == 'NETWORK')].total")
                        .value((int) networkBefore + 2))
                .andExpect(jsonPath("$.data.domains[?(@.domain == 'DATABASE')].total")
                        .value((int) databaseBefore + 1));
    }

    @Test
    @DisplayName("모든 분야가 응답에 남고, 분모는 빠짐없이 채워진다")
    void everyDomainStaysInTheResponse() throws Exception {
        // group by는 문제가 0개인 분야를 안 준다. 그대로 내려보내면 화면에서
        // 손대지 않은 분야가 사라져 "여기부터 해 볼까"의 후보가 안 보인다.
        // 기대값은 등록부의 행 수다. 기본 목록 크기를 박아 두면 관리자가 분야를 하나 추가한
        // 순간 깨지는데, 그건 이 화면의 버그가 아니다 — 새 분야도 목록에 나오는 것이 맞다
        // (2026-09-23에 TEST 분야를 추가하며 실제로 겪었다).
        int registered = (int) domainSettingRepository.count();

        mockMvc.perform(summaryRequest())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.domains.length()").value(registered))
                // 하나라도 total이 빠지면 화면에서 그 줄의 막대가 NaN이 되어 사라진다.
                //
                // hasSize를 쓰는 이유: 필터식 뒤에 .length()를 붙이면 JsonPath가 그것을
                // <걸러진 배열의 길이>가 아니라 <각 원소의 길이>로 읽어, 객체 필드 수가
                // 원소 수만큼 담긴 목록이 나온다. 한 번 그렇게 틀렸다.
                .andExpect(jsonPath("$.data.domains[?(@.total >= 0)]")
                        .value(hasSize(registered)));
    }

    /* ── 헬퍼 ── */

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder summaryRequest() {
        return get("/api/me/study-summary").header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    /** 지금 응답에 찍힌 그 분야의 분모. 없으면 0 — 필드가 아직 없을 때도 이 호출은 성립한다. */
    private long totalOf(DomainCode domain) throws Exception {
        String body = mockMvc.perform(summaryRequest())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        java.util.List<Integer> found = com.jayway.jsonpath.JsonPath.parse(body)
                .read("$.data.domains[?(@.domain == '" + domain.value() + "')].total");
        return found.isEmpty() || found.get(0) == null ? 0L : found.get(0).longValue();
    }

    /** 정답 "O" 고정 OX — 이 테스트는 채점을 하지 않으므로 내용은 아무래도 좋다. */
    private Problem ox(DomainCode domain, String question) {
        return Problem.create(domain, Difficulty.BEGINNER, ProblemType.OX,
                null, question + " " + UUID.randomUUID(), "O", "해설", null);
    }
}
