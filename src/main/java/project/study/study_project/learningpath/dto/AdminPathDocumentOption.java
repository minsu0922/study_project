package project.study.study_project.learningpath.dto;

/**
 * 코스 단계로 고를 수 있는 문서 한 줄 — 관리 콘솔의 문서 고르기 목록과 분야 코스 순서 편집이 함께 쓴다.
 *
 * @param seq 분야 코스에서 사람이 정한 자리(V44). 정하지 않았으면 null — 기본 규칙으로 뒤에 붙는다
 */
public record AdminPathDocumentOption(Long id, String slug, String title, String domain, String domainLabel,
                                      String edition, boolean hidden, long problemCount, Integer seq) {
}
