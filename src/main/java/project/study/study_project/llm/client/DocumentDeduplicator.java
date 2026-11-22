package project.study.study_project.llm.client;

import java.util.List;

/** 개념 문서 한 편에서 지울 중복 문장 후보를 찾는다(설계 2026-11-22-document-dedup-design.md). */
public interface DocumentDeduplicator {

    /**
     * @return 지울 문장 후보. 중복이 없으면 빈 목록
     * @throws project.study.study_project.global.exception.BusinessException 호출 실패·잘린 응답·빈 응답
     */
    List<DedupProposal> find(String title, String contentMd, DocumentEdition edition);
}
