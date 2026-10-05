package project.study.study_project.user.dto;

/**
 * 내 정지 상태.
 *
 * @param notice 정지 중이면 풀리는 날짜와 사유가 든 안내. 아니면 {@code null}
 */
public record SuspensionResponse(boolean suspended, String notice) {
}
