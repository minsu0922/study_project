package project.study.study_project.llm.client;

import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;

import java.util.List;

/**
 * Claude API 기반 개념 문서 생성기 — 설계 docs/15.
 *
 * <p><b>이 문서는 "읽을거리"가 아니라 "문제의 원재료"다.</b> 한 편의 문서로 3일에 걸쳐
 * 초급·중급·고급 문제를 뽑을 계획이므로, 세 층위의 재료가 문서 안에 <b>물리적으로</b> 들어 있어야 한다.
 * 그냥 "좋은 개념 문서를 써라"라고 하면 정의와 기본 동작(초급 재료)만 잔뜩 있는 문서가 나온다.
 * 시스템 프롬프트에서 가장 공들인 부분이 이 요구사항인 이유다.
 *
 * <p><b>왜 문서 구조를 지정하는가.</b> 기존 문서(V3 시드)가 이미 좋은 형식을 갖고 있다 —
 * "왜 필요한가(비유) → 본론(ASCII 다이어그램 + 왜 이렇게 설계됐나) → 면접 한 줄 요약".
 * 새로 발명하는 대신 그 형식을 따르게 했다. AI가 쓴 문서와 손으로 쓴 문서가 한 화면에
 * 섞여 나오는데 형식이 다르면 그것만으로 티가 난다.
 *
 * <p><b>환각 억제를 "틀리지 마라"로 쓰지 않은 이유</b>: 문서는 문제보다 길고 단정적이라
 * 검수자가 틀린 곳을 놓치기 쉽다. 그래서 금지 항목에 <b>왜 위험한지</b>를 함께 적었다 —
 * 모델은 이유를 아는 규칙을 훨씬 잘 지킨다(문제 생성 프롬프트에서 확인한 효과).
 */
@Slf4j
@Component
public class ClaudeDocumentGenerator implements DocumentGenerator {

    /**
     * 문서는 문제보다 길고, 사고(thinking) 토큰도 이 상한을 함께 쓴다.
     * 문제 생성(16000)보다 넉넉히 잡은 이유: 넘치면 문서가 <b>중간에 잘려</b> 통째로 버려진다.
     * 비스트리밍 요청이라 SDK 타임아웃이 걱정될 수 있지만, Java SDK는 비스트리밍 응답 대기를
     * 최대 10분까지 늘려 잡고 실측 생성 시간은 1~2분 수준이라 여유가 있다.
     * (그래도 타임아웃이 보이면 스트리밍으로 바꿔야 한다 — 그때의 신호는 AnthropicIoException이다)
     */
    private static final long MAX_TOKENS = 24_000L;

    private final String model;

    public ClaudeDocumentGenerator(@Value("${llm.generation.model:claude-opus-5}") String model) {
        this.model = model;
    }

    @Override
    public GeneratedDocumentItem generate(Domain domain, String topic,
                                          List<String> avoidTitles, List<String> preferredTags) {
        StructuredMessageCreateParams<GeneratedDocumentItem> params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
                // 개념 문서 쓰기는 "무엇을 넣고 무엇을 뺄지"를 계속 저울질하는 작업이라 사고가 도움이 된다.
                // 문제 생성과 같은 판단(ClaudeProblemGenerator 주석)이므로 설정도 같게 둔다.
                .thinking(ThinkingConfigAdaptive.builder().build())
                .system(SYSTEM_PROMPT)
                .outputConfig(GeneratedDocumentItem.class)
                .addUserMessage(buildPrompt(domain, topic, avoidTitles, preferredTags))
                .build();

        try {
            return AnthropicClientHolder.get().messages().create(params).content().stream()
                    .flatMap(block -> block.text().stream())
                    .findFirst()
                    .map(typed -> typed.text())
                    .orElseThrow(() -> new BusinessException(ErrorCode.LLM_003, "모델 응답에 문서가 없습니다."));
        } catch (AnthropicServiceException e) {
            log.warn("Claude API 호출 실패(문서 생성): status={}, message={}", e.statusCode(), e.getMessage());
            throw new BusinessException(ErrorCode.LLM_003, "Claude API 오류: " + e.getMessage());
        } catch (AnthropicIoException e) {
            log.warn("Claude API 네트워크 오류(문서 생성): {}", e.getMessage());
            throw new BusinessException(ErrorCode.LLM_003, "네트워크 오류로 문서 생성에 실패했습니다.");
        }
    }

    /* ── 프롬프트 ─────────────────────────────────────────────── */

    /**
     * 시스템 프롬프트 — 역할·문서 구조·난이도 재료·금지 사항. 요청마다 바뀌는 값(분야·주제·회피 목록)은
     * user 메시지로 분리한다(프롬프트 캐시가 앞부분을 재사용할 수 있는 배치 — 문제 생성과 동일).
     *
     * <p>가장 중요한 절은 <b>[난이도 재료]</b>다. "셋 중 하나라도 빠지면 그 난이도의 문제를 만들 수 없다"는
     * 문장이 핵심인데, 규칙만 주는 것보다 <b>규칙이 존재하는 이유</b>를 함께 주면 훨씬 잘 지켜지기 때문이다.
     */
    private static final String SYSTEM_PROMPT = """
            너는 백엔드 개발자 취업 준비생을 위한 CS 개념 문서를 쓰는 기술 저자다.
            이 문서는 두 가지를 동시에 만족해야 한다.
            ① 학습자가 이 문서만으로 개념을 처음부터 이해할 수 있다.
            ② 이 문서만 보고 초급·중급·고급 문제를 각각 낼 수 있는 재료가 들어 있다.

            [문서 구조] — 아래 형식을 따른다
            # 제목
            ## 왜 필요한가
              이 개념이 없으면 무엇이 곤란한지부터 시작한다. 일상 비유를 하나 든다.
            ## (본론 2~4개 섹션)
              동작 원리를 순서대로 설명한다.
              흐름이나 구조가 있는 것은 ``` 코드블록 안에 ASCII 다이어그램으로 그린다.
              각 섹션 끝에 "왜 이렇게 설계됐는가"를 굵은 소제목 불릿으로 정리한다.
            ## 면접 한 줄 요약
              면접에서 실제로 말할 2~3문장 대본. 큰따옴표로 감싼다.

            [난이도 재료] — 이 문서의 존재 이유
            이 문서를 근거로 세 층위의 문제가 출제된다. 각 재료를 반드시 넣어라.
            - 초급 재료: 용어의 정의, 기본 동작 순서, 구성 요소의 이름과 역할.
            - 중급 재료: "왜 이렇게 설계했는가". 다른 선택지가 있었는데 왜 이걸 골랐는지.
              그리고 이 개념이 실무에서 문제를 일으키는 전형적 상황을 하나 이상 적는다.
            - 고급 재료: 트레이드오프, 엣지 케이스, 흔한 오해, 이 방식이 깨지는 조건.
            셋 중 하나라도 빠지면 그 난이도의 문제를 만들 수 없다.

            [문체]
            - 한국어. 평서체("~다")로 쓴다.
            - 중학생도 따라올 수 있게 쓰되 용어를 피하지는 않는다. 용어를 쓰면 곧바로 풀어 준다.
            - 결론을 먼저 말하고 근거를 뒤에 붙인다.

            [분량]
            본문 1,200~2,000자. 짧으면 재료가 부족하고, 길면 검수하는 사람이 읽기를 포기한다.

            [금지]
            - HTML 태그. 마크다운 문법만 쓴다. 본문이 그대로 화면에 렌더링되므로 보안 문제가 된다.
            - 특정 제품의 특정 버전에서만 참인 서술. 버전이 오르면 조용히 틀린 문서가 된다.
            - 확신 없는 수치·연도·표준 문서 번호·RFC 번호. 근거가 불확실하면 아예 쓰지 마라.
              사람이 검수하지만, 그럴듯한 거짓은 검수를 통과하기 쉽다.
            - 긴 코드 예제. 이것은 개념 문서지 튜토리얼이 아니다.
            """;

    /**
     * 사용자 메시지 — 분야, 주제(지정 또는 자동), 중복 회피 목록, 태그 후보.
     *
     * <p>주제를 지정하지 않으면 "기존 제목을 피해 알아서 고르라"고 시킨다. 큐레이션 목록을 손으로
     * 관리하는 대안도 검토했지만, 분야마다 수십 개를 적어 두는 일이 생기고 결국 갱신되지 않는다.
     * 기존 제목 목록은 어차피 중복 회피용으로 필요하므로, 그것을 그대로 선택의 근거로 쓴다.
     */
    String buildPrompt(Domain domain, String topic,
                       List<String> avoidTitles, List<String> preferredTags) {
        StringBuilder sb = new StringBuilder();
        sb.append("분야: ").append(domain.getDisplayName()).append(domainHint(domain)).append("\n\n");

        if (topic != null && !topic.isBlank()) {
            sb.append("주제: ").append(topic.trim()).append("\n이 주제로 문서를 써라.\n");
        } else {
            sb.append("이 분야에서 백엔드 면접에 자주 나오는 주제를 하나 골라 문서를 써라.\n");
        }

        // 중복 회피 — 제목 목록을 실물로 준다. "겹치지 마라"는 추상적 지시보다 훨씬 잘 지켜진다
        // (문제 생성의 중복 회피 목록과 같은 원리).
        if (avoidTitles != null && !avoidTitles.isEmpty()) {
            sb.append("\n[이미 문서가 있는 주제] 아래와 겹치는 주제는 고르지 마라:\n");
            avoidTitles.forEach(t -> sb.append("- ").append(t).append('\n'));
        }

        // 태그를 자유롭게 만들게 두면 tcp / TCP / tcp-handshake 처럼 비슷한 태그가 계속 늘어난다.
        if (preferredTags != null && !preferredTags.isEmpty()) {
            sb.append("\n[기존 태그] 아래에서 우선 고르고, 마땅한 게 없을 때만 새로 만들어라:\n");
            sb.append(String.join(", ", preferredTags)).append('\n');
        }
        return sb.toString();
    }

    /**
     * 분야 범위 힌트 — 문제 생성기와 같은 이유로 스프링·백엔드와 언어·런타임의 경계를 명시한다
     * (둘 다 "Java 관련"이라 모델이 헷갈린다, docs/02의 구분 기준).
     */
    private String domainHint(Domain domain) {
        return switch (domain) {
            case BACKEND_FRAMEWORK ->
                    " (Spring DI/IoC·Bean 생명주기·AOP·@Transactional 전파·MVC 흐름, JPA 영속성 컨텍스트·지연 로딩·N+1, 커넥션 풀·서블릿 컨테이너. 순수 JVM/GC 주제는 제외)";
            case LANGUAGE_RUNTIME ->
                    " (Java 언어·JVM 내부: 메모리 구조·GC·클래스로딩·동시성. Spring/JPA 등 프레임워크 주제는 제외)";
            default -> "";
        };
    }
}
