package project.study.study_project.discussion.dto;

import java.util.List;

/**
 * 한 토론방의 글 목록 한 쪽. 방이 아직 없으면(글이 한 번도 안 쓰였으면) 빈 목록이다.
 *
 * @param solved   보는 사람이 이 문제를 풀었는지
 * @param canWrite 쓸 수 있는지 — 풀었거나 관리자다
 * @param total    보이는 글 수. 가려지거나 삭제된 글은 세지 않는다
 * @param hasNext  다음 쪽이 있는지
 * @param suspensionNotice 보는 사람이 정지 중이면 그 안내. 아니면 {@code null}. 풀었는데도 못 쓰는 이유를 화면이 말해 준다
 */
public record PostListResponse(
        boolean solved,
        boolean canWrite,
        long total,
        boolean hasNext,
        String suspensionNotice,
        List<PostSummary> posts
) {
}
