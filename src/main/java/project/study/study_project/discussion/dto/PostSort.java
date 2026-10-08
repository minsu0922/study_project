package project.study.study_project.discussion.dto;

import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;

/** 커뮤니티 목록의 정렬. 주소에는 소문자로 적는다({@code ?sort=comments}). */
public enum PostSort {
    /** 새 글부터. 기본값이다. */
    LATEST,
    /** 보이는 댓글이 많은 글부터. */
    COMMENTS,
    LIKES;

    /**
     * 모르는 값은 400으로 답한다. 조용히 기본값으로 바꾸면 화면이 고른 정렬과 결과가 어긋나도
     * 아무도 모른다.
     */
    public static PostSort from(String raw) {
        if (raw == null || raw.isBlank()) {
            return LATEST;
        }
        for (PostSort sort : values()) {
            if (sort.name().equalsIgnoreCase(raw.trim())) {
                return sort;
            }
        }
        throw new BusinessException(ErrorCode.COMMON_001, "정렬은 latest, comments, likes 가운데 하나입니다.");
    }
}
