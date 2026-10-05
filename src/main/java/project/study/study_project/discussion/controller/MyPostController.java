package project.study.study_project.discussion.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.discussion.dto.PostDetail;
import project.study.study_project.discussion.dto.PostEditRequest;
import project.study.study_project.discussion.dto.PostFilter;
import project.study.study_project.discussion.dto.RecentPostResponse;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.discussion.dto.PostWriteRequest;
import project.study.study_project.discussion.service.PostService;
import project.study.study_project.global.response.ApiResponse;

/**
 * 내 글 쓰기·수정·삭제와 내 활동 목록 — 로그인 필수({@code /api/me/**}).
 *
 * <p>글쓴이 id는 토큰에서만 꺼낸다. 본문으로 받으면 남의 이름으로 쓸 수 있다.
 */
@RestController
@RequestMapping("/api/me/posts")
@RequiredArgsConstructor
public class MyPostController {

    private final PostService postService;

    /**
     * 내 활동 — 내가 쓴 글({@code kind=written}) 또는 내가 댓글을 단 글({@code kind=commented}).
     * 커뮤니티의 "내 활동" 탭이 읽는다. 지우거나 가려진 글은 나오지 않는다.
     */
    @GetMapping
    public ApiResponse<RecentPostResponse> activity(@AuthenticationPrincipal Long userId,
                                                    @RequestParam(defaultValue = "written") String kind,
                                                    @RequestParam(defaultValue = "0") int page) {
        PostFilter filter = switch (kind) {
            case "written" -> PostFilter.writtenBy(userId);
            case "commented" -> PostFilter.commentedBy(userId);
            default -> throw new BusinessException(ErrorCode.COMMON_001,
                    "kind는 written과 commented 가운데 하나입니다.");
        };
        return ApiResponse.ok(postService.recent(filter, page));
    }

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
