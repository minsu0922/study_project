package project.study.study_project.admin.revision;

import java.time.LocalDateTime;

/**
 * 수정 이력 한 줄.
 *
 * @param title          그때의 제목 — 여러 줄 가운데 어느 것인지 알아보는 단서
 * @param editorUsername 그 모습을 다른 것으로 고친 사람. 알 수 없으면 {@code null}
 * @param createdAt      그 모습이 다른 것으로 바뀐 시각
 */
public record RevisionItem(Long id, String title, String editorUsername, LocalDateTime createdAt) {
}
