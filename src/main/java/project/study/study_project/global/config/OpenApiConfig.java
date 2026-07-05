package project.study.study_project.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger(OpenAPI) 문서 설정 — 로컬 개발 시 브라우저에서 API를 눌러보기 위한 화면.
 * 접속: http://localhost:8080/swagger-ui.html (경로는 application.yml의 springdoc 설정)
 *
 * <p>이 설정이 필요한 이유: springdoc은 컨트롤러를 스캔해 문서를 자동 생성하지만,
 * <b>JWT 같은 인증 방식은 스스로 알 수 없다.</b> 아래처럼 bearer 보안 스킴을 등록해야
 * Swagger UI 우측 상단에 <b>Authorize 버튼</b>이 생기고, 로그인 API로 받은 accessToken을
 * 붙여넣으면 이후 모든 요청에 {@code Authorization: Bearer <token>} 헤더가 자동으로 실린다.
 * (없으면 보호 API(제출/오답노트)를 Swagger에서 테스트할 방법이 없다)
 */
@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME_NAME = "bearerAuth";

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("csquiz API")
                        .description("CS 지식베이스 & 문제풀이 플랫폼 — 명세는 docs/03-api-spec.md")
                        .version("v1 (MVP)"))
                // "bearerAuth"라는 이름의 보안 스킴 정의: HTTP bearer + JWT 형식
                .components(new Components().addSecuritySchemes(SECURITY_SCHEME_NAME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                // 전역 적용: 모든 API에 자물쇠 아이콘 표시.
                // 공개 API(문서/퀴즈 조회 등)는 토큰 없이도 그냥 동작하므로 실사용엔 지장 없고,
                // API별로 세분화(@SecurityRequirement)하는 것보다 설정이 단순해 MVP에선 전역을 택했다.
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME));
    }
}
