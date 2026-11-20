package project.study.study_project.llm.client;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * 독자 점검이 짚은 곳 하나 — 막힘·되풀이와 고친 문장 제안. 글을 고치지 않고 짚기만 한다
 * (docs/superpowers/specs/2026-11-20-document-reader-check-design.md).
 */
public record ReaderFinding(

        @JsonPropertyDescription("BLOCK=읽기를 멈추게 한 곳(막힘), REPEAT=이미 읽은 내용을 새 정보 없이 다시 읽은 곳(되풀이)")
        Kind kind,

        @JsonPropertyDescription("UNDEFINED_TERM=풀지 않은 말, UNCLEAR_SENTENCE=안 잡히는 문장, SKIPPED_STEP=건너뛴 단계, "
                + "ANALOGY=비유, FIGURE=그림·표, NUMBER_MISMATCH=본문 안 수치·시각끼리 계산이 안 맞음, REPEAT=되풀이")
        Type type,

        @JsonPropertyDescription("STOP=읽기를 멈췄다, PAUSE=잠깐 멈칫했다")
        Weight weight,

        @JsonPropertyDescription("걸린 곳이 든 소제목")
        String section,

        @JsonPropertyDescription("걸린 문장. 본문에서 글자 그대로 복사한다. 80자 이내")
        String quote,

        @JsonPropertyDescription("되풀이면 앞에서 이미 읽은 문장을 본문 그대로. 아니면 빈 문자열")
        String earlier,

        @JsonPropertyDescription("독자 입장에서 왜 걸렸는지 한 문장")
        String reason,

        @JsonPropertyDescription("이 지적을 푸는 가장 작은 고침")
        Suggestion suggestion
) {

    public enum Kind { BLOCK, REPEAT }

    public enum Type { UNDEFINED_TERM, UNCLEAR_SENTENCE, SKIPPED_STEP, ANALOGY, FIGURE, NUMBER_MISMATCH, REPEAT }

    public enum Weight { STOP, PAUSE }

    public enum Op { REPLACE, INSERT_AFTER, DELETE, NEED_HUMAN }

    public record Suggestion(
            @JsonPropertyDescription("REPLACE=target을 text로 바꾼다, INSERT_AFTER=target 바로 뒤 같은 문단에 text를 넣는다, "
                    + "DELETE=target을 지운다, NEED_HUMAN=본문에 없는 사실이 필요해 고칠 수 없다")
            Op op,

            @JsonPropertyDescription("고칠 문장. 본문에서 글자 그대로 복사한다. NEED_HUMAN이면 빈 문자열")
            String target,

            @JsonPropertyDescription("새 문장. DELETE·NEED_HUMAN이면 빈 문자열")
            String text,

            @JsonPropertyDescription("근거를 어디서 가져왔는지, NEED_HUMAN이면 사람이 확인할 물음 한 문장")
            String note
    ) {
    }

    /** 구조화 출력의 최상위 봉투 — 최상위는 객체여야 한다. */
    public record Result(
            @JsonPropertyDescription("걸린 곳 목록. 없으면 빈 목록")
            List<ReaderFinding> findings
    ) {
    }

    ReaderFinding withSuggestion(Suggestion s) {
        return new ReaderFinding(kind, type, weight, section, quote, earlier, reason, s);
    }
}
