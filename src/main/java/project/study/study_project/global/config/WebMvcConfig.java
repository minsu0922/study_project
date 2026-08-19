package project.study.study_project.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 화면 경로 보정 — {@code /admin}·{@code /admin/}으로 들어와도 관리 대시보드가 열리게 한다.
 *
 * <p><b>왜 필요한가.</b> 스프링의 정적 리소스 처리기는 디렉터리 경로를 {@code index.html}로
 * 바꿔 주지 않는다(그건 최상위 {@code /}에만 적용되는 환영 페이지 규칙이다). 그래서
 * {@code /admin/}은 "디렉터리라 읽을 수 없음" → 404가 된다. 링크는 전부
 * {@code /admin/index.html}을 가리키므로 동작에는 지장이 없지만, 주소창에 {@code /admin}을
 * 쳐 보는 것은 사람이 가장 먼저 하는 일이라 그 자리에서 404를 만나면 <b>기능이 없는 줄 안다</b>.
 *
 * <p><b>출입증 검사를 건너뛰지 않는다.</b> {@code AdminGateFilter}는 브라우저가 보낸
 * <b>원래 요청</b>({@code /admin/})에서 이미 판정을 끝낸다. 포워드는 그 뒤에 서버 안에서
 * 일어나는 일이라, 이 보정이 게이트에 구멍을 내지 않는다 — 반대로 리다이렉트(302)를 썼다면
 * 인증 없이도 "뭔가 있다"는 신호가 새어 나갔을 것이다.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/admin").setViewName("forward:/admin/index.html");
        registry.addViewController("/admin/").setViewName("forward:/admin/index.html");
    }
}
