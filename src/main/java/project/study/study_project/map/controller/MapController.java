package project.study.study_project.map.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.global.response.ApiResponse;
import project.study.study_project.map.dto.MapResponse;
import project.study.study_project.map.service.MapService;

/** 백엔드 기술 지도. 공개 API이고, 토큰이 있으면 내 진도가 실리고 없으면 0이다. */
@RestController
@RequiredArgsConstructor
public class MapController {

    private final MapService service;

    @GetMapping("/api/map")
    public ApiResponse<MapResponse> map(@AuthenticationPrincipal Long userId) {
        return ApiResponse.ok(service.map(userId));
    }
}
