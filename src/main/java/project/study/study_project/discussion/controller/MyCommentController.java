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
import project.study.study_project.discussion.dto.CommentEditRequest;
import project.study.study_project.discussion.dto.CommentItem;
import project.study.study_project.discussion.dto.CommentWriteRequest;
import project.study.study_project.discussion.service.CommentService;
import project.study.study_project.global.response.ApiResponse;

/**
 * 내 댓글 쓰기·수정·삭제 — 로그인 필수({@code /api/me/**}).
 *
 * <p>글쓴이 id는 토큰에서만 꺼낸다. 본문으로 받으면 남의 이름으로 쓸 수 있다.
 */
@RestController
@RequestMapping("/api/me/comments")
@RequiredArgsConstructor
public class MyCommentController {

    private final CommentService commentService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CommentItem> write(@AuthenticationPrincipal Long userId,
                                          @Valid @RequestBody CommentWriteRequest request) {
        return ApiResponse.ok(commentService.write(userId, request));
    }

    @PutMapping("/{id}")
    public ApiResponse<CommentItem> edit(@AuthenticationPrincipal Long userId,
                                         @PathVariable Long id,
                                         @Valid @RequestBody CommentEditRequest request) {
        return ApiResponse.ok(commentService.edit(userId, id, request.body()));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        commentService.delete(userId, id);
        return ApiResponse.ok(null);
    }
}
