package project.study.study_project.llm.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import project.study.study_project.global.common.Texts;
import project.study.study_project.llm.client.GeneratedProblemItem;
import project.study.study_project.llm.client.ProblemReview;
import project.study.study_project.llm.support.FindingList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 초안에 저장하는 AI 검수 지적 한 건 — {@code generated_problem_draft.ai_findings_json}의 원소(V29).
 *
 * <p>배치 파일의 {@link ProblemReview.Finding}에서 {@code problemIndex}만 뺀 모양이다.
 * 그 번호는 파일 안 위치라, 초안 한 건에 붙은 뒤에는 뜻이 없다.
 *
 * @param detail     근거(여러 줄). 없으면 {@code null}
 * @param suggestion 고친 문장 제안. 없거나 이미 적용했으면 {@code null}
 * @param resolution 검수자가 처리한 기록(제안 적용·직접 수정·영향 없음). 아직 안 봤으면 {@code null}
 */
public record DraftAiFinding(ProblemReview.FindingType type, String message, String detail,
                             // 제안은 드물다. 없는 지적마다 null을 적으면 저장된 JSON이 칸 이름으로 채워진다
                             @JsonInclude(JsonInclude.Include.NON_NULL) ProblemReview.Suggestion suggestion,
                             @JsonInclude(JsonInclude.Include.NON_NULL) String resolution)
        implements FindingList.Resolvable<DraftAiFinding> {

    public DraftAiFinding(ProblemReview.FindingType type, String message, String detail) {
        this(type, message, detail, null, null);
    }

    public DraftAiFinding(ProblemReview.FindingType type, String message, String detail,
                          ProblemReview.Suggestion suggestion) {
        this(type, message, detail, suggestion, null);
    }

    /**
     * 검수기의 지적을 문제 질문별로 묶는다. 초안은 저장할 때 걸러지거나 순서가 바뀔 수 있어
     * 번호가 아니라 질문 글자로 짝짓는다. 배치 들여오기와 관리 화면 생성이 같이 쓴다.
     * 짝이 없는 번호(손으로 고친 파일 등)는 버리고 돌려준 개수로 알린다.
     */
    public static Grouped groupByQuestion(List<GeneratedProblemItem> problems, List<ProblemReview.Finding> findings) {
        Map<String, List<DraftAiFinding>> byQuestion = new HashMap<>();
        List<ProblemReview.Finding> dropped = new ArrayList<>();
        for (ProblemReview.Finding finding : findings) {
            if (finding == null || finding.type() == null
                    || finding.problemIndex() < 0 || finding.problemIndex() >= problems.size()) {
                dropped.add(finding);
                continue;
            }
            String question = Texts.trimToNull(problems.get(finding.problemIndex()).question());
            byQuestion.computeIfAbsent(question, q -> new ArrayList<>())
                    .add(new DraftAiFinding(finding.type(), finding.message(), finding.detail(), finding.suggestion()));
        }
        return new Grouped(byQuestion, dropped);
    }

    /** @param dropped 짝을 못 찾아 버린 지적 */
    public record Grouped(Map<String, List<DraftAiFinding>> byQuestion, List<ProblemReview.Finding> dropped) {

        public List<DraftAiFinding> of(String question) {
            return byQuestion.getOrDefault(question, List.of());
        }
    }

    /** 처리한 지적. 제안은 뗀다 — 같은 제안을 두 번 누를 수 없게 한다. */
    public DraftAiFinding resolvedWith(String resolution) {
        return new DraftAiFinding(type, message, detail, null, resolution);
    }
}
