package project.study.study_project.llm.domain;

/**
 * 주제 대기열의 종류(V40).
 *
 * <p>둘은 근거가 다르다. 개념 주제는 문서를 쓰고 그 문서에서 문제를 내지만,
 * 코드 주제는 문서 없이 문제를 내고 실행 결과로 정답을 확인한다. 그래서 배치도, 내보내는 파일도 따로다.
 */
public enum TopicKind {
    /** 개념 문서 주제 — 4일 주기의 문서일에 하나씩 쓴다({@code _topics.json}). */
    CONCEPT,
    /** 자바 코드 문제 주제 — 주 1회 코드 문제 배치가 하나씩 쓴다({@code _code-topics.json}). */
    CODE
}
