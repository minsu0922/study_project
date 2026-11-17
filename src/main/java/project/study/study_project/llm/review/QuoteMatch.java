package project.study.study_project.llm.review;

import project.study.study_project.llm.client.ClaudeDocumentFactChecker;

/**
 * AI가 "글자 그대로" 옮겼다는 인용이 실제로 그 글에 있는지 본다. 검수 지적은 인용이 원문에 없으면 버린다.
 * 정규화는 문서 사실 검수와 같은 것을 쓴다. 기준이 갈리면 같은 인용이 한쪽에서만 통과한다.
 */
public final class QuoteMatch {

    private QuoteMatch() {
    }

    public static boolean in(String quote, String... texts) {
        String q = ClaudeDocumentFactChecker.normalize(quote);
        if (q.isEmpty()) {
            return false;
        }
        for (String t : texts) {
            if (t != null && ClaudeDocumentFactChecker.normalize(t).contains(q)) {
                return true;
            }
        }
        return false;
    }
}
