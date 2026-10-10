package project.study.study_project.document.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import project.study.study_project.document.service.DocumentService;
import project.study.study_project.document.support.DocumentPageMeta;
import project.study.study_project.global.exception.BusinessException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 문서 화면 — 정적 {@code document.html}에 그 문서의 제목과 설명을 채워 내려준다.
 *
 * <p>다른 화면은 전부 정적 파일 그대로 나간다. 이 화면만 컨트롤러를 거치는 이유는
 * {@link DocumentPageMeta} 주석에 있다 — 주소 하나가 문서 수십 편을 가리키는 유일한 화면이라,
 * 파일 그대로 내보내면 JS를 안 돌리는 쪽에는 전부 같은 제목으로 보인다.
 *
 * <p>같은 경로의 정적 파일보다 컨트롤러 매핑이 먼저 잡힌다(스프링 MVC의 기본 순서).
 * 본문과 나머지 화면 동작은 지금까지처럼 JS가 맡는다.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class DocumentPageController {

    private static final Resource TEMPLATE = new ClassPathResource("static/document.html");
    private static final MediaType HTML_UTF8 = new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8);

    private final DocumentService documentService;

    /**
     * @param slug 없으면 원본을 그대로 준다 — 화면의 JS가 "주소가 올바르지 않습니다"를 띄운다
     */
    @GetMapping("/document.html")
    public ResponseEntity<String> page(@RequestParam(required = false) String slug) throws IOException {
        // 요청마다 읽는다. 25KB라 부담이 없고, 들고 있으면 개발 중에 고친 화면이 재시작 전까지 안 보인다.
        String template = TEMPLATE.getContentAsString(StandardCharsets.UTF_8);
        if (slug == null || slug.isBlank()) {
            return html(HttpStatus.OK, template);
        }
        try {
            return html(HttpStatus.OK, DocumentPageMeta.apply(template, documentService.getDocument(slug)));
        } catch (BusinessException e) {
            // 없는 문서도 화면은 준다(JS가 안내 문구를 띄운다). 상태만 404로 바꿔 검색에 남지 않게 한다.
            return html(HttpStatus.NOT_FOUND, DocumentPageMeta.applyNotFound(template));
        } catch (RuntimeException e) {
            // 머리말을 못 채웠다고 화면까지 막지 않는다. 원본을 주면 JS가 같은 조회를 다시 해 보고
            // 진짜 오류를 화면에 띄운다.
            log.warn("문서 화면의 메타 태그를 채우지 못해 원본을 내보냅니다: slug={}", slug, e);
            return html(HttpStatus.OK, template);
        }
    }

    private static ResponseEntity<String> html(HttpStatus status, String body) {
        return ResponseEntity.status(status).contentType(HTML_UTF8).body(body);
    }
}
