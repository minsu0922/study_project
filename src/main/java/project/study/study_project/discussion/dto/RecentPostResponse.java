package project.study.study_project.discussion.dto;

import java.util.List;

/**
 * 최근 글 한 쪽. 전체 건수는 싣지 않는다 — 화면이 쓰지 않고, 세려면 글 전체를 훑는다.
 */
public record RecentPostResponse(
        boolean hasNext,
        List<RecentPostItem> posts
) {
}
