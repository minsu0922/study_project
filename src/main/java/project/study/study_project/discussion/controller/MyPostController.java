package project.study.study_project.discussion.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.discussion.dto.PostDetail;
import project.study.study_project.discussion.dto.PostEditRequest;
import project.study.study_project.discussion.dto.PostWriteRequest;
import project.study.study_project.discussion.service.PostService;
import project.study.study_project.global.response.ApiResponse;

/**
 * 내 글 쓰기·수정·삭제 — 로그인 필수({@code /api/me/**}).
 *
 * <p>글쓴이 id는 토큰에서만 꺼낸다. 본문으로 받으면 남의 이름으로 쓸 수 있다.
 */
@RestController
@RequestMapping("/api/me/posts")
@RequiredArgsConstructor
public class MyPostController {

    private final PostService postService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PostDetail> write(@AuthenticationPrincipal Long userId,
                                         @Valid @RequestBody PostWriteRequest request) {
        return ApiResponse.ok(postService.write(userId, request));
    }

    @PutMapping("/{id}")
    public ApiResponse<PostDetail> edit(@AuthenticationPrincipal Long userId,
                                        @PathVariable Long id,
                                        @Valid @RequestBody PostEditRequest request) {
        return ApiResponse.ok(postService.edit(userId, id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        postService.delete(userId, id);
        return ApiResponse.ok(null);
    }
}
