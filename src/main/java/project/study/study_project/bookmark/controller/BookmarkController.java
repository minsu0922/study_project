package project.study.study_project.bookmark.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.bookmark.dto.BookmarkItem;
import project.study.study_project.bookmark.dto.BookmarkState;
import project.study.study_project.bookmark.dto.BookmarkTarget;
import project.study.study_project.bookmark.service.BookmarkService;
import project.study.study_project.global.response.ApiResponse;
import project.study.study_project.global.response.PageResponse;

import java.util.List;

/**
 * 내 북마크. 경로가 /api/me/** 라 로그인 사용자만 닿는다(SecurityConfig).
 *
 * <p>담기가 POST가 아니라 PUT인 이유: 같은 요청을 몇 번 보내도 결과가 같다("담겨 있다").
 */
@RestController
@RequestMapping("/api/me/bookmarks")
@RequiredArgsConstructor
public class BookmarkController {

    private final BookmarkService bookmarkService;

    /** 예: {@code GET /api/me/bookmarks?type=PROBLEM&page=0} */
    @GetMapping
    public ApiResponse<PageResponse<BookmarkItem>> list(@AuthenticationPrincipal Long userId,
                                                        @RequestParam BookmarkTarget type,
                                                        @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok(bookmarkService.list(userId, type, pageable));
    }

    /** 예: {@code GET /api/me/bookmarks/state?problemIds=1,2,3} */
    @GetMapping("/state")
    public ApiResponse<BookmarkState> state(@AuthenticationPrincipal Long userId,
                                            @RequestParam(defaultValue = "") List<Long> problemIds,
                                            @RequestParam(defaultValue = "") List<Long> documentIds) {
        return ApiResponse.ok(bookmarkService.state(userId, problemIds, documentIds));
    }

    @PutMapping("/{type}/{targetId}")
    public ApiResponse<Void> add(@AuthenticationPrincipal Long userId,
                                 @PathVariable BookmarkTarget type, @PathVariable Long targetId) {
        bookmarkService.add(userId, type, targetId);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/{type}/{targetId}")
    public ApiResponse<Void> remove(@AuthenticationPrincipal Long userId,
                                    @PathVariable BookmarkTarget type, @PathVariable Long targetId) {
        bookmarkService.remove(userId, type, targetId);
        return ApiResponse.ok(null);
    }
}
