package project.study.study_project.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관리 화면 출입증 — <b>진짜 톰캣</b>을 띄우고 확인하는 회귀 테스트(2026-08-29 신설).
 *
 * <h2>왜 MockMvc로는 부족한가</h2>
 *
 * <p>{@link AdminGateIntegrationTest}가 이미 "출입증이 없으면 404"를 검증하고 있었고 <b>초록불이었다.</b>
 * 그런데 브라우저로 {@code /admin/index.html}을 열면 실제로는 <b>401 JSON</b>이 떴다.
 *
 * <p>원인은 {@code sendError}가 일으키는 서블릿의 <b>ERROR 디스패치</b>였다. 요청이 {@code /error}로
 * 다시 들어가고 그 경로는 인증을 요구해서, 필터가 정한 404를 인증 엔트리포인트가 덮어썼다.
 * <b>MockMvc는 그 재진입을 재현하지 않는다</b> — 응답 객체에 찍힌 상태 코드만 보므로 404로 보인다.
 *
 * <p>즉 이 결함은 "필터가 틀렸다"가 아니라 <b>"필터 뒤에 무엇이 이어 붙는가"</b>의 문제라,
 * 필터 사슬만 흉내 내는 도구로는 원리상 볼 수 없다. 그래서 이 클래스만 진짜 서버를 띄운다.
 *
 * <p><b>이 저장소의 첫 RANDOM_PORT 테스트다.</b> 다른 통합 테스트는 MockMvc로 충분하고 그쪽이
 * 훨씬 빠르다 — 여기서만 컨테이너가 필요한 이유가 위에 적힌 그것 하나뿐이므로, 이 방식을
 * 다른 테스트로 넓히지 않는다.
 *
 * <p>MySQL이 필요하다(다른 통합 테스트와 같은 전제). 쓰기를 하지 않아 롤백할 것도 없다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ratelimit.enabled=false")
class AdminGateRealServerTest {

    @Autowired
    private TestRestTemplate restTemplate;

    /**
     * 이 테스트가 지키는 것은 상태 코드만이 아니다. <b>본문이 비어 있어야</b> 한다 —
     * 우리가 어떤 문구든 남기면 그 문구가 곧 "여기 뭔가 있다"는 단서가 되고,
     * 401 JSON이 새어 나오던 상태와 실질적으로 같아진다.
     */
    @Test
    @DisplayName("출입증 없이 관리 화면을 열면 진짜 서버에서도 404이고 본문이 비어 있다")
    void hidesAdminPageOnARealServer() {
        ResponseEntity<String> response = restTemplate.getForEntity("/admin/index.html", String.class);

        assertThat(response.getStatusCode())
                .as("401이면 '여기 뭔가 있다'를 알려 주는 셈이다 — 감추기로 한 결정이 무효가 된다")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody())
                .as("문구를 남기면 그 자체가 단서다. 브라우저 기본 404 화면이 나가야 한다")
                .isNull();
    }

    /**
     * 회귀의 정확한 모양을 못 박는다. 상태 코드만 보면 나중에 누군가 "404 JSON 본문"을
     * 붙여도 통과하는데, 그건 다시 존재를 알려 주는 것이다.
     */
    @Test
    @DisplayName("응답이 우리 공통 JSON 봉투가 아니다 — 에러 처리기로 다시 들어가지 않았다는 증거")
    void doesNotFallIntoTheJsonErrorHandler() {
        ResponseEntity<String> response = restTemplate.getForEntity("/admin/", String.class);

        assertThat(response.getHeaders().getContentType())
                .as("APPLICATION_JSON이면 /error로 재진입해 엔트리포인트가 응답을 만든 것이다")
                .isNotEqualTo(MediaType.APPLICATION_JSON);

        // 본문은 지금 null이다(비운 응답). 그래도 null 자체를 단언하지는 않는다 —
        // 이 테스트가 지키려는 것은 "비어 있음"이 아니라 "에러 코드가 새지 않음"이고,
        // 앞 테스트가 이미 비어 있음을 본다. 빈 문자열로 바꿔 두 경우를 함께 통과시킨다.
        String body = response.getBody() == null ? "" : response.getBody();
        assertThat(body).doesNotContain("AUTH_003");
    }

    /**
     * 반대 방향도 함께 본다. 감추기를 고치다가 <b>공개 화면까지 잠그면</b> 로그인 자체가 막혀
     * 관리자도 영영 못 들어온다 — 이 기능에서 가장 비싼 실수다.
     */
    @Test
    @DisplayName("공개 화면은 진짜 서버에서도 그대로 열린다")
    void keepsPublicPagesOpenOnARealServer() {
        assertThat(restTemplate.getForEntity("/login.html", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }
}
