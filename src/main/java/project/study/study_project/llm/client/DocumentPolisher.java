package project.study.study_project.llm.client;

/**
 * 개념 문서 윤문기 — 내용은 그대로 두고 문장만 고친 본문을 돌려준다
 * (설계 docs/superpowers/specs/2026-11-18-document-polish-design.md).
 */
public interface DocumentPolisher {

    /**
     * @return 윤문한 본문 전체
     * @throws project.study.study_project.global.exception.BusinessException 호출 실패·잘린 응답·빈 응답
     */
    String polish(String title, String contentMd, DocumentEdition edition);
}
