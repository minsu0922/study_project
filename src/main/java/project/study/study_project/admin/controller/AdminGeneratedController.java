package project.study.study_project.admin.controller;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import project.study.study_project.llm.dto.GeneratedDiff;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.global.response.ApiResponse;
import project.study.study_project.llm.dto.GeneratedPending;
import project.study.study_project.llm.dto.GeneratedPublishResult;
import project.study.study_project.llm.service.GeneratedPublisher;

import java.util.List;

/**
 * 앱이 고친 generated/ 파일을 배치로 보낸다(docs/29 7장).
 *
 * <p>보내기가 실패해도 200으로 결과를 돌려준다. 막힘·충돌은 요청 오류가 아니라 화면이 그대로
 * 보여 줄 결과이고, 실패 응답으로 내리면 화면이 이유와 겹친 파일 목록을 받지 못한다.
 */
@RestController
@RequestMapping("/api/admin/generated")
public class AdminGeneratedController {

    static final String DISABLED = "보내기가 꺼져 있습니다(llm.publish.enabled=false).";

    /** 설정으로 끄면 빈이 없다. 그때도 화면이 "쓸 수 없음"을 받아야 해서 ObjectProvider로 받는다 */
    private final ObjectProvider<GeneratedPublisher> publisher;

    public AdminGeneratedController(ObjectProvider<GeneratedPublisher> publisher) {
        this.publisher = publisher;
    }

    @GetMapping("/pending")
    public ApiResponse<GeneratedPending> pending() {
        GeneratedPublisher p = publisher.getIfAvailable();
        if (p == null) {
            return ApiResponse.ok(GeneratedPending.unavailable(DISABLED));
        }
        try {
            return ApiResponse.ok(p.pending());
        } catch (RuntimeException e) {
            return ApiResponse.ok(GeneratedPending.unavailable("보낼 변경을 세지 못했습니다: " + e.getMessage()));
        }
    }

    /** 보낼 파일 하나의 바뀐 줄. 보낼 파일 목록에 없는 경로는 이유만 돌려준다 */
    @GetMapping("/diff")
    public ApiResponse<GeneratedDiff> diff(@RequestParam String path) {
        GeneratedPublisher p = publisher.getIfAvailable();
        if (p == null) {
            return ApiResponse.ok(GeneratedDiff.error(path, DISABLED));
        }
        try {
            return ApiResponse.ok(p.diff(path));
        } catch (RuntimeException e) {
            return ApiResponse.ok(GeneratedDiff.error(path, "바뀐 줄을 읽지 못했습니다: " + e.getMessage()));
        }
    }

    @PostMapping("/publish")
    public ApiResponse<GeneratedPublishResult> publish() {
        GeneratedPublisher p = publisher.getIfAvailable();
        if (p == null) {
            return ApiResponse.ok(GeneratedPublishResult.of(GeneratedPublishResult.Status.UNAVAILABLE, DISABLED, List.of()));
        }
        return ApiResponse.ok(p.publish());
    }
}
