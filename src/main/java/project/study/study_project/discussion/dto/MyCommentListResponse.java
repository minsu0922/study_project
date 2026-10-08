package project.study.study_project.discussion.dto;

import java.util.List;

/** 내가 쓴 댓글 목록 — 커뮤니티의 다른 목록과 같이 "다음 쪽이 있는가"만 준다. */
public record MyCommentListResponse(boolean hasNext, List<MyCommentItem> comments) {
}
