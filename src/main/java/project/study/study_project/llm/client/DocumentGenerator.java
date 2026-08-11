package project.study.study_project.llm.client;

import project.study.study_project.global.common.Domain;

import java.util.List;

/**
 * 개념 문서 생성기 추상화 — 실제 구현은 {@link ClaudeDocumentGenerator}(Claude API).
 *
 * <p>{@link ProblemGenerator}와 같은 이유로 인터페이스를 둔다: 서비스 로직(중복 회피 목록 구성,
 * 초안 저장, 승인 변환)을 검증할 때 실제 API를 부르면 돈이 들고 느리고 결과가 매번 달라
 * 단정(assert)이 불가능하다. 테스트에서는 정해진 문서를 돌려주는 가짜를 주입한다.
 */
public interface DocumentGenerator {

    /**
     * 개념 문서 한 편을 생성한다.
     *
     * @param domain        분야 (필수)
     * @param topic         주제. {@code null}이면 모델이 {@code avoidTitles}를 피해 알아서 고른다.
     *                      관리자가 직접 지정하면 그 주제로 쓴다(배치는 자동, 수동은 지정 — docs/15)
     * @param avoidTitles   이미 문서가 있는 주제 제목들. 중복 생성을 막는다
     * @param preferredTags 기존 태그 목록. 비슷한 태그가 무한히 늘어나는 것을 막으려고 재사용을 유도한다
     * @return 생성된 문서(검증 전 원본)
     */
    GeneratedDocumentItem generate(Domain domain, String topic,
                                   List<String> avoidTitles, List<String> preferredTags);
}
