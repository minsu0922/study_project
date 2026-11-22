package project.study.study_project.llm.dto;

/**
 * 중복 검사가 지운 문장 한 건.
 *
 * @param removed 지운 문장
 * @param kept    같은 내용으로 남긴 앞 문장
 * @param section 지운 문장이 있던 {@code ##} 절 이름
 * @param reason  새 정보가 없다고 본 까닭
 */
public record DedupRemoval(String removed, String kept, String section, String reason) {
}
