package project.study.study_project.llm.client;

import java.util.List;

/**
 * 개념 문서 사실 검수 — 틀린 곳을 지적만 하고 본문은 고치지 않는다(docs/22 §3.1).
 */
public interface DocumentFactChecker {

    /**
     * 원문 인용이 확인된 지적만 돌려준다.
     *
     * @param edition 정의 없는 용어는 입문편에서만 본다. 심화편은 입문편에서 푼 용어를 다시 풀지 않는다
     */
    List<FactCheckFinding> check(String title, String contentMd, DocumentEdition edition);
}
