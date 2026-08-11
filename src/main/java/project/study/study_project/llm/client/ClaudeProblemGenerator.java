package project.study.study_project.llm.client;

import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;

import java.util.List;

/**
 * Claude API 기반 문제 생성기 — {@link ProblemGenerator}의 실제 구현(docs/13).
 *
 * <p>핵심 설계:
 * <ul>
 *   <li><b>구조화 출력</b>: {@code outputConfig(Batch.class)}로 record에서 파생된 JSON 스키마를
 *       API에 보내면, 모델 응답이 스키마에 강제된다. 프롬프트로 "JSON으로 줘"라고 부탁하는 것과
 *       달리 형식이 <b>보장</b>되므로 파싱 실패 처리 코드가 필요 없다.
 *   <li><b>클라이언트 지연 생성(lazy)</b>: {@link AnthropicClientHolder}가 첫 호출 때 만들어 공유한다.
 *       키가 없어도 앱은 뜨고 생성 기능만 LLM_004로 안내한다 — 이유는 그 클래스 주석 참고.
 *   <li><b>어댑티브 사고(thinking)</b>: 문제 출제는 "그럴듯한 오답 만들기"가 어려운 작업이라
 *       모델이 스스로 생각 깊이를 조절하는 adaptive를 켠다. Opus 4.8은 thinking을 생략하면
 *       꺼진 채로 동작하므로 명시적으로 설정해야 한다.
 * </ul>
 */
@Slf4j
@Component
public class ClaudeProblemGenerator implements ProblemGenerator {

    private final String model;

    public ClaudeProblemGenerator(@Value("${llm.generation.model:claude-opus-4-8}") String model) {
        this.model = model;
    }

    @Override
    public List<GeneratedProblemItem> generate(Domain domain, Difficulty difficulty, ProblemType type,
                                               int count, List<String> avoidQuestions,
                                               List<RejectionNote> rejectionNotes) {
        // 구조화 출력: Batch record가 응답 스키마. create() 결과의 text()가 이미 Batch 타입으로
        // 파싱되어 있다(수동 JSON 파싱 없음 — 이 한 줄이 구조화 출력을 쓰는 이유다).
        StructuredMessageCreateParams<GeneratedProblemItem.Batch> params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .thinking(ThinkingConfigAdaptive.builder().build())
                .system(SYSTEM_PROMPT)
                .outputConfig(GeneratedProblemItem.Batch.class)
                .addUserMessage(buildPrompt(domain, difficulty, type, count, avoidQuestions, rejectionNotes))
                .build();

        try {
            return AnthropicClientHolder.get().messages().create(params).content().stream()
                    .flatMap(block -> block.text().stream())
                    .findFirst()
                    .map(typed -> typed.text().problems())
                    .orElseThrow(() -> new BusinessException(ErrorCode.LLM_003, "모델 응답에 문제 목록이 없습니다."));
        } catch (AnthropicServiceException e) {
            // API 쪽 오류(429 한도 초과, 529 과부하 등) — 우리 코드 문제가 아니므로 502로 안내
            log.warn("Claude API 호출 실패: status={}, message={}", e.statusCode(), e.getMessage());
            throw new BusinessException(ErrorCode.LLM_003, "Claude API 오류: " + e.getMessage());
        } catch (AnthropicIoException e) {
            // 네트워크 단절·타임아웃 — 재시도하면 성공할 수 있는 일시 장애
            log.warn("Claude API 네트워크 오류: {}", e.getMessage());
            throw new BusinessException(ErrorCode.LLM_003, "네트워크 오류로 생성에 실패했습니다.");
        }
    }

    /* ── 프롬프트 ─────────────────────────────────────────────── */

    /**
     * 시스템 프롬프트 — 역할과 품질 기준. 요청마다 바뀌는 값(도메인·개수 등)은 여기 넣지 않는다:
     * 안정된 앞부분(system)과 가변 부분(user)을 나누면 Anthropic 쪽 프롬프트 캐시가 앞부분을
     * 재사용할 수 있어 반복 호출 비용이 준다(지금 규모에선 미미하지만 습관으로).
     *
     * <p><b>여기를 길게 쓰는 것이 품질 레버 중 가성비가 가장 좋다.</b> 입력 토큰은 출력의 1/5 가격이고
     * (입력 $5 / 출력 $25 per 1M) 게다가 캐시 대상이라, 기준을 상세히 적어도 비용이 거의 안 오른다.
     * 반면 같은 품질을 {@code effort} 상향으로 얻으려 하면 사고 토큰이 전부 <b>출력</b> 요금으로 잡혀
     * 비용이 눈에 띄게 뛴다. 그래서 effort는 기본값(high)에 두고 기준을 글로 적는 쪽을 먼저 택했다
     * — 검수해 보고 오답 품질이 계속 아쉬우면 그때 effort를 올리고 batch-count를 줄여 예산을 맞춘다.
     */
    private static final String SYSTEM_PROMPT = """
            너는 백엔드 개발자 취업 준비생을 위한 CS 면접 문제 출제 위원이다.
            실제 기술 면접·실무에서 다뤄지는 주제로, 암기 확인이 아니라 원리 이해를 묻는 문제를 만든다.
            모든 문제와 해설은 한국어로 작성한다.

            [오답 보기의 조건]
            객관식의 품질은 사실상 오답 보기가 결정한다. 오답이 허술하면 지문을 읽지 않고도 답이 보인다.
            - 각 오답은 "학습자가 흔히 저지르는 오해" 하나에 대응시킨다.
              예: TCP를 묻는 문제의 오답은 UDP의 특성을 TCP 것으로 착각하는 오해를 담는다.
            - 네 보기의 길이와 문체를 비슷하게 맞춘다. 정답만 유독 길거나 구체적이면 읽지 않고도 찍힌다.
            - "위 모두 옳다", "모두 틀리다" 같은 보기는 쓰지 않는다.

            [해설]
            해설은 이 서비스의 핵심 가치다. 왜 정답인지의 근거를 반드시 설명하고,
            객관식이면 나머지 보기가 왜 틀렸는지도 한 줄씩 짚는다.
            특히 그 오답이 어떤 오해에서 비롯되는지를 밝혀, 학습자가 같은 실수를 반복하지 않게 한다.

            [금지]
            - 지문에 정답 용어가 그대로 등장하는 문제(답을 지문이 알려주는 꼴).
            - "다음 중 옳지 않은 것은?" 형태. 나머지 세 보기가 모두 참이어야 해서 검수 비용이 크다.
            - 특정 제품의 특정 버전에서만 참인 문제(예: "MySQL 8.0.33의 기본값은?").
              버전이 올라가면 정답이 조용히 틀린 문제가 된다.
            - 문제끼리 같은 개념을 다른 말로 물어보는 것. 한 번에 만드는 문제들은 서로 다른 주제여야 한다.
            """;

    private String buildPrompt(Domain domain, Difficulty difficulty, ProblemType type,
                               int count, List<String> avoidQuestions,
                               List<RejectionNote> rejectionNotes) {
        StringBuilder sb = new StringBuilder();
        sb.append("다음 조건으로 문제 ").append(count).append("개를 만들어라.\n\n");
        sb.append("- 분야: ").append(domain.getDisplayName()).append(domainHint(domain)).append('\n');
        sb.append("- 난이도: ").append(difficultyRule(difficulty)).append('\n');
        sb.append("- 유형: ").append(typeRule(type)).append('\n');

        // 중복 회피 — 기존 문제·대기 초안의 질문을 그대로 나열한다. "비슷한 주제 금지"보다
        // 실물 목록을 주는 쪽이 훨씬 잘 지켜진다(모델이 '비슷함'을 우리 기준으로 알 수 없으므로).
        if (!avoidQuestions.isEmpty()) {
            sb.append("\n[중복 금지] 아래 기존 문제와 같은 내용·주제의 문제는 만들지 마라:\n");
            for (String q : avoidQuestions) {
                // 지문이 길 수 있어 앞부분만 — 주제 중복 판단에는 첫 문장이면 충분하고 토큰이 절약된다
                sb.append("- ").append(truncate(q, 150)).append('\n');
            }
        }

        // 거절 사례 되먹이기(docs/14) — SYSTEM_PROMPT의 금지 규칙이 "일반론"이라면 이건 "판례"다.
        // 우리 검수자가 실제로 무엇을 탈락시켰는지는 이 서비스에만 있는 데이터라, 범용 규칙으로는
        // 얻을 수 없는 신호다. 사유만 주면 무엇을 두고 한 말인지 알 수 없으므로 지문과 짝지어 준다.
        // 이력이 없으면 블록 자체를 넣지 않는다 — 빈 제목만 남으면 모델이 "사례가 없다"를
        // "기준이 없다"로 읽을 수 있고, 토큰도 낭비다.
        if (rejectionNotes != null && !rejectionNotes.isEmpty()) {
            sb.append("\n[과거 거절 사례] 아래는 검수자가 실제로 거절한 문제와 그 사유다. 같은 실수를 반복하지 마라:\n");
            for (RejectionNote note : rejectionNotes) {
                sb.append("- \"").append(truncate(note.question(), 100)).append('"');
                if (note.reason() != null && !note.reason().isBlank()) {
                    sb.append(" → 거절 사유: ").append(truncate(note.reason(), 150));
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /** 프롬프트 토큰 절약용 앞부분 자르기. 잘렸음을 말줄임표로 알려 모델이 문장이 끊긴 것으로 오해하지 않게 한다. */
    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    /**
     * 도메인 범위 힌트. 특히 신설된 스프링·백엔드와 기존 언어·런타임은 경계가 헷갈리기 쉬워
     * (둘 다 "Java 관련") 모델에게 명시적으로 갈라 준다 — docs/02의 구분 기준 그대로.
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

    /**
     * 난이도 규칙 — 설명 한 줄에 <b>예시 문항</b>을 붙인다.
     * "초급 = 용어의 정의" 같은 서술만으로는 경계가 모호해 난이도가 뒤섞였다.
     * 모델에게는 추상적 정의보다 구체적 예시 하나가 훨씬 강한 신호라, 같은 축(TIME_WAIT)을
     * 세 난이도로 변주한 예시를 주어 "무엇이 한 단계 어려워지는 것인지"를 보여준다.
     */
    private String difficultyRule(Difficulty difficulty) {
        return switch (difficulty) {
            case BEGINNER -> """
                    초급 — 용어의 정의와 기본 동작을 안다면 풀 수 있는 수준.
                    예시: "TCP 연결 수립에 쓰이는 3-way handshake의 순서로 옳은 것은?\"""";
            case INTERMEDIATE -> """
                    중급 — 동작 원리를 이해하고 주어진 상황에 적용해야 풀 수 있는 수준.
                    예시: "서버에 TIME_WAIT 상태 소켓이 수만 개 쌓였다. 원인으로 가장 적절한 것은?\"""";
            case ADVANCED -> """
                    고급 — 내부 구현·트레이드오프·엣지 케이스까지 알아야 풀 수 있는 수준.
                    예시: "TIME_WAIT 회피를 위한 SO_REUSEADDR와 tcp_tw_reuse의 차이, 그리고 각각이 감수하는 위험은?\"""";
        };
    }

    /** 유형별 형식 규칙 — 스키마가 형태는 강제하지만 "빈 값 규약"(docs/01)은 프롬프트로 알려야 한다. */
    private String typeRule(ProblemType type) {
        return switch (type) {
            case MULTIPLE_CHOICE ->
                    "객관식 — choices에 보기 정확히 4개, 그중 correct=true 정확히 1개. answer는 빈 문자열 \"\". 오답 보기는 그럴듯해야 한다(명백히 틀린 보기 금지)";
            case OX -> "OX — choices는 빈 배열, answer는 \"O\" 또는 \"X\"";
            case SHORT_ANSWER ->
                    "단답형 — choices는 빈 배열, answer는 정답 단어(소문자, 복수 정답·영문 약어/풀네임은 |로 구분. 예: \"arp|address resolution protocol\")";
            case ESSAY -> throw new BusinessException(ErrorCode.QUIZ_002,
                    "서술형(ESSAY)은 자동채점 미지원이라 생성 대상이 아닙니다.");
        };
    }
}
