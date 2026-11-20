package project.study.study_project.llm.client;

import java.util.List;

/** 개념 문서 독자 점검 — 독자 눈으로 읽다 걸린 곳만 짚고, 고친 문장은 제안으로만 낸다. */
public interface DocumentReaderCheck {

    /**
     * 원문 인용이 확인된 지적만 돌려준다.
     *
     * @param earlierTopics     코스에서 이 글보다 앞에 나와 독자가 이미 읽은 글 이름(없으면 빈 목록)
     * @param beginnerContentMd 심화편일 때 독자가 방금 읽은 입문편 전문. 입문편이면 {@code null}
     */
    List<ReaderFinding> check(String title, String contentMd, DocumentEdition edition,
                              List<String> earlierTopics, String beginnerContentMd);
}
