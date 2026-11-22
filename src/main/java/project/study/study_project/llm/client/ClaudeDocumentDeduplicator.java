package project.study.study_project.llm.client;

import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import lombok.extern.slf4j.Slf4j;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;

import java.util.List;

/** Claude API 기반 중복 검사. 독자 점검과 같은 호출 설정을 쓰고 웹 검색은 하지 않는다. */
@Slf4j
public class ClaudeDocumentDeduplicator implements DocumentDeduplicator {

    private static final long MAX_TOKENS = 32_000L;

    static final String SYSTEM_PROMPT = Prompts.load("document-dedup/system.txt");

    private final String model;

    public ClaudeDocumentDeduplicator(String model) {
        this.model = model;
    }

    @Override
    public List<DedupProposal> find(String title, String contentMd, DocumentEdition edition) {
        StructuredMessageCreateParams<DedupProposal.Result> params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
                // 두 문장의 정보가 정말 같은지 글 전체에서 되짚는 일이라 사고를 켠다
                .thinking(ThinkingConfigAdaptive.builder().build())
                .system(SYSTEM_PROMPT)
                .addUserMessage(buildPrompt(title, contentMd))
                .outputConfig(DedupProposal.Result.class)
                .build();

        ClaudeCalls.Result<DedupProposal.Result> result;
        try {
            result = ClaudeCalls.create("중복 검사(" + edition.getDisplayName() + ")", params);
        } catch (AnthropicServiceException e) {
            log.warn("Claude API 호출 실패(중복 검사 {}): status={}, message={}", edition, e.statusCode(), e.getMessage());
            throw new BusinessException(ErrorCode.LLM_003, "Claude API 오류: " + e.getMessage());
        } catch (AnthropicIoException e) {
            log.warn("Claude API 네트워크 오류(중복 검사 {}): {}", edition, e.getMessage());
            throw new BusinessException(ErrorCode.LLM_003, "네트워크 오류로 중복 검사에 실패했습니다.");
        }
        if (result.truncated()) {
            throw new BusinessException(ErrorCode.LLM_003, "중복 검사 응답이 MAX_TOKENS에서 잘렸습니다.");
        }
        if (result.value() == null || result.value().removals() == null) {
            throw new BusinessException(ErrorCode.LLM_003, "모델 응답에 중복 검사 결과가 없습니다.");
        }
        return result.value().removals();
    }

    static String buildPrompt(String title, String contentMd) {
        return "아래 글에서 앞 문장을 새 정보 없이 되풀이하는 문장을 찾아라.\n\n# 제목\n" + title + "\n\n# 본문\n" + contentMd;
    }
}
