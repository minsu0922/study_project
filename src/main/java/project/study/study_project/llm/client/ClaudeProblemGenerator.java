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
                                               List<RejectionNote> rejectionNotes,
                                               SourceDocument sourceDocument) {
        // 구조화 출력: Batch record가 응답 스키마. create() 결과의 text()가 이미 Batch 타입으로
        // 파싱되어 있다(수동 JSON 파싱 없음 — 이 한 줄이 구조화 출력을 쓰는 이유다).
        StructuredMessageCreateParams<GeneratedProblemItem.Batch> params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .thinking(ThinkingConfigAdaptive.builder().build())
                .system(SYSTEM_PROMPT)
                .outputConfig(GeneratedProblemItem.Batch.class)
                .addUserMessage(buildPrompt(domain, difficulty, type, count, avoidQuestions,
                        rejectionNotes, sourceDocument))
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
     *
     * <h2>2026-08-15 보강 — 프롬프트를 코드·실측과 대조하고 빈 곳을 메웠다</h2>
     *
     * <p><b>[개수를 채우지 못할 때] — 8/14 사고의 원인이 여기 남아 있었다.</b> 5개를 요청했는데
     * 3개가 왔고 그중 하나가 지문이 빈 껍데기였다. 코드 쪽은 그 뒤에 고쳤지만
     * ({@code ProblemItemRule}, {@code DraftGeneratorCli.reportYield}) <b>프롬프트는 침묵했다</b>.
     * 모델 입장에서 재료가 마르면 껍데기로 개수를 맞추는 것이 지시를 지키는 길로 보인다.
     *
     * <p>그런데 우리 파이프라인에서는 정반대가 낫다. <b>적게 오는 것은 수확량 점검이 자동으로
     * 잡고, 껍데기는 사람이 읽어야 걸러진다.</b> 그래서 "모자라면 적게 내라"고 <b>탐지 가능한
     * 실패 쪽으로 유도</b>한다. 이유까지 적은 것은 이 프롬프트의 오랜 방식 그대로다 —
     * 모델은 규칙보다 규칙의 목적을 훨씬 잘 따른다.
     *
     * <p><b>[단답형 문제의 조건] — 채점기를 프롬프트가 몰랐다.</b> 실제 채점은
     * {@code QuizService.gradeShortAnswer}의 {@code trim + toLowerCase} 완전 일치다. 정답이
     * "3-way handshake"면 학습자가 "3 way handshake"라고 써도 오답이다. 프롬프트에는 형식
     * ({@code |} 구분)만 있고 <b>채점이 어떻게 되는지</b>가 없어서, 모델이 채점 불가능한 답을
     * 낼 여지가 그대로 있었다. 규약이 아니라 <b>결과</b>를 알려 줘야 그에 맞는 답이 나온다.
     *
     * <p><b>[해설] 분량 — 숫자를 박았다.</b> "핵심 가치"라고만 적혀 있었는데, 문서 프롬프트에서
     * 배운 것이 정확히 이 지점이다: 개수·분량을 숫자로 박은 지시만 실제로 지켜졌다.
     * 승인된 17문제의 해설을 재 보니 359~522자(평균 441)였다 — 지금 잘 나오는 것은 우연이고,
     * 숫자가 없으면 유지된다는 보장이 없다. 실측 폭에 약간의 여유를 얹어 400~700자로 잡고,
     * 상한을 올린 대가로 "되풀이 금지"를 함께 넣었다(문서 프롬프트의 [덜어낼 것]과 같은 짝).
     *
     * <p><b>[OX 문제의 조건]이 아예 없었다.</b> 객관식에는 오답 보기 규칙이 통째로 있는데
     * OX·단답형은 {@link #typeRule}의 <b>형식 한 줄</b>이 전부였다. OX는 "항상·절대·반드시"가
     * 든 문장이 거짓이기 쉽다는 것이 시험의 상식이라, 그 단어들이 그대로 정답 단서가 된다.
     *
     * <p><b>[금지]의 마지막 줄을 판정 가능한 규칙으로 옮겼다.</b> "문제끼리 같은 개념을 다른
     * 말로 물어보는 것"은 무엇이 '다른 개념'인지 기준이 없어 지킬 수가 없었다.
     * {@link #buildPrompt}의 중복 회피 목록은 <b>기존 문제</b>를 막을 뿐 한 배치 안은 못 막는다.
     * 근거 문서가 있으므로 "각 문제는 문서의 서로 다른 절에서 나온다"로 바꿨다 — 모델이
     * 스스로 확인할 수 있는 기준이다.
     *
     * <p><b>정답 위치 편향은 프롬프트로 고치지 않았다.</b> 승인된 17문제의 정답 위치를 세어 보니
     * 1번 6개·2번 6개·3번 5개·<b>4번 0개</b>였다(균등하다면 확률 0.8%). 보기는
     * {@code @OrderBy("seq ASC")}로 저장 순서 그대로 나가므로 학습자에게 그대로 노출된다.
     * 다만 위치 편향은 지시로 잘 고쳐지지 않는 모델의 성향이라, <b>내보낼 때 섞는 쪽</b>을 택했다
     * ({@code QuizProblemItem}). 프롬프트에 한 줄 적어 두고 지켜지길 바라는 것보다 확실하다.
     *
     * <p>{@code private}에서 package-private으로 바꾼 이유는 위 규칙들이 조용히 사라지는 것을
     * 테스트로 막기 위해서다({@code ClaudeProblemGeneratorPromptTest}) —
     * {@code ClaudeDocumentGenerator.SYSTEM_PROMPT}와 같은 판단이다.
     */
    static final String SYSTEM_PROMPT = """
            너는 백엔드 개발자 취업 준비생을 위한 CS 면접 문제 출제 위원이다.
            실제 기술 면접·실무에서 다뤄지는 주제로, 암기 확인이 아니라 원리 이해를 묻는 문제를 만든다.
            모든 문제와 해설은 한국어로 작성한다.

            [개수를 채우지 못할 때]
            요청한 개수를 채우려고 억지로 만들지 마라.
            서로 다른 것을 물을 재료가 모자라면 만들 수 있는 만큼만 내라.
            적게 오는 것은 우리가 자동으로 세어 경고한다. 반면 지문이 비었거나 보기가 모자란 문항은
            사람이 하나씩 읽어야 걸러진다. 모자란 채로 오는 쪽이 우리에게 훨씬 낫다.

            [한 번에 만드는 문제들의 관계]
            같은 요청으로 만드는 문제들은 서로 다른 것을 물어야 한다.
            근거 문서가 주어지면 각 문제는 문서의 서로 다른 절이나 항목에서 나온다.
            한 절에서 두 문제를 뽑지 마라.
            같은 개념을 표현만 바꿔 다시 묻는 것은 서로 다른 문제가 아니다.

            [오답 보기의 조건]
            객관식의 품질은 사실상 오답 보기가 결정한다. 오답이 허술하면 지문을 읽지 않고도 답이 보인다.
            - 각 오답은 "학습자가 흔히 저지르는 오해" 하나에 대응시킨다.
              예: TCP를 묻는 문제의 오답은 UDP의 특성을 TCP 것으로 착각하는 오해를 담는다.
            - 네 보기의 길이와 문체를 비슷하게 맞춘다. 정답만 유독 길거나 구체적이면 읽지 않고도 찍힌다.
            - "위 모두 옳다", "모두 틀리다" 같은 보기는 쓰지 않는다.

            [OX 문제의 조건]
            - "항상", "절대", "반드시", "모든"이 든 문장은 거짓이기 쉽다는 것이 시험의 상식이다.
              그런 말이 곧 정답 단서가 되지 않게 하라. 쓰려면 참인 명제에도 똑같이 써야 한다.
            - 예외가 있는 명제를 참으로 내지 마라. 조건을 붙여 참·거짓이 분명해지게 만든다.
            - 참과 거짓을 고르게 섞는다. 한쪽으로 몰면 지문을 안 읽고도 찍힌다.

            [단답형 문제의 조건]
            - 채점은 앞뒤 공백과 대소문자만 정규화한 뒤의 완전 일치다. 그 밖에는 전부 오답 처리된다.
            - 그래서 띄어쓰기·하이픈·약어로 갈릴 수 있는 답은 변형을 모두 | 로 적어야 한다.
              예: 3-way handshake|3way handshake|three-way handshake
            - 한국어 문장으로 답하는 문제는 내지 마라. 채점되지 않는다. 답은 용어 하나여야 한다.
            - 답이 여럿 성립하는 질문은 내지 마라. 지문에 조건을 붙여 답을 하나로 좁힌다.

            [해설]
            해설은 이 서비스의 핵심 가치다. 400~700자로 쓴다.
            - 왜 정답인지의 근거를 반드시 설명한다.
            - 객관식이면 나머지 보기가 왜 틀렸는지도 한 줄씩 짚는다.
              특히 그 오답이 어떤 오해에서 비롯되는지를 밝혀, 학습자가 같은 실수를 반복하지 않게 한다.
            - 근거 문서가 주어졌다면 마지막에 다시 읽을 절을 한 줄로 가리킨다.
              예: (문서의 '언제 깨지는가' 절을 다시 읽어 보라)
            - 분량을 채우려고 같은 말을 되풀이하지 마라. 짧고 빈 해설만큼이나 나쁘다.

            [금지]
            - 지문에 정답 용어가 그대로 등장하는 문제(답을 지문이 알려주는 꼴).
            - "다음 중 옳지 않은 것은?" 형태. 나머지 세 보기가 모두 참이어야 해서 검수 비용이 크다.
            - 특정 제품의 특정 버전에서만 참인 문제(예: "MySQL 8.0.33의 기본값은?").
              버전이 올라가면 정답이 조용히 틀린 문제가 된다.
            """;

    String buildPrompt(Domain domain, Difficulty difficulty, ProblemType type,
                       int count, List<String> avoidQuestions,
                       List<RejectionNote> rejectionNotes, SourceDocument sourceDocument) {
        StringBuilder sb = new StringBuilder();
        sb.append("다음 조건으로 문제 ").append(count).append("개를 만들어라.\n\n");
        sb.append("- 분야: ").append(domain.getDisplayName()).append(domainHint(domain)).append('\n');
        sb.append("- 난이도: ").append(difficultyRule(difficulty)).append('\n');
        sb.append("- 유형: ").append(typeRule(type)).append('\n');

        // 근거 문서(2단계) — 있으면 "아는 것을 쓰지 말고 이 문서에서 내라"로 바뀐다.
        if (sourceDocument != null) {
            appendSourceDocument(sb, sourceDocument, difficulty);
        }

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

    /**
     * 근거 문서 블록을 붙인다 — 2단계의 핵심(docs/15).
     *
     * <p><b>왜 "참고하라"가 아니라 "이 문서 안에서만 내라"인가.</b> 참고 수준으로 말하면 모델은
     * 문서를 슬쩍 보고 결국 자기 기억으로 돌아간다. 그러면 근거를 준 목적 두 가지가 다 무너진다 —
     * 환각이 줄지 않고, 학습자가 문서를 읽어도 문제를 못 푼다(문서에 없는 내용이 나오므로).
     * 사흘에 걸쳐 <b>같은 문서로</b> 계단을 오르게 하려면 범위가 닫혀 있어야 한다.
     *
     * <p><b>난이도별로 문서의 어느 부분을 쓸지 지정하는 이유.</b> 같은 문서로 세 번 문제를 만드는데
     * 지시가 같으면 사흘 내내 비슷한 문제가 나온다. 문서는 이미 층위별 재료를 나눠 담도록
     * 쓰여 있으므로(생성 프롬프트의 [난이도 재료] 절), 어느 층을 캘지 알려 주면 된다.
     *
     * <p>특히 고급에서 <b>"면접에서 이렇게 물어본다" 절을 지목</b>하는 것이 이 설계의 회수 지점이다.
     * 그 절은 질문과 요점이 짝지어 있어 <b>그대로 문항의 씨앗</b>이 된다 — 어제 문서 프롬프트에
     * 이 절을 넣은 것이 여기서 값을 한다.
     */
    private void appendSourceDocument(StringBuilder sb, SourceDocument doc, Difficulty difficulty) {
        sb.append("\n[근거 문서] 아래 문서를 읽고, 이 문서에 실제로 담긴 내용으로만 문제를 만들어라.\n");
        sb.append("문서에 없는 사실을 끌어와 문제를 내지 마라. 학습자는 이 문서를 읽고 문제를 푼다 — "
                + "문서에 없는 것을 물으면 학습이 아니라 시험이 된다.\n");
        sb.append("단, 문장을 그대로 베껴 빈칸을 뚫는 식은 금지다. 문서가 설명한 원리를 "
                + "다른 상황에 적용하게 만들어야 이해를 확인할 수 있다.\n");
        sb.append("\n제목: ").append(doc.title()).append('\n');
        sb.append("--- 문서 시작 ---\n").append(doc.contentMd()).append("\n--- 문서 끝 ---\n");
        sb.append('\n').append(sourceFocus(difficulty)).append('\n');
    }

    /**
     * 난이도별로 문서에서 캐낼 층 — 사흘 내내 비슷한 문제가 나오지 않게 하는 장치.
     *
     * <p><b>여기 적는 절 이름은 문서 생성 프롬프트의 실제 절 이름과 같아야 한다</b>
     * ({@link ClaudeDocumentGenerator#REQUIRED_SECTIONS}). 2026-08-15에 이게 어긋나 있는 것을
     * 발견했다 — 고급이 "문서의 '실무에서 터지는 지점과 한계' 절"을 지목했는데 <b>그런 이름의
     * 절은 없었다</b>. 실제 고급 재료는 {@code ## 언제 깨지는가}다.
     *
     * <p>같은 날 문서에 {@code ## 실무에서는 이렇게 쓴다}를 신설하면서 상황이 더 나빠졌다.
     * 없는 이름을 가리키던 지시가 이제는 <b>비슷한 이름의 엉뚱한 절</b>을 가리키게 됐는데,
     * 그 절은 "장면 하나를 보여 주고 원리는 다시 설명하지 마라"로 좁혀 둔 곳이라
     * 고급 재료가 거의 없다. 고급 날 재료가 마르는 8/14 사고와 같은 구조다.
     *
     * <p>중급도 함께 고쳤다. 문서 프롬프트가 {@code ### 왜 이렇게 설계됐는가} 소제목을
     * <b>문서 전체 1개</b>로 제한하면서, 소제목만 찾으면 캘 곳이 셋에서 하나로 줄었다.
     * 설계 근거는 본문 문장으로 녹여 쓰게 해 뒀으므로, 소제목만이 아니라 <b>근거를 밝힌
     * 문장들</b>까지 함께 지목해야 중급 재료가 그대로 남는다.
     *
     * <p>절 이름이 문자열로 두 파일에 흩어져 있는 것이 근본 원인이다. 지금은 테스트가
     * 세 난이도의 지시가 서로 다른지까지만 보고 <b>이름이 실재하는지</b>는 못 봤다 —
     * 그래서 {@code ClaudeProblemGeneratorPromptTest}에 실재 확인을 추가했다.
     */
    private String sourceFocus(Difficulty difficulty) {
        return switch (difficulty) {
            case BEGINNER -> "[이번 난이도에서 쓸 부분] 문서의 '## 무엇인가' 절(정의·용어 설명)과 "
                    + "본론의 기본 동작 부분을 쓴다. 문서를 읽은 사람이라면 풀 수 있어야 한다.";
            case INTERMEDIATE -> "[이번 난이도에서 쓸 부분] 문서가 <왜 그렇게 했는지>를 밝힌 곳을 쓴다 — "
                    + "'### 왜 이렇게 설계됐는가' 소제목뿐 아니라, 본문 문장 안에서 다른 선택지를 두고 "
                    + "판단한 대목과 '## 실무에서는 이렇게 쓴다' 절의 선택 이유가 모두 여기 해당한다. "
                    + "문서가 설명한 원리를 문서에 없는 새로운 상황에 적용해 판단하게 만들어라.";
            case ADVANCED -> "[이번 난이도에서 쓸 부분] 문서의 '## 언제 깨지는가' 절(깨지는 조건과 흔한 오해)과 "
                    + "'## 면접에서 이렇게 물어본다' 절을 쓴다. 그 질문들이 다루는 트레이드오프와 "
                    + "엣지 케이스를 문제로 바꿔라. 다만 질문을 그대로 옮기지 말고 객관식으로 재구성한다.";
        };
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
