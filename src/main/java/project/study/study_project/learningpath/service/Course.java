package project.study.study_project.learningpath.service;

import project.study.study_project.global.common.Difficulty;
import project.study.study_project.learningpath.domain.PathKey;

import java.util.List;

/**
 * 코스 한 벌 — 자동 코스든 관리자 코스든 같은 모양이다. 화면에 내보내기 전 내부 표현이다.
 *
 * @param steps 비어 있지 않다. 단계가 없는 코스는 {@link CourseCatalog}가 목록에서 뺀다
 */
public record Course(PathKey key, String title, String description, List<Step> steps) {

    /**
     * 단계 하나. 문서 단계면 {@code documentSlug}, 코드 단계면 {@code difficulty}가 차 있다.
     *
     * @param edition 입문편·심화편. 짝 문서가 실제로 있을 때만 붙는다(DocumentEditions)
     */
    public record Step(int seq, String title, String edition, String documentSlug, Difficulty difficulty) {

        public static Step document(int seq, String title, String edition, String slug) {
            return new Step(seq, title, edition, slug, null);
        }

        public static Step code(int seq, Difficulty difficulty) {
            return new Step(seq, difficulty.getDisplayName() + " 코드 문제", null, null, difficulty);
        }

        public boolean isDocument() {
            return documentSlug != null;
        }
    }
}
