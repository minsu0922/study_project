package project.study.study_project.llm.client;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/** 중복 검사 응답의 한 건. 지워도 되는지는 {@code DedupGuard}가 코드로 다시 판정한다. */
@JsonClassDescription("새 정보 없이 앞 문장을 되풀이하는 문장과 그 앞 문장의 짝")
public record DedupProposal(
        @JsonPropertyDescription("지울 문장. 문장 하나 또는 이어진 문장 전체를 본문에서 글자 그대로 옮긴다")
        String target,
        @JsonPropertyDescription("같은 내용을 target보다 앞에서 설명한 문장. 본문에서 글자 그대로 옮긴다")
        String kept,
        @JsonPropertyDescription("target에 새 정보가 없는 까닭 한 문장")
        String reason
) {

    @JsonClassDescription("중복 검사 결과")
    public record Result(
            @JsonPropertyDescription("지울 문장 목록. 중복이 없으면 빈 목록")
            List<DedupProposal> removals
    ) {
    }
}
