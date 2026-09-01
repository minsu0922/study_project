package project.study.study_project.admin.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.admin.dto.AdminBatchStatus;
import project.study.study_project.admin.service.AdminBatchService;
import project.study.study_project.global.response.ApiResponse;

/**
 * 배치 현황 — 읽기 전용(2026-09-01 신설, docs/14).
 *
 * <p><b>왜 GET 하나뿐인가.</b> 이 화면에서 할 수 있는 일이 그것뿐이기 때문이다. 배치는 GitHub
 * Actions에서 돌고 스위치는 {@code application.yml}에 있어서, 앱에는 켜거나 즉시 실행할 수단이
 * 아예 없다. POST를 하나라도 두면 "여기서 조작할 수 있다"는 인상을 주고, 그러면 눌러 놓고
 * 안 돌아간 이유를 다시 찾게 된다.
 *
 * <p>경로가 {@code /batch-status}인 이유도 같다. {@code /batch}로 두면 나중에
 * {@code POST /batch/run} 같은 것이 자연스러워 보이는데, 이 앱에서는 만들 수 없는 것이라
 * 이름부터 <b>"상태"</b>로 못 박는다.
 */
@RestController
@RequestMapping("/api/admin/batch-status")
@RequiredArgsConstructor
public class AdminBatchController {

    private final AdminBatchService adminBatchService;

    @GetMapping
    public ApiResponse<AdminBatchStatus> status() {
        return ApiResponse.ok(adminBatchService.getStatus());
    }
}
