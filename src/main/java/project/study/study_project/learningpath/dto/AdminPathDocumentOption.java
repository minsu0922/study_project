package project.study.study_project.learningpath.dto;

/** 코스 단계로 고를 수 있는 문서 한 줄 — 관리 콘솔의 문서 고르기 목록. */
public record AdminPathDocumentOption(Long id, String slug, String title, String domain, String domainLabel,
                                      String edition, boolean hidden, long problemCount) {
}
