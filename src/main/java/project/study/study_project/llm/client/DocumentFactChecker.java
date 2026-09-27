package project.study.study_project.llm.client;

import java.util.List;

/**
 * 개념 문서 사실 검수 — 틀린 곳을 지적만 하고 본문은 고치지 않는다(docs/22 §3.1).
 */
public interface DocumentFactChecker {

    /** 원문 인용이 확인된 지적만 돌려준다. */
    List<FactCheckFinding> check(String title, String contentMd);
}
