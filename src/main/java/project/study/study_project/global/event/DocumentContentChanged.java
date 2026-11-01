package project.study.study_project.global.event;

/**
 * 문서 관리에서 정식 문서를 고치거나 옛 판으로 되돌린 뒤 발행된다.
 *
 * <p>문제 배치는 DB를 못 보고 원본 배치 파일({@code generated/documents/*.json})을 근거로 읽는다.
 * 이 사건을 받아 llm 쪽이 그 파일을 맞춘다. 문서 서비스가 배치 파일을 모르게 하려고 사건으로 끊었다
 * ({@link ReviewCompleted}와 같은 이유).
 *
 * @param oldSlug 고치기 전 slug. 배치 파일은 이 slug로 찾는다
 * @param slug    고친 뒤 slug
 */
public record DocumentContentChanged(String oldSlug, String slug, String title, String contentMd) {
}
