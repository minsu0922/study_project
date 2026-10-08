package project.study.study_project.discussion.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.discussion.dto.PostLikeState;
import project.study.study_project.discussion.service.PostLikeService;
import project.study.study_project.global.response.ApiResponse;

/** 글 추천 — 누르기는 PUT, 거두기는 DELETE. 둘 다 몇 번을 보내도 결과가 같다. */
@RestController
@RequestMapping("/api/me/post-likes")
@RequiredArgsConstructor
public class MyPostLikeController {

    private final PostLikeService postLikeService;

    @PutMapping("/{postId}")
    public ApiResponse<PostLikeState> like(@AuthenticationPrincipal Long userId, @PathVariable Long postId) {
        return ApiResponse.ok(postLikeService.like(userId, postId));
    }

    @DeleteMapping("/{postId}")
    public ApiResponse<PostLikeState> unlike(@AuthenticationPrincipal Long userId, @PathVariable Long postId) {
        return ApiResponse.ok(postLikeService.unlike(userId, postId));
    }
}
