package project.study.study_project.llm.client;

import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import lombok.extern.slf4j.Slf4j;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.llm.client.ReaderFinding.Op;
import project.study.study_project.llm.client.ReaderFinding.Suggestion;
import project.study.study_project.llm.support.TextSpans;

import java.util.ArrayList;
import java.util.List;

/**
 * Claude API 기반 독자 점검 — 사실 검수와 같은 모델·같은 호출 설정을 쓰고, 웹 검색은 하지 않는다.
 * 사실이 맞는지는 사실 검수의 몫이고, 여기서는 본문 안에서 독자가 따라오는지만 본다.
 */
@Slf4j
public class ClaudeDocumentReaderCheck implements DocumentReaderCheck {

    private static final long MAX_TOKENS = 32_000L;

    static final String BEGINNER_PROMPT = Prompts.load("document-reader/beginner.txt");
    static final String ADVANCED_PROMPT = Prompts.load("document-reader/advanced.txt");

    /** target을 본문에서 못 찾아 제안을 접을 때 남기는 말. 검수 화면이 이 말로 까닭을 보인다. */
    static final String TARGET_NOT_FOUND = "제안한 문장을 본문에서 찾지 못했다";

    private final String model;

    public ClaudeDocumentReaderCheck(String model) {
        this.model = model;
    }

    static String systemPromptFor(DocumentEdition edition) {
        return edition == DocumentEdition.ADVANCED ? ADVANCED_PROMPT : BEGINNER_PROMPT;
    }

    @Override
    public List<ReaderFinding> check(String title, String contentMd, DocumentEdition edition,
                                     List<String> earlierTopics, String beginnerContentMd) {
        StructuredMessageCreateParams<ReaderFinding.Result> params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
                // 계산이 맞는지, 앞에서 풀었는지를 되짚어야 하는 일이라 사고를 켠다
                .thinking(ThinkingConfigAdaptive.builder().build())
                .system(systemPromptFor(edition))
                .addUserMessage(buildPrompt(title, contentMd, edition, earlierTopics, beginnerContentMd))
                .outputConfig(ReaderFinding.Result.class)
                .build();
        List<ReaderFinding> raw;
        try {
            var response = ClaudeCalls.create("독자 점검(" + edition.getDisplayName() + ")", params);
            raw = java.util.Optional.ofNullable(response.value())
                    .map(ReaderFinding.Result::findings)
                    .orElseThrow(() -> new BusinessException(ErrorCode.LLM_003, "모델 응답에 독자 점검 결과가 없습니다."));
        } catch (AnthropicServiceException e) {
            log.warn("Claude API 호출 실패(독자 점검): status={}, message={}", e.statusCode(), e.getMessage());
            throw new BusinessException(ErrorCode.LLM_003, "Claude API 오류: " + e.getMessage());
        } catch (AnthropicIoException e) {
            log.warn("Claude API 네트워크 오류(독자 점검): {}", e.getMessage());
            throw new BusinessException(ErrorCode.LLM_003, "네트워크 오류로 독자 점검에 실패했습니다.");
        }
        return sanitize(raw == null ? List.of() : raw, contentMd);
    }

    static String buildPrompt(String title, String contentMd, DocumentEdition edition,
                              List<String> earlierTopics, String beginnerContentMd) {
        StringBuilder sb = new StringBuilder("아래 글을 독자로서 읽고 걸린 곳을 짚어라.\n\n# 제목\n").append(title).append("\n\n");
        if (earlierTopics != null && !earlierTopics.isEmpty()) {
            sb.append("# 코스 앞 글(독자가 이미 읽었다)\n");
            earlierTopics.forEach(t -> sb.append("- ").append(t).append('\n'));
            sb.append('\n');
        }
        if (edition == DocumentEdition.ADVANCED && beginnerContentMd != null) {
            sb.append("# 입문편 전문(독자가 방금 읽었다. 짚는 대상이 아니다)\n").append(beginnerContentMd).append("\n\n");
        }
        return sb.append("# 점검할 본문\n").append(contentMd).toString();
    }

    /**
     * 인용이 본문에 없는 지적은 버리고, 고칠 자리를 정확히 한 곳으로 못 찾는 제안은 사람 확인으로 접는다.
     * 지어낸 인용은 검수자가 찾아갈 곳이 없고, 두 곳에 걸린 제안은 수락하면 엉뚱한 곳을 고친다.
     */
    static List<ReaderFinding> sanitize(List<ReaderFinding> raw, String contentMd) {
        String doc = ClaudeDocumentFactChecker.normalize(contentMd);
        List<ReaderFinding> kept = new ArrayList<>();
        for (ReaderFinding f : raw) {
            if (f == null || f.quote() == null) {
                continue;
            }
            String q = ClaudeDocumentFactChecker.normalize(f.quote());
            if (q.isEmpty() || !doc.contains(q)) {
                continue;
            }
            kept.add(f.withSuggestion(checkedSuggestion(f.suggestion(), contentMd)));
        }
        if (kept.size() < raw.size()) {
            log.info("독자 점검: 원문에 없는 인용 {}건을 버렸습니다.", raw.size() - kept.size());
        }
        return kept;
    }

    private static Suggestion checkedSuggestion(Suggestion s, String contentMd) {
        if (s == null) {
            return new Suggestion(Op.NEED_HUMAN, "", "", "");
        }
        if (s.op() == null || s.op() == Op.NEED_HUMAN) {
            return new Suggestion(Op.NEED_HUMAN, "", "", s.note() == null ? "" : s.note());
        }
        if (TextSpans.locateOnce(contentMd, s.target()).isEmpty()) {
            return new Suggestion(Op.NEED_HUMAN, "", "", TARGET_NOT_FOUND);
        }
        return s;
    }
}
