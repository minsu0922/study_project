package project.study.study_project.llm.client;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * 문서 사실 검수가 내는 지적 한 건 — docs/22 §3.1.
 *
 * <p>quote는 원문에서 그대로 복사해야 한다. 원문에 없는 인용은 코드가 버린다
 * ({@link ClaudeDocumentFactChecker#keepQuotedInDocument}).
 */
@JsonClassDescription("개념 문서에서 찾은 오류 한 건")
public record FactCheckFinding(

        @JsonPropertyDescription("틀린 내용이 들어 있는 문장. 문서에서 글자 그대로 복사한다. 한 문장만 옮긴다")
        String quote,

        @JsonPropertyDescription("FACT_ERROR=사실과 다른 주장, "
                + "INTERNAL_MISMATCH=같은 문서의 다른 곳과 수치·설명이 어긋남")
        Kind kind,

        @JsonPropertyDescription("왜 틀렸는지. INTERNAL_MISMATCH면 어긋나는 상대 문장도 함께 적는다")
        String reason,

        @JsonPropertyDescription("올바른 내용 한두 문장")
        String correction,

        @JsonPropertyDescription("HIGH=틀렸다고 확신한다, LOW=의심되지만 확신하지 못한다")
        Confidence confidence
) {

    public enum Kind { FACT_ERROR, INTERNAL_MISMATCH }

    public enum Confidence { HIGH, LOW }

    /** 구조화 출력의 최상위 봉투 — 최상위는 객체여야 한다. */
    public record Result(
            @JsonPropertyDescription("찾은 오류 목록. 없으면 빈 목록")
            List<FactCheckFinding> findings
    ) {
    }
}
