package project.study.study_project.llm.client;

import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.DomainCode;
import project.study.study_project.domainsetting.support.DefaultDomains;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.domainsetting.support.DomainCatalog;
import project.study.study_project.llm.support.ProblemItemRule;

import java.util.List;
import java.util.Map;

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

    /**
     * 난이도별로 <b>근거 문서에서 캐낼 절</b> — 절 이름의 단일 출처.
     *
     * <p><b>왜 상수로 뺐나.</b> 같은 절 이름이 세 곳에서 필요한데, 지금까지는 {@link #sourceFocus}의
     * 문장 안에 문자열로 박혀 있어서 나머지 두 곳이 그것을 알 방법이 없었다.
     * <ul>
     *   <li>프롬프트: "이번 난이도는 이 절을 써라"라고 모델에게 지목한다
     *   <li>{@code DraftGeneratorCli.hasMaterialFor}: 오늘 쓸 문서에 <b>그 절이 실제로 있는지</b> 본다
     *   <li>{@code ClaudeProblemGeneratorPromptTest}: 지목한 이름이 실재하는 절인지 대조한다
     * </ul>
     *
     * <p><b>없으면 어떤 사고가 나는가.</b> 2026-08-15에 문서 프롬프트의 절 이름을 바꿨는데
     * (커밋 2a5538c) 하루 전 만들어 둔 문서는 옛 이름 그대로였다. 그래서 중급이 지목하는 두 절이
     * 그 문서에 <b>둘 다 없는</b> 상태가 됐다. 이건 조용하다 — 문제는 정상적으로 생성되고 스키마도
     * 통과한다. 모델이 알아서 다른 절을 캐는데, 그게 하필 {@code ## 언제 깨지는가}면
     * <b>다음 날 고급이 쓸 재료를 미리 먹어 치운다</b>(8/14에 실제로 겪은 경로).
     *
     * <p><b>여기 적는 이름은 {@link ClaudeDocumentGenerator#REQUIRED_SECTIONS}에 있는 것이어야
     * 한다.</b> 문서에 존재할 수 없는 절을 지목하면 매일 폴백으로 떨어진다. 테스트가 대조한다.
     *
     * <p><b>중급에 두 절을 적은 이유.</b> 문서 프롬프트가 {@code ### 왜 이렇게 설계됐는가}를
     * 문서 전체 1개로 제한하면서 캘 곳이 하나로 줄었다. 설계 근거는 본문 문장으로도 녹아 있으므로
     * {@code ## 실무에서는 이렇게 쓴다}의 선택 이유까지 함께 지목해야 재료가 남는다.
     *
     * <p><b>2026-08-23에 초급에 {@code ## 바탕이 되는 개념}을 더했다.</b> 그날 신설된 절인데,
     * 상위 개념의 구성 요소를 빠짐없이 표로 적는 자리라 <b>초급 재료가 가장 많이 쌓이는 곳</b>이
     * 됐다(계층·단계·상태의 이름과 역할). 여기를 지목하지 않으면 초급이 계속
     * {@code ## 무엇인가}의 용어 3~5개만 파게 되고, 사흘 치 15문제에서 초급 5개가 가장 먼저
     * 마른다 — 8/14에 고급이 겪은 것과 같은 구조다.
     *
     * <p>{@code public}인 이유는 검사하는 쪽({@code llm.cli})이 다른 패키지라서다 —
     * {@code REQUIRED_SECTIONS}를 {@code llm.support}의 검증기에 열어 둔 것과 같은 판단.
     */
    public static final Map<Difficulty, List<String>> SOURCE_SECTIONS = Map.of(
            Difficulty.BEGINNER, List.of("## 바탕이 되는 개념", "## 무엇인가"),
            // 중급은 2026-09-14부터 심화편을 근거로 삼는다(DraftGeneratorCli.editionFor).
            // 그래서 심화편 절이 맨 앞에 온다. 뒤의 두 입문편 절을 지우지 않은 이유:
            // 심화편이 없는 옛 문서(09-03 이전 단일 문서)와 심화편 생성만 실패한 날에는
            // editionFor가 입문편을 돌려주는데, 그때 캘 곳이 이 둘이다. 지우면 그런 날의
            // 중급이 통째로 폴백(근거 없는 생성)으로 떨어진다.
            // 2026-09-16: 심화편 두 절의 이름이 바뀌었다. <옛 이름을 목록 맨 뒤에> 덧붙인다 —
            // 앞자리를 그대로 둬야 sourceFocus가 꺼내 쓰는 자리(get(0)·get(1)·get(2))의 뜻이
            // 안 바뀐다. 옛 이름을 지우지 않는 이유는 ClaudeDocumentGenerator의
            // LEGACY_FAILURE_MODE_SECTION 주석에 있다(이미 나간 문서 14편).
            Difficulty.INTERMEDIATE, List.of("## 실무에서 어디에 나타나는가",
                    "### 왜 이렇게 설계됐는가", "## 실무에서는 이렇게 쓴다",
                    ClaudeDocumentGenerator.LEGACY_REAL_WORLD_SECTION),
            Difficulty.ADVANCED, List.of("## 어떤 때 통하지 않는가", "## 면접에서 이렇게 물어본다",
                    ClaudeDocumentGenerator.LEGACY_FAILURE_MODE_SECTION)
    );

    private final String model;

    /**
     * 분야 이름·경계 힌트를 읽는 창구. 예전에는 이름은 {@code DefaultDomains}에, 힌트는
     * {@code switch}로 코드에 박혀 있었는데, 둘 다 생성 품질을 직접 흔드는 값인데도 고치려면
     * 재배포가 필요했다. 이제 관리자 화면에서 고치고 {@code generated/_domain-settings.json}으로
     * 배치까지 나른다(docs/21).
     *
     * <p><b>값이 아니라 창구(카탈로그)를 든다.</b> 이름·힌트는 {@link #buildPrompt}가 프롬프트에
     * 붙이는 <b>그 순간</b> {@link DomainCatalog#displayName}·{@link DomainCatalog#hints()}로
     * 읽는다. 생성자에서 한 번 읽어 필드에 굳히면, 스프링 빈은 기동 때 한 번만 만들어지므로
     * 관리자가 화면에서 이름·힌트를 고쳐도 앱을 재시작하기 전까지 옛 값이 나간다 — Task 7이
     * 배치 후보 목록에서 없앤 것과 같은 증상이다(자세한 판단은 {@link DomainCatalog} 클래스 주석).
     *
     * <p><b>왜 이름도 여기서 읽나(Task 4).</b> 전에는 이름을 {@code DefaultDomains.displayName}로
     * 직접 읽었다. 그러면 힌트는 관리자가 고친 값이 나가는데 <b>같은 줄의 분야 이름만 옛 이름</b>인
     * 프롬프트가 나간다 — 화면에서 분야명을 바꿔도 필터 목록만 바뀌고 나머지 화면·프롬프트는
     * 그대로였던 버그(스펙 6절)가 바로 이 자리였다. {@code DomainHints}였던 필드 타입을
     * {@link DomainCatalog}로 넓혀 이름·힌트가 <b>같은 창구, 같은 순간</b>에서 나오게 한다.
     */
    private final DomainCatalog domainCatalog;

    /**
     * 설정을 못 읽는 자리(테스트·평가 CLI)에서 쓰는 생성자 — 내장 이름·힌트로 돈다.
     *
     * <p><b>스프링이 쓰는 생성자가 아니다.</b> 전에는 여기에 {@code @Autowired}·{@code @Value}가
     * 붙어 있어 스프링 빈이 이 생성자로 만들어졌고, 그래서 앱 안에서 생성하면(관리자 "생성 실행"·
     * 문서 업로드) 화면에서 고친 힌트가 무시되고 늘 내장값이 나갔다(최종 리뷰 Important 2).
     * 지금은 {@link #ClaudeProblemGenerator(String, DomainCatalog)}가 빈 생성을 맡는다.
     * 이 생성자를 지우지 않는 이유: {@code PromptEvalCli}·E2E 테스트·프롬프트 테스트가 DB 없이
     * {@code new}로 만든다.
     */
    public ClaudeProblemGenerator(String model) {
        this(model, DefaultDomains.catalog());
    }

    /**
     * <b>스프링 빈을 만드는 생성자</b> — 앱 안의 생성이 관리자 화면에서 고친 이름·힌트를 따르게 한다.
     * 앱에서는 {@code DomainSettingService}가 {@link DomainCatalog}를 구현해 DB 값을 내준다.
     * 배치 CLI({@code DraftGeneratorCli})도 <b>같은 생성자</b>를 쓴다 — {@code _domain-settings.json}을
     * 읽은 {@code DomainSettings}가 또 하나의 {@link DomainCatalog} 구현이기 때문이다. 두 실행
     * 환경이 생성자를 공유하므로 "배치 프롬프트는 옛 이름, 앱 프롬프트는 새 이름"으로 갈라질
     * 여지가 애초에 없다.
     *
     * <p><b>{@code @Autowired}는 이 생성자 하나에만 붙인다.</b> 생성자가 여럿이면 스프링은
     * 스스로 하나를 고르지 못하고(자동 선택은 생성자가 하나뿐일 때만 된다), 표시가 둘 이상이거나
     * 하나도 없으면 컨텍스트가 아예 안 뜬다 — 이 브랜치에서 두 번 겪었다(Task 2 fix round 1,
     * 커밋 9aaea73). {@code @Value}는 "이 인자에 무엇을 넣을지"만 정할 뿐 "이 생성자를 써라"는
     * 표시가 아니다. 그리고 그 사고 때 프롬프트 테스트는 전부 초록이었다 — 컨텍스트를 안 띄우고
     * {@code new}로 만들기 때문이다. 그래서 빈 경로는 {@code ClaudeProblemGeneratorBeanHintTest}가
     * 따로 지킨다.
     */
    @Autowired
    public ClaudeProblemGenerator(@Value("${llm.generation.model:claude-opus-4-8}") String model,
                                  DomainCatalog domainCatalog) {
        this.model = model;
        this.domainCatalog = domainCatalog;
    }

    @Override
    public List<GeneratedProblemItem> generate(DomainCode domain, Difficulty difficulty, ProblemType type,
                                               int count, List<String> avoidQuestions,
                                               List<RejectionNote> rejectionNotes,
                                               SourceDocument sourceDocument,
                                               QuestionKind requestedKind) {
        // 구조화 출력: Batch record가 응답 스키마. create() 결과의 text()가 이미 Batch 타입으로
        // 파싱되어 있다(수동 JSON 파싱 없음 — 이 한 줄이 구조화 출력을 쓰는 이유다).
        StructuredMessageCreateParams<GeneratedProblemItem.Batch> params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                // 사고 요약을 받는다. 요금은 같고 보이는 것만 달라진다 — 아래 logThinkingWhenShort가 쓴다.
                .thinking(ThinkingConfigAdaptive.builder()
                        .display(ThinkingConfigAdaptive.Display.SUMMARIZED).build())
                .system(SYSTEM_PROMPT)
                .outputConfig(GeneratedProblemItem.Batch.class)
                .addUserMessage(buildPrompt(domain, difficulty, type, count, avoidQuestions,
                        rejectionNotes, sourceDocument, requestedKind))
                .build();

        try {
            ClaudeCalls.Result<GeneratedProblemItem.Batch> result = ClaudeCalls.create("문제 생성", params);
            List<GeneratedProblemItem> problems = java.util.Optional.ofNullable(result.value())
                    .map(GeneratedProblemItem.Batch::problems)
                    .orElseThrow(() -> new BusinessException(ErrorCode.LLM_003, "모델 응답에 문제 목록이 없습니다."));
            logThinkingWhenShort(problems, count, result.thinking());
            return problems;
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

    /**
     * 요청한 개수를 못 채운 날, 모델이 남긴 사고 요약을 로그에 찍는다.
     *
     * <p>빈 자리의 사유는 {@code skipReason}에 적게 했지만 모델이 그 칸까지 비운다 —
     * 2026-10에 고급이 3개 중 1개만 나온 세 번 모두 사유가 없었다. 왜 멈췄는지는 사고 안에만
     * 남아 있어서, 모자란 날에만 꺼내 본다. 다 채운 날에는 찍지 않는다(로그가 매일 길어진다).
     */
    private static void logThinkingWhenShort(List<GeneratedProblemItem> problems, int requested, String thinking) {
        long made = problems.stream().filter(p -> p.question() != null && !p.question().isBlank()).count();
        if (made >= requested || thinking == null || thinking.isBlank()) {
            return;
        }
        // 배치 CLI는 표준 출력이 곧 Actions 로그다. 로거 설정에 기대지 않고 그대로 찍는다.
        System.out.printf("%n[문제 %d개 요청에 %d개 — 모델의 사고 요약]%n%s%n%n", requested, made, thinking);
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
     * <b>그 뒤 두 번 내려 지금은 150~400이다</b>(오답 설명이 보기 행으로 옮겨 가며 해설이 맡는
     * 일이 줄었고, 하한은 실측을 보고 다시 낮췄다). 숫자를 옮긴 이유는 그때마다
     * {@link project.study.study_project.llm.support.ProblemItemRule#EXPLANATION_MIN}에 적어 뒀다 —
     * 여기와 저기에 같은 이야기를 두 벌 두면 언젠가 한쪽만 고쳐진다.
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
     *
     * <h2>2026-08-18 — [용어] 절 신설</h2>
     *
     * <p><b>사용자가 8/18 고급 문제를 읽고 "모르는 용어가 설명 없이 나온다"고 지적했다.</b>
     * 세어 보니 다섯 문제 중 셋의 보기에 {@code "SELECT ... FOR UPDATE"}·낙관적 잠금·갭 락이
     * 들어 있었는데, 근거 문서 어디에도 그 뜻이 없었다. 근본 원인은 문서 쪽이고 그쪽도 고쳤지만
     * ({@code ClaudeDocumentGenerator}의 같은 날 개정) <b>여기에도 한 겹이 필요하다</b>.
     * 문서에 뜻이 있어도 문제는 그 절을 통째로 옮겨 오지 않는다 — 학습자는 보기를 먼저 읽고,
     * 문서로 돌아가는 것은 해설을 본 뒤다.
     *
     * <p><b>"쉽게 써라"로 적지 않은 이유</b>: 그러면 모델이 <b>문제를 쉽게</b> 만든다. 우리가
     * 원하는 것은 난이도를 낮추는 것이 아니라 <b>용어의 문턱만 낮추는</b> 것이다 —
     * 고급은 "여러 대응 중 무엇을 고를지"를 묻는 자리이고 그 어려움은 그대로 둬야 한다.
     * 그래서 지시를 "괄호로 한 줄 붙여라"라는 <b>형식</b>으로 적었다. 형식은 지켜졌는지
     * 눈으로 확인되고, 난이도 규칙과 부딪히지 않는다.
     *
     * <p><b>"정답이 그 용어면 붙이지 마라"가 이 절의 핵심 예외다.</b> 이 규칙을 안 달면
     * 곧바로 {@code [금지]}의 "지문이 답을 알려 주는 문제"와 정면으로 부딪힌다 —
     * 초급 문제에서 정답 용어에 뜻을 괄호로 달면 그게 곧 답이다. 규칙을 새로 넣을 때
     * <b>기존 규칙과 부딪히는 자리</b>를 같이 적어 두지 않으면, 모델은 둘 중 하나를 버린다.
     *
     * <h2>2026-08-18 — [상황 지문 쓰는 법] 신설</h2>
     *
     * <p><b>사용자가 실물 하나를 짚었다.</b> "여러 쓰레드가 같은 메서드를 동시에 실행하는 코드를
     * 점검했다. 메서드 안 카운터는 정확했지만 static 카운터는 기대보다 작게 나왔다" — 읽어도
     * 무슨 상황인지 알 수 없다는 지적이었고, 뜯어보니 결함이 셋이었다.
     * ① 누가 왜 점검하는지가 없어 <b>업무가 아니라 실험</b>이다. ② 기대값이 안 적혀 있어
     * "작게 나왔다"의 기준이 없다. ③ 무엇보다 <b>같은 일을 하는 지역 카운터와 static 카운터를
     * 둘 다 두는 코드는 현실에 없다</b> — 두 메모리 영역을 대조하려고 발명한 장치다.
     *
     * <p><b>정답에서 거꾸로 상황을 만든 것이 근본 원인이다.</b> 중급 규칙은 "원리를 처음 보는
     * 상황에 적용하게 하라"고만 말할 뿐 <b>그 상황이 있을 법해야 한다는 요구가 없었다</b>.
     * 모델 입장에서는 개념이 드러나는 최소 장치를 짜는 것이 가장 안전한 선택이다 —
     * 실무 장면을 지어내는 것보다 틀릴 위험이 적기 때문이다. 규칙이 비어 있으면 모델은 언제나
     * 안전한 쪽으로 간다({@code ## 실무에서는 이렇게 쓴다}가 본론 요약으로 흐르던 것과 같은 성질).
     *
     * <p><b>문서 프롬프트가 이미 푼 문제였다.</b> 거기에는 "구체적인 기능이나 장애 상황으로
     * 적는다"가 있는데 여기에는 없었다. 그대로 옮겨 오면서 둘을 더했다 — <b>목적과 증상을
     * 모두 요구</b>하고(기대값이 없어서 못 읽혔으므로), 실물 (X)/(O) 대비를 붙였다.
     * 추상적 금지보다 실물 예시가 훨씬 잘 지켜진다는 것은 이 저장소에서 여러 번 확인했다.
     *
     * <p><b>중급 지문에 250자 상한을 박았다.</b> 초급에는 이미 120자 상한이 있는데
     * ({@code ProblemItemRule.BEGINNER_QUESTION_MAX}) 중급에는 없어 마음껏 길어졌다.
     * 실제로 지켜진 지시는 <b>숫자가 박힌 것뿐</b>이었다(해설 분량, 문서 절 개수, 고급 재료 개수).
     * 상황을 없애는 것이 아니라 <b>짧게 만드는 것</b>이 목적이다 — 상황 자체는 중급의 정의라
     * 없앨 수 없다.
     *
     * <p><b>업로드 경로만 고치지 않은 이유</b>: 억지 상황은 올린 문서에서만 나오는 것이 아니라
     * 매일 나오는 배치 문제의 문제이기도 하다. 여기는 두 경로가 공유하는 시스템 프롬프트라
     * 고치면 양쪽에 함께 걸린다. 그것이 의도다.
     *
     * <h2>2026-08-21 — [제목] 절 신설</h2>
     *
     * <p><b>문제 목록 화면을 만들려니 보여 줄 이름이 없었다.</b> {@code problem} 테이블에는
     * 지문뿐이라 앞부분을 잘라 쓰는 수밖에 없는데, 바로 사흘 전 [상황 지문 쓰는 법]으로
     * 중급·고급 지문을 <b>실무 장면</b>으로 쓰게 바꾼 참이었다. 그래서 첫 문장이 이제
     * "주문 API에서 처리 건수를 세려고 서비스 클래스에 static 카운터를 두고…"다 —
     * 무엇을 묻는 문제인지 알 수 없다. 한 개선이 다른 곳의 전제를 무너뜨린 자리다.
     *
     * <p><b>지문을 쓴 모델이 그 자리에서 붙이게 한 이유</b>: 나중에 따로 부르면 지문만 보고
     * 이름을 지어야 하는데, 정작 주제를 가장 잘 아는 것은 <b>보기와 해설까지 아는 쪽</b>이다.
     * 그래서 {@code title}을 스키마 <b>맨 뒤</b>에 뒀다 — 순서가 곧 생성 순서라, 다 쓴 뒤에
     * 자기가 만든 것을 보고 이름을 짓는다({@link GeneratedProblemItem} 주석).
     *
     * <p><b>"정답을 제목에 쓰지 마라"가 이 절의 핵심 예외다.</b> 제목은 <b>안 푼 문제의
     * 목록에도</b> 뜬다. 이 규칙이 없으면 목록만 훑어도 답이 보이는 문제가 나오는데,
     * 그건 [금지]의 "지문이 답을 알려 주는 문제"가 화면 하나 건너에서 되살아나는 것이다.
     * 새 규칙을 넣을 때 기존 규칙과 부딪히는 자리를 함께 적는다는 원칙 그대로다.
     *
     * <h2>2026-09-17 — 문서 제목과 잣대를 맞췄다</h2>
     *
     * <p>같은 날 문서 제목 규칙을 의문문에서 명사구로 뒤집으면서, 이 절에도 빠져 있던 셋을
     * 넣었다 — <b>의문형 어미 금지</b>(여기는 물음표 예시만 있어서 "…무엇으로 아는가" 꼴이
     * 그대로 새어 나갔다), <b>줄표 부연 금지</b>, <b>제품 이름은 일반 개념 뒤 괄호에</b>.
     * 문제 목록과 문서 목록은 같은 화면 흐름에서 읽히는데 제목 형식만 다르면 두 세대처럼 보인다.
     *
     * <p><b>괄호 규칙에는 여기에만 필요한 단서를 달았다</b> — 그 이름이 정답이면 넣지 마라.
     * 문서에는 정답이라는 것이 없지만 문제 제목은 답을 가리는 것이 존재 이유의 절반이라,
     * 규칙을 그대로 옮겨 오면 바로 위 "정답을 제목에 쓰지 마라"와 부딪힌다.
     *
     * <p>검사 쪽도 함께 넓혔다({@code ProblemItemRule}은 물음표만 보고 있었다). 두 검증기가
     * 같은 판정을 각자 적지 않도록 {@code TitleStyleRule} 한 곳에서 본다.
     *
     * <h2>2026-09-05 — 말은 "최대 2개"인데 본보기는 전부 상황형이었다</h2>
     *
     * <p>2026-09-05 배치(OS·중급 5문제)가 상황형 3개로 나와 {@code SITUATION_MAX_PER_BATCH}
     * 경고에 걸렸다. 상한은 08-25에 이미 숫자로 박아 뒀는데도 넘긴 것이라, 숫자가 모자란
     * 것이 아니라 <b>숫자를 이기는 무언가가 프롬프트 안에 있다</b>고 봐야 했다. 찾아보니 둘이었다.
     *
     * <p><b>① 본보기가 전부 상황형이었다.</b> 상한은 {@code [중급이 묻는 다섯 형태]}에 한 줄로
     * 적혀 있는데, 바로 뒤 {@code [같은 문제를 다섯 번 내지 마라]}의 (X)/(O) 예시 여섯 개가
     * <b>하나도 빠짐없이 실무 장면</b>이었다. 규칙은 한 번 말하고 본보기는 여섯 번 보여 준 셈이다.
     * 이 저장소가 반복해 확인한 것이 정확히 그 반대다 — <b>추상적 금지는 무시되고 실물 예시는
     * 지켜진다</b>. 그러니 예시가 전부 상황형인 동안에는 상한이 이길 수가 없었다.
     * 그래서 (O)를 <b>다섯 줄짜리 한 배치</b>로 다시 썼다. 형태 이름을 왼쪽에 붙이고
     * 상황 둘 + 비교·인과·판정 셋으로 짰다 — "좋은 배치는 이렇게 생겼다"는 그림 자체를
     * 상한과 같은 모양으로 바꾼 것이다. 문형이 겹치면 안 된다는 원래 교훈은 그대로 남긴다.
     *
     * <p><b>② 다른 형태의 재료를 어디서 캐는지 아무 데도 없었다.</b> 문제의 배치는 근거 문서가
     * {@code what-a-new-thread-adds-in-memory} 입문편이었는데, 비교에 쓸 만한 절과 인과에 쓸 만한
     * 절이 하나씩뿐이었다. 실제로 그 둘을 각각 한 문제가 쓰고 나자 남은 세 자리를 채울 곳이
     * 상황형밖에 없었다 — 상황형은 어느 절을 읽어도 장면을 지어낼 수 있기 때문이다.
     * 모델을 탓할 일이 아니라 <b>개수는 요구하면서 광산을 안 알려 준 쪽</b>의 문제다.
     *
     * <p>그래서 형태마다 {@code 재료:} 한 줄을 붙여 캘 절을 지목했다. 지목한 절은
     * {@code ClaudeDocumentGenerator.BEGINNER_REQUIRED_SECTIONS}에 있는 것만 골랐다 —
     * 모든 입문편에 <b>반드시</b> 있는 절이라 "그 절이 없더라"가 성립하지 않는다.
     * 특히 {@code ## 자주 하는 오해}는 오해 하나가 곧 오답 하나라 JUDGMENT 재료가 늘 있고,
     * {@code ## 왜 필요한가}는 "왜"가 문장으로 적힌 유일한 절이라 CAUSE 재료가 늘 있다.
     * 그 사실을 프롬프트에 못 박아("c와 d는 언제나 낼 수 있다") 도피로를 닫았다.
     *
     * <p><b>상한을 차단으로 올리지 않은 이유.</b> 초과분을 버리는 선택지도 있었지만, 그러면
     * 요금을 낸 멀쩡한 문제를 잃는 데다 배치가 3개로 줄어 수확량 점검이 대신 운다. 이 저장소가
     * 품질 기준에 늘 쓰는 판단 그대로다 — <b>버리는 비용이 고치는 비용보다 크면 경고로 둔다</b>
     * ({@code ProblemItemRule}의 품질 경고 문단). 여기서 고칠 자리는 사후 처리가 아니라
     * 애초에 상황형으로 기울게 만든 프롬프트 쪽이었다.
     *
     * <h2>2026-09-17 — 09-05에 지목한 광산이 중급에는 없는 절이었다</h2>
     *
     * <p><b>증상</b>: 09-17 배치(DATABASE·중급)가 요청 5개 중 2개만 냈고, 그 둘은 상황형이었다.
     * 버려진 셋은 전부 <b>빈 지문</b>이다(09-13에 둘, 09-14에 하나 — 늘 뒤쪽 번호).
     * 살아남은 1번 문제의 해설은 {@code ### 애플리케이션 프로세스 밖에서만 만나는 것}을
     * 가리키는데, 그건 <b>고급 절</b> 아래 소제목이다. 고급 재료로 만든 중급 문제라 지문이
     * 오케스트레이터·테넌트 배치로 시작한다 — 사용자가 "문제가 이해하기 어렵다"고 한 자리다.
     *
     * <p><b>원인은 둘이 겹쳤다.</b> ① 중급이 캘 수 있는 분량이 실물에서 908자였다(초급 3,185자,
     * 고급 4,623자). 5문제를 요구하니 문제당 182자다. ② 바로 위 09-05 개정이 형태마다 적어 준
     * {@code 재료:} 줄이 <b>전부 입문편 절 이름</b>이었다 — {@code ## 왜 필요한가},
     * {@code ## 자주 하는 오해}. 09-14에 중급이 심화편으로 옮겨 가면서 <b>그 절들은 근거 문서에
     * 없는 이름</b>이 됐고, "c와 d는 언제나 낼 수 있다"는 보증이 거짓이 됐다. 없는 절을 지목하면
     * 모델은 오류를 내지 않고 조용히 아무 데나 캔다는 8/15 사고가 여기서 다시 일어났다.
     *
     * <p><b>고친 것 셋.</b> ① 재료가 두 자리가 됐다 — 심화편에 입문편의 중급 절 둘을 붙여
     * 908 → 2,383자로 늘렸다({@code DocumentEditionRule.bodyFor}). ② {@code 재료:} 줄을 <b>실제로
     * 있는 절</b>로 다시 썼다. ③ 형태 배분을 숫자로 못 박았다. 09-05에 배웠듯 상한 한 줄로는
     * 안 되고, 이 프롬프트에서 숫자가 박힌 지시만 실제로 지켜졌다.
     *
     * <h2>같은 날 — 중급에서 상황형을 아예 뺐다(사용자 결정)</h2>
     *
     * <p><b>중급을 "초급 바로 위 한 칸"으로 못 박았다.</b> 위 개정으로 재료는 늘었지만 상황형이
     * 남아 있는 한 중급 문제는 계속 어렵다 — 장면을 읽어 내는 것 자체가 과제이기 때문이다.
     * 중급이 묻는 것은 "원리를 쓸 줄 아는가" 하나인데, 장면이 있으면 <b>장면 파악이라는 두 번째
     * 과제</b>가 얹힌다. 그래서 {@code SITUATION}을 고급 전용으로 돌리고 중급은 네 형태로 낸다.
     *
     * <p><b>실측이 이 방향을 뒷받침한다.</b> 중급 초안 47건의 형태별 지문 길이를 재 보니
     * 상황형(24건)만 평균 218자·최장 285자였고, 나머지 넷은 평균 91~142자·최장 206자였다.
     * 길이는 난이도의 <b>증상</b>이다 — 같은 원리를 묻는데 상황형만 지문이 두 배다.
     *
     * <p><b>예시부터 갈았다.</b> 중급 예시 문항이 정확히 상황형이었고("…수만 개 쌓이고 새 연결이
     * 간헐적으로 실패한다"), {@code [같은 문제를 다섯 번 내지 마라]}의 (O) 본보기도 첫 두 줄이
     * 장면이었다. 09-05에 겪은 그대로다 — <b>규칙을 한 번 말하고 본보기를 여섯 번 보여 주면
     * 본보기가 이긴다.</b> 새 (O)는 다섯 줄 모두 장면이 없고 묻는 행위만 다르다.
     *
     * <p><b>중급 지문 상한을 350 → 220자로 내렸다</b>({@code ProblemItemRule}). 비상황형 23건의
     * 최장이 206자라 지시를 잘 따른 문제는 걸리지 않고, 장면을 늘어놓기 시작하면 걸린다.
     * 상한을 그대로 두면 이 개정은 프롬프트 문구로만 남는다.
     *
     * <h2>같은 날 — 짧아진 지문이 이번엔 <b>아는 사람만 읽히게</b> 됐다</h2>
     *
     * <p>상황형을 뺀 첫 실물에서 사용자가 한 문항을 짚었다 — "새 컬럼을 채우는 백필을
     * 마이그레이션 스크립트 안에 둘지 별도 배치로 뺄지 판단하는 기준으로 가장 적절한 것은?"
     * 62자짜리 깔끔한 비교형인데, <b>백필이 무엇인지 아는 사람만 읽을 수 있다.</b>
     *
     * <p>장면을 걷어내면서 함께 사라진 것이 있었다. 상황형 지문은 장면을 그리는 동안 용어가
     * 무엇인지도 자연스럽게 알려 준다("…카운터는 970에서 멈춘다"). 장면을 빼자 그 설명까지
     * 빠져 <b>용어를 아는 사람만 읽을 수 있는 한 줄</b>이 남았다. 중급을 초급 바로 위 한 칸으로
     * 두려던 개정이 정반대로 문턱을 올린 셈이다.
     *
     * <p>고친 방법은 <b>장면 대신 인과 한 문장</b>이다. {@code [중급 지문 쓰는 법]}을 신설해
     * "무엇 때문에 생기는 일인가 → 그것을 뭐라 부르는가 → 무엇을 묻는가" 순서를 못 박고
     * (X)/(O)를 붙였다. (O)는 장면이 하나도 없는데 처음 보는 사람도 읽힌다 — 그 둘이 양립
     * 가능하다는 것을 본보기로 보여 주는 것이 이 블록의 전부다.
     *
     * <p>{@code [용어]} 절과 부딪히는 자리를 함께 적었다: <b>그 용어가 정답이면 풀지 마라.</b>
     * 규칙 둘이 서로를 자르면 모델은 말없이 하나를 버린다 — 이 프롬프트에서 세 번 겪었다.
     */
    static final String SYSTEM_PROMPT = Prompts.load("problem/system.txt");

    String buildPrompt(DomainCode domain, Difficulty difficulty, ProblemType type,
                       int count, List<String> avoidQuestions,
                       List<RejectionNote> rejectionNotes, SourceDocument sourceDocument,
                       QuestionKind requestedKind) {
        StringBuilder sb = new StringBuilder();
        sb.append("다음 조건으로 문제 ").append(count).append("개를 만들어라.\n\n");
        // 이름·힌트는 <지금> 읽는다 — 필드에 굳혀 두면 화면에서 고친 값이 재시작 전까지 안 나간다
        // (domainCatalog 필드 주석). 이름을 DefaultDomains가 아니라 카탈로그에서 읽어야
        // 관리자가 화면에서 분야명을 바꾼 직후 이 줄도 새 이름을 쓴다(스펙 6절 버그 수정).
        sb.append("- 분야: ").append(domainCatalog.displayName(domain))
                .append(domainCatalog.hints().hintFor(domain)).append('\n');
        sb.append("- 난이도: ").append(difficultyRule(difficulty)).append('\n');
        sb.append("- 유형: ").append(typeRule(type)).append('\n');

        // 사람이 형태를 지목한 경우 — 2026-08-25.
        //
        // [왜 조건 목록의 한 줄로 넣나] 시스템 프롬프트의 [중급이 묻는 네 형태]는 "고를 수 있는
        // 것들"의 목록이고, 여기는 "이번에 무엇을 낼지"다. 시스템 프롬프트를 고쳐 넣으면 요청마다
        // 값이 달라져 프롬프트 캐시가 깨지고(시스템/유저를 나눈 이유가 그것이다), 무엇보다
        // 분야·난이도와 같은 성격의 조건이라 같은 자리에 있어야 사람도 모델도 한눈에 읽는다.
        //
        // [배분 규칙과 부딪히는 자리를 함께 적는다] 시스템 프롬프트에는 형태별 개수 배분이 있다.
        // 지목과 배분은 언제나 부딪힌다 — 특히 2026-09-17부터 중급에서 SITUATION이 빠졌으므로,
        // 사람이 중급에 SITUATION을 지목하면 두 지시가 정면으로 맞선다. 그러면 모델이 둘 중
        // 하나를 조용히 버린다([용어]·[제목] 절에서 두 번 겪은 실패라) 여기서 명시적으로 끈다.
        // 사람이 일부러 고른 것은 존중하고, 대신 검증기가 경고로 알린다(ProblemItemRule).
        //
        // 상한이지 하한이 아니므로 다른 형태를 지목할 때는 원래 부딪히지 않는다. 그래도 문구를
        // 조건부로 만들지 않은 이유는, "언제 끄고 언제 안 끄는지"가 프롬프트를 읽는 사람에게도
        // 모델에게도 한 겹 더 생각할 거리가 되기 때문이다. 사람이 골랐으면 배분은 사람 몫이다.
        if (requestedKind != null) {
            sb.append("- 묻는 형태: ").append(requestedKind.name())
                    .append('(').append(requestedKind.getLabel()).append(")로만 낸다. ")
                    .append("questionKind에 이 값을 그대로 적어라.\n")
                    .append("  형태 배분 규칙(중급 비교 2·인과 1·판정 1·순서 1, 고급 SITUATION 최대 1개)은 ")
                    .append("이번에는 적용하지 마라 — 사람이 형태를 직접 골랐다.\n")
                    .append("  [같은 문제를 다섯 번 내지 마라]는 그대로 지켜라 — 형태가 같을수록 문형이 겹치기 쉽다.\n");
        }

        // 난이도 예시는 여러 줄이라 조건 목록 안에 끼우면 "- 유형:" 줄이 예시 밑에 파묻힌다.
        // 조건은 조건대로 한눈에 보이게 두고, 예시는 제 이름표를 달아 따로 붙인다.
        sb.append('\n').append(difficultyExample(difficulty)).append('\n');

        // 근거 문서(2단계) — 있으면 "아는 것을 쓰지 말고 이 문서에서 내라"로 바뀐다.
        if (sourceDocument != null) {
            appendSourceDocument(sb, sourceDocument, difficulty);
        } else {
            // 폴백으로 도는 날. sourceQuote는 스키마상 필수 필드라 모델이 무엇이든 채워 넣는데,
            // 근거가 없는 날에 지어낸 인용이 들어오면 <검증기가 그것을 진짜로 취급한다>.
            // 빈 문자열을 명시적으로 요구해 "근거 없음"과 "근거를 지어냄"을 갈라 둔다.
            sb.append("\n[근거 인용] 이번에는 근거 문서가 없다. sourceQuote는 빈 문자열 \"\"로 두어라. "
                    + "문서가 없는데 인용을 지어내면 근거를 확인할 길이 사라진다.\n");
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
        // 여기서 "다른 상황에 적용하게 만들어라"를 <모든 난이도에> 걸어 두었던 것이
        // 초급이 어려워진 직접 원인이다(2026-08-16 실측: 초급 5문제 중 4개가 상황 서술로 시작).
        // 그 문장은 중급의 정의 그 자체라, 초급 규칙과 부딪히면 이쪽이 이겼다.
        // 적용 여부는 난이도가 정하도록 넘기고, 여기서는 베껴 쓰기 금지만 남긴다.
        sb.append("단, 문장을 그대로 베껴 빈칸을 뚫는 식은 금지다. 표현을 바꿔 묻더라도 "
                + "문서를 읽고 이해했는지가 드러나야 한다.\n");
        sb.append("새로운 상황을 지어내 적용시킬지는 난이도가 정한다 — 초급에는 상황을 붙이지 마라.\n");
        sb.append("\n제목: ").append(doc.title()).append('\n');
        // 해설이 "어느 편의 절"인지 적으려면 이 문서가 어느 편인지 알아야 한다. 알려 주지 않으면
        // "(이 글의 '…' 절을…)"로 쓴다. 올린 문서에는 편이 없어 줄을 넣지 않는다.
        if (doc.kind() == SourceDocument.Kind.GENERATED) {
            sb.append("편: ").append(ClaudeDocumentGenerator.editionOf(doc.contentMd()).getDisplayName()).append('\n');
        }
        sb.append("--- 문서 시작 ---\n").append(doc.contentMd()).append("\n--- 문서 끝 ---\n");
        sb.append('\n').append(sourceFocus(difficulty, doc.kind())).append('\n');

        // 근거 인용 — 위 지시들을 <검사 가능하게> 만드는 장치. 자세한 배경은 SourceQuoteRule.
        // "그대로 복사"를 못 박는 이유: 요약을 허용하면 문자열 대조가 성립하지 않아 검사가
        // 통째로 무력해진다. 한 줄로 제한하는 이유는 인용이 길어질수록 옮겨 적다 표현이
        // 흔들려 못 찾을 확률이 올라가기 때문이다(정규화가 공백까지만 흡수한다).
        sb.append("""

                [근거 인용] 문제마다 sourceQuote에 그 문제의 근거가 된 <문서 원문 한 줄>을 넣어라.
                - 문서에서 그대로 복사해 옮긴다. 요약하거나 표현을 바꾸면 안 된다.
                - 바로 위에서 지목한 절 안의 문장을 골라라. 다른 난이도가 쓸 절에서 캐면
                  그 난이도의 재료가 하루 먼저 사라진다.
                - 이 값은 학습자에게 보이지 않는다. 검수자가 "이 문제가 문서에서 나왔는가"를
                  대조하는 데만 쓰므로, 문제 본문에 문서를 지칭하는 말을 넣지 마라.
                """);
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
     * <p>절 이름이 문자열로 두 파일에 흩어져 있던 것이 근본 원인이었다. 이제 이름은
     * {@link #SOURCE_SECTIONS} 한 곳에만 적고 이 문장들은 <b>거기서 꺼내 끼운다</b> —
     * 문장을 고치다 이름이 갈라지는 일 자체가 없어진다.
     *
     * <h2>2026-08-18 — 업로드 문서용 분기</h2>
     *
     * <p><b>이 메서드가 이 클래스에서 유일하게 "우리 문서 양식"에 묶인 곳이다.</b> 관리자가
     * 아무 문서나 올려 문제를 뽑는 경로가 생기면서 그 결합이 문제가 됐다 — 사내 위키나 블로그
     * 글에는 {@code ## 무엇인가}가 없고, <b>없는 절을 지목하면 모델은 오류를 내지 않고 조용히
     * 아무 데나 캔다</b>. 8/15에 겪은 사고(없는 이름을 지목하던 지시)와 정확히 같은 실패다.
     *
     * <p><b>프롬프트를 통째로 복제하지 않은 이유</b>: 시스템 프롬프트의 난이도 정의·오답 조건·
     * 해설 규칙·용어 규칙은 문서 양식과 아무 상관이 없다. 복제하면 오늘 고친 것들이 두 벌이
     * 되고, 다음에 한쪽만 고쳐서 어긋난다 — 이 저장소가 절 이름으로 이미 겪은 실패다.
     * <b>양식에 묶인 곳만 갈랐다.</b>
     *
     * <p>업로드 쪽 문구의 핵심은 마지막 줄이다: <b>"그런 대목이 없으면 적게 내라."</b>
     * 임의의 문서에는 그 난이도의 재료가 아예 없을 수 있는데, 지시가 없으면 모델은 개수를
     * 맞추려고 지어낸다. 시스템 프롬프트의 {@code [개수를 채우지 못할 때]}와 같은 원리이고,
     * 여기서 한 번 더 적는 이유는 <b>재료가 마를 가능성이 배치보다 훨씬 높은 경로</b>여서다.
     */
    private String sourceFocus(Difficulty difficulty, SourceDocument.Kind kind) {
        if (kind == SourceDocument.Kind.UPLOADED) {
            return uploadedSourceFocus(difficulty);
        }
        List<String> sections = SOURCE_SECTIONS.get(difficulty);
        return switch (difficulty) {
            // 2026-08-23: 초급이 두 절을 캐게 됐다. 첫 절은 그날 문서에 신설된 상위 개념 설명으로,
            // 계층·단계·상태의 이름과 역할이 표로 빠짐없이 들어가는 자리다 — 초급 재료가 가장
            // 많이 쌓인다. 한 절만 지목하던 문장을 그대로 두면 새 절이 있어도 안 캔다.
            case BEGINNER -> "[이번 난이도에서 쓸 부분] 문서의 '%s' 절(상위 개념과 그 구성 요소)과 "
                    .formatted(sections.get(0))
                    + "'%s' 절(정의·용어 설명), 그리고 본론의 기본 동작 부분을 쓴다. "
                    .formatted(sections.get(1))
                    + "문서를 읽은 사람이라면 풀 수 있어야 한다.";
            // 2026-09-14: 중급이 심화편으로 옮겨 가면서 주 재료가 바뀌었다. 세 절을 모두
            // 부르는 이유는 SOURCE_SECTIONS 주석에 적은 폴백 때문이다 — 심화편이 없는 옛
            // 문서로 떨어지면 뒤의 두 입문편 절이 그 자리를 대신한다. 어느 문서가 오든
            // 지목한 이름 중 하나는 실제로 있게 해 둔다(없는 이름을 지목하면 모델은 오류를
            // 내지 않고 조용히 아무 데나 캔다 — 8/15에 겪은 사고).
            // 맨 뒤 항목은 2026-09-16 이전 문서가 쓰던 옛 이름이다. 함께 지목하는 이유는 아래
            // 고급과 같다 — 옛 이름 문서가 와도 지목한 이름 중 하나는 실제로 있게 해 둔다.
            // 2026-09-17: 재료가 두 자리로 늘었다(DocumentEditionRule.bodyFor). 심화편 뒤에
            // 입문편의 중급 절 둘이 붙어 온다. "없는 문서라면"이라고 조건절로 적어 두면 모델은
            // 앞의 한 절만 주 재료로 읽고 붙은 부분을 참고 자료로 흘린다 — 실제로 908자에서
            // 5문제를 짜내다 옆의 고급 절까지 넘어간 것이 이 문구를 고치는 이유다.
            case INTERMEDIATE -> "[이번 난이도에서 쓸 부분] 문서가 <이 개념을 실제로 어디에 쓰는지> "
                    + "밝힌 곳과 <왜 그렇게 정했는지> 밝힌 곳, 두 자리를 함께 쓴다 — "
                    + "'%s' 절(옛 문서에서는 '%s'), 그리고 '%s' 소제목과 '%s' 절이다. "
                    .formatted(sections.get(0), sections.get(3), sections.get(1), sections.get(2))
                    + "뒤의 둘은 같은 주제 입문편에서 가져온 부분에 있다. 넷 다 같은 자리이니 "
                    + "한 절에서만 뽑지 말고 고루 캐라 — 한 절을 쥐어짜면 문제가 서로 닮거나 "
                    + "다른 난이도의 절(한계·실패 조건)로 넘어가게 된다. "
                    + "본문 문장 안에서 다른 선택지를 두고 판단한 대목도 같은 재료다. "
                    + "문서가 설명한 원리를 문서에 없는 새로운 상황에 적용해 판단하게 만들어라.";
            // 2026-09-16: 절 이름이 바뀌어 옛 이름을 괄호로 함께 준다. 이름 하나만 지목하면
            // 옛 문서에서 <지목한 절이 없는> 상태가 되고, 그때 모델은 오류를 내지 않고 조용히
            // 아무 데나 캔다 — 8/15에 겪은 사고 그대로다(SOURCE_SECTIONS 주석).
            case ADVANCED -> "[이번 난이도에서 쓸 부분] 문서의 '%s' 절(옛 문서에서는 '%s')과 "
                    .formatted(sections.get(0), sections.get(2))
                    + "'%s' 절을 쓴다. 앞 절은 통하지 않게 되는 조건과 흔한 오해를 담고 있다. "
                    .formatted(sections.get(1))
                    + "그 질문들이 다루는 트레이드오프와 "
                    + "엣지 케이스를 문제로 바꿔라. 다만 질문을 그대로 옮기지 말고 객관식으로 재구성한다.";
        };
    }

    /**
     * 업로드 문서용 — <b>절 이름 대신 역할로 말한다.</b>
     *
     * <p>절 이름을 지목할 수 없으니 "무엇을 담은 대목인가"로 찾게 한다. 우리 양식 문서를
     * 올려도 그대로 통한다 — {@code ## 언제 깨지는가}는 "한계·실패 조건을 다룬 대목"이 맞다.
     * 즉 이 문구는 {@link #sourceFocus}의 상위 집합이고, 배치가 굳이 이쪽을 쓰지 않는 이유는
     * <b>이름으로 지목하는 편이 더 정확하기 때문</b>이다(찾기가 아니라 지정이다).
     *
     * <p>각 난이도의 마지막 문장이 "없으면 적게 내라"인 것이 이 메서드의 알맹이다.
     * 임의의 문서에서 가장 흔한 실패는 <b>고급 재료가 없는 글에서 억지 고급 문제가 나오는 것</b>이다.
     */
    private String uploadedSourceFocus(Difficulty difficulty) {
        String tail = " 문서에 그런 대목이 없으면 억지로 만들지 말고 만들 수 있는 만큼만 내라.";
        return switch (difficulty) {
            case BEGINNER -> "[이번 난이도에서 쓸 부분] 이 문서는 우리 양식이 아니다. 정해진 절 이름을 찾지 마라. "
                    + "용어를 정의한 대목, 구성 요소의 이름과 역할을 밝힌 대목, 기본 동작 순서를 설명한 대목을 "
                    + "찾아 쓴다. 문서를 읽은 사람이라면 풀 수 있어야 한다." + tail;
            case INTERMEDIATE -> "[이번 난이도에서 쓸 부분] 이 문서는 우리 양식이 아니다. 정해진 절 이름을 찾지 마라. "
                    + "문서가 <왜 그렇게 했는지>를 밝힌 대목을 찾아 쓴다 — 다른 선택지를 두고 판단한 문장, "
                    + "장단점을 견준 문장, 실무에서 이 개념을 꺼내 쓰는 장면이 모두 여기 해당한다. "
                    + "문서가 설명한 원리를 문서에 없는 새로운 상황에 적용해 판단하게 만들어라." + tail;
            case ADVANCED -> "[이번 난이도에서 쓸 부분] 이 문서는 우리 양식이 아니다. 정해진 절 이름을 찾지 마라. "
                    + "한계·실패 조건·트레이드오프를 다룬 대목, 흔한 오해를 짚은 대목, "
                    + "\"이럴 때는 이렇게 하라\"는 대응 지침을 찾아 쓴다. "
                    + "여러 대응 중 무엇을 고를지 묻는 형태로 재구성한다." + tail;
        };
    }

    /** 프롬프트 토큰 절약용 앞부분 자르기. 잘렸음을 말줄임표로 알려 모델이 문장이 끊긴 것으로 오해하지 않게 한다. */
    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    // domainHint(Domain)는 Task 2에서 지웠다 — DomainHints.hintFor(DomainCode)로 대체됐다.
    // 문구 자체는 DomainHints.BUILT_IN에 그대로 옮겨 뒀다(문자 단위 대조 완료, docs/21).

    /**
     * 난이도 한 줄 — 조건 목록에 들어갈 요약. 실제 보기와 예시는 {@link #difficultyExample}이 맡는다.
     *
     * <p><b>왜 한 줄 예시에서 통 문항으로 바꿨나.</b> 예전에는 지문 한 줄만 보여 줬는데,
     * 그것으로는 <b>오답을 어떻게 설계하는가</b>가 전달되지 않았다. 그런데 난이도를 실제로
     * 가르는 것이 바로 오답이다 — 2026-08-13(중급)과 08-14(고급)의 지문은 형태가 똑같이
     * "상황 서술 → 원인과 대응으로 가장 적절한 것은?"이었고, 지문만 보면 구분이 안 됐다.
     * 갈린 곳은 보기였다. 중급 오답에는 "시각 동기화가 어긋났다" 같은 <b>명백히 틀린</b> 것이
     * 섞여 있었고, 고급 오답은 넷 다 "다른 상황이었다면 옳았을 대응"이었다(stampede 대응,
     * 샤딩, TTL 연장 — 전부 어떤 조건에서는 정답이다).
     *
     * <p>그 차이를 서술로 설명하는 것보다 <b>보여 주는 편이 훨씬 강하다</b>. 이 저장소는
     * 이미 반대 방향으로 그것을 확인했다 — 문서 프롬프트에서 "핵심 용어를 하이픈 목록으로
     * 쓴다"는 규칙이 있었는데 바로 아래 예시에 하이픈이 없었더니, 모델은 규칙이 아니라
     * <b>예시를 따랐다</b>. 예시가 규칙을 이긴다면 예시 쪽에 제대로 투자하는 것이 맞다.
     *
     * <p><b>왜 세 난이도가 같은 주제(TIME_WAIT)인가.</b> 주제가 다르면 "무엇이 한 단계
     * 올라간 것인지"가 주제 차이에 가려진다. 축을 고정해야 <b>같은 개념을 세 층으로 파는 법</b>이
     * 드러난다. 대신 주제를 베껴 갈 위험이 생기므로 각 예시에 "주제는 가져다 쓰지 마라"를 붙였다.
     *
     * <p><b>왜 승인된 기존 문제를 예시로 쓰지 않았나.</b> 그게 첫 안이었는데, 승인된 17문제는
     * <b>지금 고치려는 그 기준으로 만들어진 것들</b>이다. 초급이 어렵다는 것이 출발점인데
     * 그 초급을 예시로 주면 어긋난 기준을 그대로 굳힌다. 예시는 새로 썼다.
     *
     * <p>여기가 아니라 시스템 프롬프트에 넣지 않은 이유: 한 번에 한 난이도만 만들므로
     * 나머지 두 예시는 그 요청에 쓸모가 없다. 입력 토큰 몇백 개는 큰 비용이 아니지만,
     * 쓰지 않을 예시가 프롬프트에 있으면 모델이 그쪽 칸으로 넘어갈 빌미가 된다.
     *
     * <p><b>2026-08-18: 초급 예시에 "용어 처리" 두 줄을 붙였다.</b> 같은 날 새로 넣은
     * {@code [용어]} 절은 "어려운 용어는 괄호로 뜻을 붙여라"와 "단, 그 용어가 정답이면 붙이지
     * 마라"를 함께 요구한다. 뒤쪽은 {@code [금지]}의 "지문이 답을 알려 주는 문제"와 부딪히지
     * 않으려고 둔 예외인데, <b>규칙 두 개가 서로를 자르면 모델은 둘 중 하나를 버린다</b>.
     * 마침 이 초급 예시가 그 예외에 딱 맞는 사례라(정답이 TIME_WAIT라는 용어 자체다)
     * 두 줄로 실물을 보여 주는 편이 설명을 늘리는 것보다 확실하다. 어느 쪽을 버렸는지는
     * 문제를 읽어야만 보이므로, 애초에 버릴 일이 없게 만드는 것이 낫다.
     *
     * <p><b>초급 예시의 보기는 용어만 적는다.</b> 지문이 뜻을 풀어 쓴 문제라, 보기에 뜻을 붙이면
     * 정답만 지문을 되받는다({@code [오답 보기의 조건]}이 금지한 꼴). 예시가 그 꼴이면 규칙을
     * 적어 둬도 모델은 예시를 따른다 — {@code ProblemItemRule.TERM_PAIR_GIVEAWAY_MIN_RUN}이 재는 결함이다.
     */
    private String difficultyRule(Difficulty difficulty) {
        return switch (difficulty) {
            // 지문 길이에 숫자를 박는 이유: 이 저장소에서 <숫자로 적은 지시만> 실제로 지켜졌다
            // (해설 분량, 문서 절 개수). "한두 문장"은 모델도 사람도 서로 다르게 읽는다.
            // 이 숫자는 검증기가 재는 값과 같아야 한다(ProblemItemRule.BEGINNER_QUESTION_MAX) —
            // 갈라지면 지시대로 쓴 지문이 매번 경고를 달고 나온다.
            case BEGINNER -> ("초급 — 용어가 무엇인지를 묻는다. 문서의 정의 부분을 읽은 사람이면 푼다. "
                    + "지문은 상황 없이 한두 문장(%d자 이내)이고, 한 문제에 개념 하나만 담는다.")
                    .formatted(ProblemItemRule.BEGINNER_QUESTION_MAX);
            // 2026-09-17: "상황에 적용" → "원리 자체를 한 걸음 더". 중급은 초급 바로 위 한 칸이고,
            // 장면을 읽어 내는 부담은 고급의 몫이다. 길이 숫자를 여기에도 박는 이유는 초급과 같다.
            case INTERMEDIATE -> ("중급 — 원리를 쓸 줄 아는지를 묻는다. 초급 바로 위 한 칸이다. "
                    + "지문에 실무 장면을 넣지 마라. 한두 문장(%d자 이내)으로 묻는 것만 적는다.")
                    .formatted(ProblemItemRule.INTERMEDIATE_QUESTION_MAX);
            case ADVANCED -> "고급 — 여러 대응 중 무엇을 고를지 묻는다. "
                    + "지문에 상황과 <이미 시도한 것>이 있다.";
        };
    }

    static final String BEGINNER_EXAMPLE = Prompts.load("problem/example-beginner.txt");
    static final String INTERMEDIATE_EXAMPLE = Prompts.load("problem/example-intermediate.txt");
    static final String ADVANCED_EXAMPLE = Prompts.load("problem/example-advanced.txt");

    /**
     * 난이도별 <b>완성된 예시 문항</b> — 보기 넷과 "왜 이 난이도인가"까지 붙인다.
     * 조립 위치와 이 블록을 따로 둔 이유는 {@link #difficultyRule}의 주석을 보라.
     */
    private String difficultyExample(Difficulty difficulty) {
        return switch (difficulty) {
            case BEGINNER -> BEGINNER_EXAMPLE;
            case INTERMEDIATE -> INTERMEDIATE_EXAMPLE;
            case ADVANCED -> ADVANCED_EXAMPLE;
        };
    }

    /** 유형별 형식 규칙 — 스키마가 형태는 강제하지만 "빈 값 규약"(docs/01)은 프롬프트로 알려야 한다. */
    private String typeRule(ProblemType type) {
        return switch (type) {
            // "명백히 틀린 보기 금지"를 여기 두었더니 난이도 규칙과 정면으로 부딪혔다 —
            // 초급 오답은 <다른 개념의 정의>라 아는 사람에게는 명백히 갈려야 하고,
            // 중급도 "명백히 틀린 오답이 있어야 중급"이다. 셋 다에 걸리는 형식 규칙 자리에
            // 난이도의 몫을 적어 두면, 규칙이 싸우고 어느 쪽이 이길지는 그날 운이 된다.
            // 형식은 형식만 말하고, 오답의 그럴듯함은 난이도 쪽으로 넘긴다.
            case MULTIPLE_CHOICE ->
                    "객관식 — choices에 보기 정확히 4개, 그중 correct=true 정확히 1개. answer는 빈 문자열 \"\". "
                            + "오답이 얼마나 그럴듯해야 하는지는 난이도가 정한다([난이도가 뜻하는 것] 참고). "
                            + "어느 난이도든 성의 없이 지어낸 보기는 금지다";
            case OX -> "OX — choices는 빈 배열, answer는 \"O\" 또는 \"X\"";
            case SHORT_ANSWER ->
                    "단답형 — choices는 빈 배열, answer는 정답 단어(소문자, 복수 정답·영문 약어/풀네임은 |로 구분. 예: \"arp|address resolution protocol\")";
            // 짝짓기·순서 배열은 choices를 <보기>가 아닌 다른 뜻으로 쓴다. 그 뜻을 여기서 못 박아
            // 두지 않으면 모델이 객관식처럼 correct=true를 하나 찍어 보낸다(실제로 자주 그런다).
            case MATCHING ->
                    "짝짓기 — choices의 한 항목이 한 쌍이다. text에 왼쪽(용어), matchText에 오른쪽(그 용어의 설명)을 "
                            + "적는다. correct는 전부 false, answer는 빈 문자열 \"\". 쌍은 정확히 4개";
            case ORDERING ->
                    "순서 배열 — choices에 배열할 항목을 4개 적는다(text만 쓰고 matchText는 빈 문자열, correct는 전부 false). "
                            + "answer에는 정답 순서를 <choices의 번호>로 적는다. 예: choices가 [ㄱ,ㄴ,ㄷ,ㄹ]이고 "
                            + "정답이 ㄹ→ㄴ→ㄱ→ㄷ이면 answer는 \"4|2|1|3\"";
            case ESSAY -> throw new BusinessException(ErrorCode.QUIZ_002,
                    "서술형(ESSAY)은 자동채점 미지원이라 생성 대상이 아닙니다.");
        };
    }
}
