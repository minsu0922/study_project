package project.study.study_project.discussion.dto;

import project.study.study_project.global.common.DomainCode;

/**
 * 커뮤니티 목록을 거르는 조건 한 벌. 탭과 검색 칸이 채우고, 비운 칸은 조건을 걸지 않는다.
 *
 * <p>묶어 둔 이유: 조건이 여섯이라 따로 넘기면 순서를 바꿔 넣어도 컴파일이 된다
 * (authorId와 commenterId는 둘 다 Long이다).
 *
 * @param q           제목·본문에서 찾을 말
 * @param domain      글이 속한 문제의 분야
 * @param unanswered  보이는 댓글이 없는 글만
 * @param authorId    이 사람이 쓴 글만("내 활동 > 내가 쓴 글")
 * @param commenterId 이 사람이 댓글을 단 글만("내 활동 > 댓글 단 글")
 */
public record PostFilter(
        String q,
        PostSort sort,
        DomainCode domain,
        boolean unanswered,
        Long authorId,
        Long commenterId
) {

    /** 커뮤니티의 전체·답변 기다리는 글 탭. */
    public static PostFilter community(String q, PostSort sort, DomainCode domain, boolean unanswered) {
        return new PostFilter(q, sort, domain, unanswered, null, null);
    }

    public static PostFilter writtenBy(Long userId) {
        return new PostFilter(null, PostSort.LATEST, null, false, userId, null);
    }

    public static PostFilter commentedBy(Long userId) {
        return new PostFilter(null, PostSort.LATEST, null, false, null, userId);
    }
}
