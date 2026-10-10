package project.study.study_project.document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.TestDomains;
import project.study.study_project.document.dto.AdminDocumentRequest;
import project.study.study_project.document.service.AdminDocumentService;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 문서 화면이 <b>JS 없이도</b> 제 제목과 설명을 달고 나가는지 — 2026-10-10 신설.
 *
 * <p>검색 엔진과 메신저가 받는 것이 바로 이 응답이다. 규칙 자체는 {@code DocumentPageMetaTest}가
 * 재고, 여기서는 그 규칙이 실제 주소에 붙어 있는지를 본다 — 정적 파일과 컨트롤러가 같은 경로를
 * 두고 다투므로, 매핑이 밀리면 화면은 멀쩡히 뜨는데 메타 태그만 조용히 사라진다.
 *
 * <p>slug를 매번 새로 짓는 이유는 {@code DocumentReadIntegrationTest}와 같다(Redis 캐시는 롤백되지 않는다).
 */
@SpringBootTest(properties = "ratelimit.enabled=false")
@AutoConfigureMockMvc
@Transactional
class DocumentPageIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AdminDocumentService adminDocumentService;

    @Test
    @DisplayName("문서 화면의 머리말에 그 문서의 제목과 설명이 들어간다")
    void fillsTitleAndDescription() throws Exception {
        String slug = newSlug();
        adminDocumentService.create(new AdminDocumentRequest(TestDomains.DATABASE, "머리말 테스트 문서", slug,
                "# 머리말 테스트 문서\n\n## 한 줄로 정리하면\n\"검색 결과에 나갈 한 문장이다.\"\n", null, List.of("meta")));

        mockMvc.perform(get("/document.html").param("slug", slug))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(allOf(
                        containsString("<title>머리말 테스트 문서 — csquiz</title>"),
                        containsString("<meta name=\"description\" content=\"검색 결과에 나갈 한 문장이다.\">"),
                        containsString("<meta property=\"og:title\" content=\"머리말 테스트 문서\">"),
                        // 화면 자체는 그대로여야 한다 — 본문은 여전히 JS가 그린다
                        containsString("id=\"body\""),
                        not(containsString("<title>문서 — csquiz</title>")))));
    }

    @Test
    @DisplayName("없는 문서는 화면을 주되 404로 답한다 — 빈 화면이 검색 결과에 남지 않는다")
    void missingDocumentIs404() throws Exception {
        mockMvc.perform(get("/document.html").param("slug", newSlug()))
                .andExpect(status().isNotFound())
                .andExpect(content().string(allOf(
                        containsString("<title>문서 — csquiz</title>"),
                        containsString("<meta name=\"robots\" content=\"noindex\">"),
                        containsString("id=\"body\""))));
    }

    @Test
    @DisplayName("slug가 없으면 원본 화면을 그대로 준다 — 안내 문구는 화면의 JS가 띄운다")
    void noSlugServesTheTemplate() throws Exception {
        mockMvc.perform(get("/document.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<title>문서 — csquiz</title>")));
    }

    private String newSlug() {
        return "page-test-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
