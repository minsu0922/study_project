package project.study.study_project.llm.client;

import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import lombok.extern.slf4j.Slf4j;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;

/** Claude API 기반 문서 윤문기. 배치({@code DocumentBatch})와 평가 CLI가 직접 만든다. */
@Slf4j
public class ClaudeDocumentPolisher implements DocumentPolisher {

    /** 문서 전체를 다시 쓰므로 생성과 같은 상한이다. 잘리면 뒷부분이 빠진 문서가 된다. */
    static final long MAX_TOKENS = 64_000L;

    static final String SYSTEM_PROMPT = Prompts.load("document-polish/system.txt");

    private final String model;

    public ClaudeDocumentPolisher(String model) {
        this.model = model;
    }

    @Override
    public String polish(String title, String contentMd, DocumentEdition edition) {
        // 사고는 켜지 않는다(사용자 확정 2026-11-18). 문장만 고치는 일이라 출력 요금만 는다
        StructuredMessageCreateParams<PolishedDocument> params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
                .system(SYSTEM_PROMPT)
                .outputConfig(PolishedDocument.class)
                .addUserMessage(buildPrompt(title, contentMd))
                .build();

        ClaudeCalls.Result<PolishedDocument> result;
        try {
            result = ClaudeCalls.create("문서 윤문(" + edition.getDisplayName() + ")", params);
        } catch (AnthropicServiceException e) {
            log.warn("Claude API 호출 실패(문서 윤문 {}): status={}, message={}", edition, e.statusCode(), e.getMessage());
            throw new BusinessException(ErrorCode.LLM_003, "Claude API 오류: " + e.getMessage());
        } catch (AnthropicIoException e) {
            log.warn("Claude API 네트워크 오류(문서 윤문 {}): {}", edition, e.getMessage());
            throw new BusinessException(ErrorCode.LLM_003, "네트워크 오류로 문서 윤문에 실패했습니다.");
        }
        if (result.truncated()) {
            throw new BusinessException(ErrorCode.LLM_003, "윤문 응답이 MAX_TOKENS에서 잘렸습니다.");
        }
        String body = result.value() == null ? null : result.value().contentMd();
        if (body == null || body.isBlank()) {
            throw new BusinessException(ErrorCode.LLM_003, "윤문 응답에 본문이 없습니다.");
        }
        return body;
    }

    static String buildPrompt(String title, String contentMd) {
        return "아래 문서를 윤문하라.\n\n# 제목\n" + title + "\n\n# 본문\n" + contentMd;
    }
}
