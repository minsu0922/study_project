package project.study.study_project.llm.review;

import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import lombok.extern.slf4j.Slf4j;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.llm.client.ClaudeCalls;
import project.study.study_project.llm.client.FactCheckFinding;
import project.study.study_project.llm.client.GeneratedProblemItem;
import project.study.study_project.llm.client.GeneratedProblemItem.GeneratedChoice;
import project.study.study_project.llm.client.ProblemReview.Finding;
import project.study.study_project.llm.client.ProblemReview.FindingType;
import project.study.study_project.llm.client.ProblemReview.Suggestion;
import project.study.study_project.llm.client.ProblemReviewer;
import project.study.study_project.llm.client.SourceDocument;
import project.study.study_project.llm.review.ProblemEditor.EditBatch;
import project.study.study_project.llm.review.ProblemSolver.Solve;
import project.study.study_project.llm.review.ProblemSolver.SolveBatch;
import project.study.study_project.llm.support.ExplanationFormRule;
import project.study.study_project.llm.support.ExplanationFormRule.FormIssue;
import project.study.study_project.llm.support.FlaggedSourceRule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * 객관식 문제 검수 — 설계는 docs/superpowers/specs/2026-11-17-ai-review-rebuild-design.md.
 * 풀이(정답을 가리고 풂) → 편집(해설·오답 설명 고침) → 고친 글 넣기 → 문서 사실 지적 연결 순서다.
 */
@Slf4j
public class ProblemReviewPipeline implements ProblemReviewer {

    private static final long MAX_TOKENS = 32_000L;

    private final Function<String, SolveBatch> solver;
    private final Function<String, EditBatch> editor;
    private long inputTokens;
    private long outputTokens;

    public ProblemReviewPipeline(String model) {
        this.solver = prompt -> call(model, ProblemSolver.PROMPT, prompt, SolveBatch.class, true);
        this.editor = prompt -> call(model, ProblemEditor.PROMPT, prompt, EditBatch.class, false);
    }

    /** 테스트용. 두 호출을 바꿔 끼운다 */
    public ProblemReviewPipeline(Function<String, SolveBatch> solver, Function<String, EditBatch> editor) {
        this.solver = solver;
        this.editor = editor;
    }

    public long inputTokens() {
        return inputTokens;
    }

    public long outputTokens() {
        return outputTokens;
    }

    @Override
    public Reviewed review(List<GeneratedProblemItem> problems, SourceDocument source, List<FactCheckFinding> facts) {
        List<Shown> shown = Shown.showable(problems);
        List<Finding> findings = new ArrayList<>();
        List<GeneratedProblemItem> reviewed = problems;
        if (!shown.isEmpty()) {
            SolveBatch solved = solver.apply(ProblemSolver.buildPrompt(shown, source));
            findings.addAll(ProblemSolver.findingsOf(shown, solved, source.contentMd()));
            findings = edit(shown, solved, source, findings);
            reviewed = apply(problems, findings);
            findings = findings.stream()
                    .map(f -> isInserted(f) ? f.withoutSuggestion() : f)
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        }
        findings.addAll(FlaggedSourceRule.link(reviewed, source.contentMd(), facts));
        return new Reviewed(reviewed, findings);
    }

    /** 표시대로 푼 문제만 편집에 넘긴다. 정답부터 의심스러운 문제의 해설을 고치면 틀린 정답을 굳힌다 */
    private List<Finding> edit(List<Shown> shown, SolveBatch solved, SourceDocument source, List<Finding> findings) {
        Map<Integer, Solve> byNo = ProblemSolver.byNo(solved);
        Map<Integer, Solve> byIndex = new HashMap<>();
        List<Shown> toEdit = new ArrayList<>();
        for (int k = 0; k < shown.size(); k++) {
            Solve solve = byNo.get(k + 1);
            if (ProblemSolver.solvedAsMarked(shown.get(k), solve)) {
                byIndex.put(shown.get(k).index(), solve);
                toEdit.add(shown.get(k));
            }
        }
        if (toEdit.isEmpty()) {
            return findings;
        }
        Map<Integer, List<FormIssue>> form = new HashMap<>();
        for (Shown s : toEdit) {
            form.put(s.index(), ExplanationFormRule.issuesOf(s.item(), source.slug(), source.contentMd()));
        }
        List<Finding> edited;
        try {
            EditBatch batch = editor.apply(ProblemEditor.buildPrompt(toEdit, byIndex, source, form));
            edited = ProblemEditor.findingsOf(toEdit, batch, source, byIndex);
        } catch (RuntimeException e) {
            log.warn("편집 호출 실패 — 풀이 지적만 남기고 해설은 고치지 않습니다: {}", e.getMessage());
            return findings;
        }
        // 고친 질문이 붙은 "질문에 정답이 적힘"은 풀이가 낸 같은 지적을 대신한다. 한 결함에 지적 둘은 나쁘다
        List<Finding> merged = new ArrayList<>(findings);
        for (Finding f : edited) {
            if (f.type() == FindingType.QUESTION_REVEALS) {
                merged.removeIf(x -> x.type() == FindingType.QUESTION_REVEALS && x.problemIndex() == f.problemIndex());
            }
            merged.add(f);
        }
        return merged;
    }

    private static boolean isInserted(Finding f) {
        return (f.type() == FindingType.EXPLANATION_REWRITTEN || f.type() == FindingType.RATIONALE_REWRITTEN)
                && f.suggestion() != null;
    }

    /** 고쳐 넣음 지적의 제안을 문제에 반영한다. 질문 글은 바꾸지 않는다(들여오기가 질문 글로 짝짓는다) */
    static List<GeneratedProblemItem> apply(List<GeneratedProblemItem> problems, List<Finding> findings) {
        List<GeneratedProblemItem> out = new ArrayList<>(problems);
        for (Finding f : findings) {
            if (!isInserted(f)) {
                continue;
            }
            GeneratedProblemItem p = out.get(f.problemIndex());
            Suggestion s = f.suggestion();
            String explanation = p.explanation();
            List<GeneratedChoice> choices = new ArrayList<>(p.choices());
            if (f.type() == FindingType.EXPLANATION_REWRITTEN) {
                explanation = s.text();
            } else {
                GeneratedChoice c = choices.get(s.choiceIndex());
                choices.set(s.choiceIndex(), new GeneratedChoice(c.text(), c.correct(), s.text(), c.matchText()));
            }
            out.set(f.problemIndex(), p.withExplanationAndChoices(explanation, choices));
        }
        return out;
    }

    private <T> T call(String model, String system, String user, Class<T> schema, boolean thinking) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
                .system(system)
                .addUserMessage(user);
        if (thinking) {
            builder.thinking(ThinkingConfigAdaptive.builder().build());
        }
        StructuredMessageCreateParams<T> params = builder.outputConfig(schema).build();
        try {
            var response = ClaudeCalls.create("문제 검수", params);
            inputTokens += response.inputTokens();
            outputTokens += response.outputTokens();
            return Optional.ofNullable(response.value())
                    .orElseThrow(() -> new BusinessException(ErrorCode.LLM_003, "모델 응답에 검수 결과가 없습니다."));
        } catch (AnthropicServiceException e) {
            log.warn("Claude API 호출 실패(문제 검수): status={}, message={}", e.statusCode(), e.getMessage());
            throw new BusinessException(ErrorCode.LLM_003, "Claude API 오류: " + e.getMessage());
        } catch (AnthropicIoException e) {
            log.warn("Claude API 네트워크 오류(문제 검수): {}", e.getMessage());
            throw new BusinessException(ErrorCode.LLM_003, "네트워크 오류로 문제 검수에 실패했습니다.");
        }
    }
}
