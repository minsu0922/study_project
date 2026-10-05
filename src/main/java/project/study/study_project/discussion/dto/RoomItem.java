package project.study.study_project.discussion.dto;

import java.time.LocalDateTime;

/**
 * 토론방 목록의 한 줄 — 글이 있는 문제 하나.
 *
 * @param postCount  보이는 글 수. 가려지거나 지운 글은 세지 않는다
 * @param lastPostAt 가장 최근 글이 쓰인 시각. 목록이 이 순서로 온다
 */
public record RoomItem(
        Long problemId,
        String problemTitle,
        String domain,
        long postCount,
        LocalDateTime lastPostAt
) {
}
