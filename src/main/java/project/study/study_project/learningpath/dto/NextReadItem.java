package project.study.study_project.learningpath.dto;

/**
 * 문서 아래 「다음에 읽을 글」 카드 한 장.
 *
 * @param edition 입문편·심화편. 짝 문서가 공개돼 있을 때만 붙는다
 * @param reason  왜 이 글인지 — "네트워크 입문 코스 · 다음 단계"나 "#tcp 태그가 같은 글"
 * @param summary 그 글 「핵심 요약」의 첫 줄 제목. 요약 절이 없으면 null
 */
public record NextReadItem(String slug, String title, String edition, String reason, String summary) {
}
