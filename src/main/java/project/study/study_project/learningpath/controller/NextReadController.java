package project.study.study_project.learningpath.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.global.response.ApiResponse;
import project.study.study_project.learningpath.dto.NextReadItem;
import project.study.study_project.learningpath.service.NextReadService;

import java.util.List;

/**
 * 문서 아래 「다음에 읽을 글」. 주소는 문서 API 밑에 두지만 코스 쪽에 둔다 —
 * 고르는 근거가 코스 순서라, 문서 패키지가 코스 패키지를 부르면 두 패키지가 서로를 부르게 된다.
 *
 * <p>문서 단건 응답에 넣지 않은 이유: 단건은 Redis에 10분 캐시된다. 코스 순서를 바꿔도
 * 캐시가 옛 추천을 들고 있게 되고, 추천이 실패해도 본문은 떠야 한다. GET /api/documents/** 라 인증 없이 열린다.
 */
@RestController
@RequiredArgsConstructor
public class NextReadController {

    private final NextReadService nextReadService;

    /** 예: {@code GET /api/documents/packet-switching-and-protocol-layers/next-reads} */
    @GetMapping("/api/documents/{slug}/next-reads")
    public ApiResponse<List<NextReadItem>> nextReads(@PathVariable String slug) {
        return ApiResponse.ok(nextReadService.nextReads(slug));
    }
}
