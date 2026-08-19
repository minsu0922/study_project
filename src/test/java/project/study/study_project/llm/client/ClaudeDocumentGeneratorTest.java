package project.study.study_project.llm.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.global.common.Domain;
import project.study.study_project.llm.support.DocumentDraftValidator;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 문서 생성 프롬프트 조립 테스트 — docs/15.
 *
 * <p><b>Claude를 부르지 않는다.</b> 검증 대상은 "모델이 좋은 문서를 쓰는가"가 아니라
 * <b>우리가 준 재료가 실제로 프롬프트에 실리는가</b>다. 이건 API 없이 확인할 수 있고,
 * 실제 문서 품질은 사람이 검수하면서 판단할 몫이다.
 *
 * <p>이 테스트가 지키는 것은 <b>조용히 무력해지는 실패</b>다. 주제를 지정했는데 프롬프트에
 * 안 실리면 모델은 그냥 아무 주제나 쓰고, 회피 목록이 안 실리면 이미 있는 문서를 또 쓴다.
 * 둘 다 오류 없이 그럴듯한 결과가 나와서 <b>검수 단계에서도 눈치채기 어렵다</b> —
 * "왜 내가 지정한 주제가 아니지?"를 몇 번 겪고서야 알게 되는 종류다.
 */
class ClaudeDocumentGeneratorTest {

    private final ClaudeDocumentGenerator generator = new ClaudeDocumentGenerator("claude-opus-5");

    @Test
    @DisplayName("주제 범위를 주면 '그 안에서 하나 골라 쓰라'는 지시가 실린다 — 범위 전체를 개괄하면 안 된다")
    void includesGivenTopicAsRange() {
        String prompt = generator.buildPrompt(Domain.NETWORK, "TCP 혼잡 제어", List.of(), List.of());

        assertThat(prompt).contains("주제 범위: TCP 혼잡 제어");
        assertThat(prompt).as("범위일 때는 '이 주제로 써라'가 아니라 '이 안에서 골라라'여야 한다")
                .contains("이 범위 안에서");
    }

    @Test
    @DisplayName("범위를 비우면 분야 안에서 모델이 직접 고르도록 지시한다")
    void asksModelToPickWhenTopicOmitted() {
        String prompt = generator.buildPrompt(Domain.NETWORK, null, List.of(), List.of());

        assertThat(prompt).contains("주제를 하나 골라");
        assertThat(prompt).doesNotContain("주제 범위:");
    }

    @Test
    @DisplayName("공백만 있는 범위는 지정하지 않은 것으로 본다 — 워크플로 입력이 비면 그렇게 온다")
    void treatsBlankTopicAsUnspecified() {
        String prompt = generator.buildPrompt(Domain.NETWORK, "   ", List.of(), List.of());

        assertThat(prompt).contains("주제를 하나 골라");
        assertThat(prompt).doesNotContain("주제 범위:");
    }

    /**
     * 2026-08-19에 사용자가 지적한 결함이다. 분야만 주면 모델은 <b>분야 이름에 가장 가까운
     * 가장 큰 주제</b>를 고른다("스프링·백엔드" → "스프링 트랜잭션"). 그러면 6,800자를 개괄에
     * 다 쓰고 {@code ## 언제 깨지는가}가 얕아져 사흘 뒤 고급 날에 재료가 마른다.
     *
     * <p>규칙 문장이 아니라 (X)/(O) 예시로 지시한 것을 함께 못 박는다 — 이 저장소는
     * "예시가 규칙을 이긴다"를 두 번 확인했다(8/15 하이픈 사고, 8/18 절 형식 흔들림).
     */
    @Test
    @DisplayName("주제를 비우면 '분야만큼 넓게 고르지 마라'가 예시와 함께 실린다 — 분야 이름만 한 주제가 나왔다")
    void asksToNarrowTopicWhenModelPicks() {
        String prompt = generator.buildPrompt(Domain.BACKEND_FRAMEWORK, null, List.of(), List.of());

        assertThat(prompt).contains("분야 전체를 개괄하지 마라");
        assertThat(prompt).as("크기 지시는 예시로 줘야 지켜진다").contains("(X)").contains("(O)");
        assertThat(prompt).as("예시 주제를 그대로 쓰는 것은 막아야 한다").contains("가져다 쓰지 마라");
    }

    /**
     * 범위는 여러 번 쓰인다(V11). 그때마다 같은 주제가 나오면 우물을 파는 뜻이 없으므로,
     * "이미 문서가 있는 주제와 겹치지 마라"가 <b>범위를 줬을 때도</b> 실려야 한다.
     */
    @Test
    @DisplayName("범위를 줄 때 '아직 안 다룬 것을 고르라'가 함께 실린다 — 같은 범위가 계속 돌아온다")
    void asksToPickUncoveredTopicWithinRange() {
        String prompt = generator.buildPrompt(Domain.BACKEND_FRAMEWORK, "Spring 트랜잭션", List.of(), List.of());

        assertThat(prompt).contains("범위 전체를 개괄하지 마라");
        assertThat(prompt).contains("아직").contains("다루지 않은 것이 오늘의 주제다");
        assertThat(prompt).as("이미 한 편 크기인 범위를 억지로 더 쪼개면 알맹이가 없어진다")
                .contains("억지로 더 좁히지 마라");
    }

    @Test
    @DisplayName("기존 문서 제목이 중복 회피 목록으로 실린다 — 없으면 있는 주제를 또 쓴다")
    void includesAvoidTitles() {
        String prompt = generator.buildPrompt(Domain.NETWORK, null,
                List.of("TCP 3-way 핸드셰이크와 연결 종료", "HTTP와 HTTPS — 무엇이 다른가"), List.of());

        assertThat(prompt).contains("이미 문서가 있는 주제");
        assertThat(prompt).contains("TCP 3-way 핸드셰이크와 연결 종료");
        assertThat(prompt).contains("HTTP와 HTTPS — 무엇이 다른가");
    }

    @Test
    @DisplayName("기존 태그가 실린다 — 없으면 tcp/TCP/tcp-handshake처럼 비슷한 태그가 계속 늘어난다")
    void includesPreferredTags() {
        String prompt = generator.buildPrompt(Domain.NETWORK, null, List.of(), List.of("tcp", "http"));

        assertThat(prompt).contains("기존 태그");
        assertThat(prompt).contains("tcp, http");
    }

    @Test
    @DisplayName("태그 목록을 줄 때는 '새 태그 남발 금지'가 함께 실린다 — 1차 실물이 태그 3개를 전부 새로 만들었다")
    void limitsNewTagsWhenPreferredTagsGiven() {
        String prompt = generator.buildPrompt(Domain.NETWORK, null, List.of(), List.of("tcp", "http"));

        assertThat(prompt).contains("새로 만드는 태그는 1개까지");
        assertThat(prompt).as("진짜 막아야 하는 건 개수가 아니라 같은 개념이 여러 이름으로 갈라지는 것")
                .contains("뜻이 겹치는 태그는 절대 새로 만들지 마라");
    }

    @Test
    @DisplayName("목록이 비어 있으면 그 블록 자체를 넣지 않는다 — 빈 제목만 남으면 토큰 낭비다")
    void omitsEmptyBlocks() {
        String prompt = generator.buildPrompt(Domain.NETWORK, null, List.of(), List.of());

        assertThat(prompt).doesNotContain("이미 문서가 있는 주제");
        assertThat(prompt).doesNotContain("기존 태그");
    }

    @Test
    @DisplayName("스프링·백엔드와 언어·런타임은 경계 힌트가 붙는다 — 둘 다 'Java 관련'이라 헷갈린다")
    void addsDomainHintForConfusablePair() {
        assertThat(generator.buildPrompt(Domain.BACKEND_FRAMEWORK, null, List.of(), List.of()))
                .contains("순수 JVM/GC 주제는 제외");
        assertThat(generator.buildPrompt(Domain.LANGUAGE_RUNTIME, null, List.of(), List.of()))
                .contains("프레임워크 주제는 제외");
        assertThat(generator.buildPrompt(Domain.NETWORK, null, List.of(), List.of()))
                .as("경계가 헷갈리지 않는 분야에는 군더더기를 붙이지 않는다")
                .doesNotContain("제외)");
    }

    /**
     * <b>왜 시스템 프롬프트 본문까지 테스트하는가.</b> 보통 프롬프트 문구는 테스트 대상이 아니다.
     * 여기서 보는 건 문구의 좋고 나쁨이 아니라 <b>두 곳에 적힌 같은 정보가 어긋나는 것</b>이다.
     *
     * <p>{@code REQUIRED_SECTIONS}는 1단계-B에서 "이 섹션이 없는 문서는 반려" 검증에 쓸 목록이다.
     * 누군가 프롬프트의 섹션 제목만 바꾸면 모델은 새 제목으로 쓰고 검증기는 옛 제목을 찾으므로
     * <b>멀쩡한 문서가 전부 반려된다</b>. 반대로 목록만 바꾸면 빈껍데기가 통과한다.
     * 둘 다 예외 없이 조용히 일어나서, 이 테스트가 없으면 한참 뒤에야 알게 된다.
     */
    @Test
    @DisplayName("필수 섹션 목록과 프롬프트의 섹션 제목이 일치한다 — 어긋나면 검증기가 헛돈다")
    void requiredSectionsMatchPrompt() {
        assertThat(ClaudeDocumentGenerator.REQUIRED_SECTIONS)
                .allSatisfy(section -> assertThat(ClaudeDocumentGenerator.SYSTEM_PROMPT)
                        .as("프롬프트 [문서 구조]에 '%s'가 있어야 한다", section)
                        .contains(section));
    }

    @Test
    @DisplayName("면접 질문 섹션과 용어 정의 규칙이 프롬프트에 살아 있다 — 1차 실물에서 빠졌던 두 가지")
    void keepsInterviewQuestionAndTerminologyRules() {
        assertThat(ClaudeDocumentGenerator.SYSTEM_PROMPT)
                .as("면접 질문은 학습용이자 2단계 문제 생성의 재료다")
                .contains("## 면접에서 이렇게 물어본다")
                .as("용어를 정의 없이 지나가면 초급 재료가 통째로 비는 셈이 된다")
                .contains("[용어 다루기]")
                .as("분량을 올린 건 고급 재료 절과 면접 질문 절에 지면을 주기 위해서다")
                .contains("5,000~6,800자");
    }

    /**
     * <b>2026-08-18 개정의 핵심 셋을 못 박는다.</b> 사용자가 8/18 고급 문제를 읽고
     * "모르는 용어가 설명 없이 나온다"고 지적했고, 8/15 실물을 세어 보니 정의된 용어는
     * {@code ## 무엇인가}의 5개뿐이었다. 뒤쪽 절에서 처음 나온 잠금·격리 용어 아홉 개는
     * 정의 없이 지나갔다.
     *
     * <p><b>규칙이 없어서가 아니었다는 것이 이 테스트를 만든 이유다.</b> [용어 다루기]에는
     * 이미 "정의 없이 지나가는 용어가 하나도 없어야 한다"가 있었다. 안 지켜진 원인은
     * <b>지면과 형식</b>이었으므로 고친 것도 지면과 형식이다 — 용어 표를 둘 자리를 만들고,
     * 세 줄로 못 박힌 절에 네 번째 줄을 열어 줬다.
     *
     * <p>그래서 여기서 지키는 것은 문구가 아니라 <b>자리</b>다. 다음에 프롬프트를 다듬다가
     * 이 셋 중 하나가 빠지면 증상은 조용하다 — 문서는 멀쩡히 생성되고 검증도 대부분 통과하며,
     * 사흘 뒤 문제를 읽는 사람만 다시 막힌다.
     */
    @Test
    @DisplayName("용어를 둘 자리가 프롬프트에 있다 — 규칙을 세게 적는 것만으로는 안 지켜졌다")
    void makesRoomForTerminology() {
        assertThat(ClaudeDocumentGenerator.SYSTEM_PROMPT)
                .as("뒤쪽 절에서 꺼낼 용어를 미리 올려 두는 자리")
                .contains("### 용어 한눈에")
                .contains("| 용어 | 한 줄 뜻 | 언제 쓰나 |")
                .as("가장 어려운 용어가 처음 나오는 절인데 세 줄로 막혀 있었다")
                .contains("(그 항목만 네 줄)")
                .as("이름을 풀어 쓴 것으로 정의를 대신하면 뜻이 전달되지 않는다")
                .contains("이름만으로 동작을 알 수 없는 용어")
                .as("SQL 구문이 세 번 나오도록 뜻이 없었다")
                .contains("SQL 구문·명령을 쓰면 그것이 무엇을 하는지 한 줄로 붙인다");
    }

    /**
     * <b>비유를 하나로 묶어 둔 규칙을 푼 것을 지킨다.</b> 전에는 "비유는 문서 전체에서
     * 왜 필요한가 절에서만"이었다. 그 결과 8/15 실물은 뒤쪽 다섯 절을 <b>"스냅샷"이라는 말
     * 하나로</b> 설명했고, 그 말을 모르면 절 다섯 개를 통째로 잃는 문서가 됐다.
     *
     * <p>비유 개수를 무제한으로 풀지 않은 이유는 [덜어낼 것]과 부딪히기 때문이다 —
     * 비유는 늘어나면 곧 부연이 된다. 그래서 <b>2개</b>라는 숫자를 박았다. 숫자가 박힌 지시만
     * 실제로 지켜졌다는 것이 이 프롬프트에서 두 번 확인된 사실이다(8/14 고급 재료, 8/18 해설 분량).
     */
    @Test
    @DisplayName("추상적인 동작을 다루는 절에 비유를 하나 더 허용한다 — 스냅샷 하나로 다섯 절을 설명했다")
    void allowsSecondAnalogyForInvisibleMechanics() {
        assertThat(ClaudeDocumentGenerator.SYSTEM_PROMPT)
                .as("무제한으로 풀면 부연이 된다 — 숫자를 박은 지시만 지켜진다")
                .contains("문서 전체에서 비유는 2개까지다")
                .as("한 낱말에 기댄 설명이 이번 사고의 다른 얼굴이다")
                .contains("그 절의 설명을 한 낱말에만 기대지 마라");
    }

    /**
     * <b>새 절 둘에 완성 예시가 붙어 있는지.</b> 8/18 재생성분에서 {@code ## 언제 깨지는가}의
     * 항목 형식이 흔들렸다 — 전에는 {@code **1. 쓰기 스큐**}였는데 번호가 사라졌고, 검증기가
     * 항목 7개를 1개로 세어 헛경고를 냈다. 검증기 패턴도 고쳤지만 근본 원인은 <b>프롬프트가
     * 이 절의 형식을 한 번도 보여 준 적이 없다</b>는 것이다.
     *
     * <p>이 저장소는 예시가 규칙을 이긴다는 것을 반대 방향으로 이미 겪었다(8/15 하이픈 사고).
     * 그래서 지키는 것은 예시 문구가 아니라 <b>예시가 존재한다는 사실</b>과, 예시가 주제를
     * 베껴 가지 못하게 막는 한 줄이다. 뒤쪽이 빠지면 모든 문서의 깨지는 조건이 캐시 이야기가 된다.
     */
    @Test
    @DisplayName("새 절 둘에 완성 예시가 붙어 있다 — 형식을 말로만 적으면 매번 다른 모양으로 온다")
    void showsWorkedExamplesForNewSections() {
        assertThat(ClaudeDocumentGenerator.SYSTEM_PROMPT)
                .as("항목 제목 형식이 흔들려 검증기가 헛울린 자리")
                .contains("**캐시 스탬피드**")
                .as("네 번째 줄(새 용어 정의)까지 예시가 보여 줘야 한다")
                .contains("- **뮤텍스(mutex)** —")
                .as("칸 이름만 주면 '언제 쓰나' 칸이 흔들린다")
                .contains("| TTL(time to live) |")
                .as("이게 빠지면 모든 문서의 깨지는 조건이 캐시 이야기가 된다")
                .contains("주제(캐시)는 가져다 쓰지 마라");
    }

    /**
     * <b>2026-08-14 개정의 핵심을 못 박는다.</b> 고급 재료가 사흘을 못 버텨 마지막 날 문제가
     * 3개(그중 1개는 껍데기)만 나온 사고가 있었다. 원인은 고급 재료 지시에 <b>개수가 없었던</b>
     * 것이다 — 실측에서 유일하게 숫자가 박혀 있던 "면접 질문 3~4개"만 그대로 지켜졌다.
     *
     * <p>이 테스트가 지키는 것은 문구의 좋고 나쁨이 아니라 <b>개수 지시가 사라지는 것</b>이다.
     * 프롬프트를 다듬다가 "5가지 이상"이 빠지면 증상은 조용하다 — 문서는 멀쩡해 보이고,
     * 사흘 뒤 고급 문제 날에 가서야 재료가 없다는 게 드러난다.
     */
    @Test
    @DisplayName("고급 재료에 개수 지시가 박혀 있다 — 이게 빠지면 사흘 뒤 고급 날에 재료가 마른다")
    void pinsAdvancedMaterialQuota() {
        assertThat(ClaudeDocumentGenerator.SYSTEM_PROMPT)
                .as("고급 전용 절이 모델 재량이면 아예 없는 문서가 나온다")
                .contains("## 언제 깨지는가")
                .as("개수를 박은 지시만 실제로 지켜졌다는 것이 실측 결과다")
                .contains("서로 다른 것으로 5가지 이상")
                .contains("4개 이하면 실패다")
                .as("분량이 빠듯할 때 제일 먼저 깎이는 것이 이 절이라, 깎지 말라고 따로 적는다")
                .contains("상한 때문에 줄이지 않는다");
    }

    /**
     * <b>프롬프트의 분량 지시와 검증기의 경고선이 같은 숫자인지</b> 본다.
     *
     * <p>이 짝이 어긋나면 증상이 고약하다. 프롬프트가 6,000자까지 쓰라고 하는데 경고선이
     * 5,800이면, <b>지시를 잘 따른 문서일수록 매번 경고를 달고</b> 나온다. 경고가 상시로 뜨면
     * 사람이 경고 자체를 안 보게 되고, 그러면 진짜 문제가 왔을 때도 지나친다.
     *
     * <p>2026-08-14과 08-15에 분량을 세 번 올렸는데 그때마다 두 곳을 따로 고쳐야 했다.
     * 문자열을 뒤져 비교하는 것이 예쁜 테스트는 아니지만, 두 숫자가 다른 파일에 사는 한
     * 잊는 것을 막을 방법은 이것뿐이다.
     */
    @Test
    @DisplayName("분량 지시와 검증기 경고선이 같은 숫자다 — 어긋나면 지시를 잘 따른 문서가 매번 경고를 단다")
    void lengthInstructionMatchesValidatorWarnLine() {
        String warnLine = "%,d자".formatted(DocumentDraftValidator.WARN_LENGTH);

        assertThat(ClaudeDocumentGenerator.SYSTEM_PROMPT)
                .as("프롬프트 [분량]의 상한이 검증기 경고선(%s)과 같아야 한다", warnLine)
                .contains(warnLine);
    }

    /**
     * 정의를 맨 앞에 두라는 요구와 담백한 문체 규칙이 살아 있는지 본다.
     * 1차 실물은 캐시의 정의가 비유 세 문장 뒤에 나왔고, 그마저 "왜 필요한가" 안에 묻혀 있었다.
     */
    @Test
    @DisplayName("정의가 첫 절이고, 군더더기 금지 규칙이 실물 예시와 함께 실린다")
    void putsDefinitionFirstAndBansFiller() {
        assertThat(ClaudeDocumentGenerator.SYSTEM_PROMPT)
                .as("개념·용어 정의가 문서의 첫 절이어야 한다")
                .contains("## 무엇인가")
                .contains("첫 문장이 이 개념의 정의다")
                .as("추상적 금지보다 실물 예시가 훨씬 잘 지켜진다")
                .contains("[덜어낼 것]")
                .contains("한 문장을 지웠는데 뜻이 그대로면 그 문장은 군더더기다");
    }

    /**
     * 용어 목록을 <b>하이픈 목록</b>으로 요구하는지.
     *
     * <p>2026-08-15 실물에서 용어 5개가 화면에서 한 문단으로 뭉쳐 나왔다. 모델은 지시를 그대로
     * 지켰고, 틀린 것은 지시 쪽이었다 — 예시가 {@code **용어** — 정의.}였는데 마크다운의
     * 홑줄바꿈은 문단을 끊지 않는다. 정의를 찾기 쉽게 하려고 만든 절이 가장 읽기 힘든
     * 덩어리가 됐다.
     *
     * <p>이 테스트가 지키는 것은 하이픈 한 글자다. 사소해 보이지만 검증기가 잡을 수 없는
     * 종류의 결함이라(원문만 봐서는 멀쩡하다) 여기서 못 박지 않으면 다음에 프롬프트를
     * 손볼 때 조용히 사라진다.
     */
    @Test
    @DisplayName("핵심 용어는 하이픈 목록으로 요구한다 — 줄만 바꾸면 화면에서 한 문단으로 붙는다")
    void requiresBulletedTermList() {
        assertThat(ClaudeDocumentGenerator.SYSTEM_PROMPT)
                .contains("핵심 용어 3~5개를 하이픈 목록으로 정의한다")
                .as("왜 하이픈이어야 하는지를 함께 적어야 모델이 규칙을 지킨다")
                .contains("줄만 바꾸면 마크다운이 한 문단으로 붙여 버린다");
    }

    /**
     * "실무에서는 이렇게 쓴다" 절이 <b>쓰는 장면</b>을 요구하는 자리로 남아 있는지 본다.
     *
     * <p>이 절은 두 방향으로 변질된다. 하나는 <b>설정 이름·기본값 카탈로그</b> — 처음 이름이
     * "어디서 만나는가"였을 때 지시가 실제로 그렇게 흘렀다. 다른 하나는 <b>본론의 요약</b>이다.
     * 모델 입장에서는 둘 다 안전한 선택이라(원리를 한 번 더 쓰거나 목록을 늘어놓는 것이,
     * 구체적인 장면을 지어내는 것보다 틀릴 위험이 적다) 못 박아 두지 않으면 돌아간다.
     *
     * <p>카탈로그로 흐르는 것은 이제 [금지]의 "제품별 설정 기본값"이 막는다. 그 금지가
     * 이 절을 지키는 장치이기도 하다는 뜻이라, 여기서 함께 확인한다 — 한쪽만 남으면
     * 다시 설정값 목록으로 돌아간다.
     */
    @Test
    @DisplayName("'실무에서는 이렇게 쓴다'는 쓰는 장면을 요구한다 — 카탈로그나 본론 요약으로 변질되지 않게")
    void pinsHowItIsUsedInPracticeSection() {
        assertThat(ClaudeDocumentGenerator.SYSTEM_PROMPT)
                .contains("## 실무에서는 이렇게 쓴다")
                .as("장면 하나를 처음부터 끝까지 — 이게 이 절의 알맹이다")
                .contains("장면 하나를 골라 처음부터 끝까지")
                .as("방치하면 본론 요약이 된다")
                .contains("원리를 다시 설명하지 마라")
                .as("설정값 목록으로 흐르는 것은 금지 항목이 막는다")
                .contains("제품별 설정 기본값");
    }

    /**
     * <b>ASCII 다이어그램을 걷어낸 결정을 못 박는다.</b> 이건 취향 문제가 아니라 측정된 결함이다 —
     * 8/15 실물의 격리 수준 표를 렌더링 상태에서 재 보니 헤더 줄의 칸 끝이 182px, 바로 아래
     * 데이터 줄의 같은 칸이 161px로 21px 어긋났다. 이 화면의 등폭 글꼴에서 한글 4글자와
     * 영문 8칸이 똑같이 56px이라, 모델이 한글 한 글자를 공백 2칸으로 환산해 가며 칸을 맞춰야
     * 하는데 그걸 매번 해낼 리가 없다.
     *
     * <p>이 테스트가 지키는 것은 <b>표현 수단의 분업</b>이다. "비교는 표로"가 빠지면 모델은
     * 곧바로 ASCII로 돌아간다(예시가 그쪽이 훨씬 많다). 증상은 조용하다 — 문서는 생성되고
     * 검증도 통과하며, 읽는 사람만 매번 불편하다.
     */
    @Test
    @DisplayName("비교는 표로, 순서는 번호 목록으로 — ASCII로 칸을 맞추면 한글 폭 때문에 어차피 어긋난다")
    void bansAsciiColumnAlignment() {
        assertThat(ClaudeDocumentGenerator.SYSTEM_PROMPT)
                .contains("비교·대조는 마크다운 표로 쓴다")
                .contains("ASCII로 칸을 맞추지 마라")
                .as("순서 있는 흐름의 대체 수단까지 줘야 ASCII로 돌아가지 않는다")
                .contains("순서가 있는 흐름은 번호 목록으로 쓴다")
                .as("굵은 글씨 한 줄은 마크다운에서 그냥 문단이라 위계를 못 만든다")
                .contains("### 왜 이렇게 설계됐는가");
    }

    /**
     * <b>설계 근거가 통째로 사라지는 것을 막는다.</b> "### 왜 이렇게 설계됐는가"를 문서 전체
     * 1개로 제한한 것은 가독성 결정이다(8/15 실물은 세 섹션 모두에 달려 있어 의례가 됐다).
     * 그런데 그 블록은 {@code [난이도 재료]}가 정의한 <b>중급 재료 그 자체</b>이기도 하다 —
     * "다른 선택지가 있었는데 왜 이걸 골랐는지".
     *
     * <p>제목만 줄이고 근거까지 줄면 중급 문제 날에 재료가 마른다. 8/14에 고급 날이
     * 빈손으로 끝난 것과 똑같은 구조다. 그래서 "나머지는 본문 문장으로 녹여 쓰되 근거
     * 자체는 빼지 마라"를 함께 박아 두고, 그 문장이 사라지지 않는지 여기서 지킨다.
     */
    @Test
    @DisplayName("소제목은 1개로 줄이되 설계 근거는 남긴다 — 근거까지 줄면 중급 날에 재료가 마른다")
    void keepsDesignRationaleEvenWithOneHeading() {
        assertThat(ClaudeDocumentGenerator.SYSTEM_PROMPT)
                .as("소제목 반복은 의례가 된다 — 개수를 못 박는다")
                .contains("문서 전체에서 1개다")
                .as("제목을 줄인다고 근거까지 줄이면 중급 재료가 3분의 1이 된다")
                .contains("본문 문장으로 녹여 쓴다")
                .contains("근거 자체를 빼지 마라");
    }

    /**
     * "왜 필요한가"가 <b>곤란함 → 장점 → 단점</b> 세 박자를 요구하는지 본다.
     *
     * <p>전에는 "없으면 무엇이 곤란한지"만 요구해서 <b>문제 제기로만 끝났다</b>. 정작 이걸 왜
     * 쓰는지는 장점을 봐야 알 수 있는데 그 자리가 없었다.
     *
     * <p>단점을 함께 요구하는 것이 짝이다. 장점만 나열하면 광고문이 되고, 무엇보다 그 단점이
     * 곧 "## 언제 깨지는가"의 씨앗이다 — 두 절이 이어지지 않으면 문서가 앞뒤로 갈라진다.
     * 장점을 "숫자로 말할 수 있으면 숫자로"라고 못 박은 것은 [덜어낼 것]의 과장 금지와 같은
     * 규칙인데, 여기서 한 번 더 적는 이유는 장점 문단이 과장이 가장 나오기 쉬운 자리여서다.
     */
    @Test
    @DisplayName("'왜 필요한가'는 곤란함·장점·단점을 함께 요구한다 — 장점만 쓰면 광고문이 된다")
    void requiresBenefitAndCostInWhyItMatters() {
        assertThat(ClaudeDocumentGenerator.SYSTEM_PROMPT)
                .as("문제 제기로만 끝나면 왜 쓰는지가 빠진다")
                .contains("이어서 썼을 때의 장점을 쓴다")
                .as("장점은 과장이 가장 나오기 쉬운 자리다")
                .contains("숫자로 말할 수 있으면 숫자로")
                .as("단점은 '언제 깨지는가'의 씨앗이라 두 절을 잇는다")
                .contains("단점이 있으면 한 줄 붙인다");
    }

    /**
     * <b>주제·중복 회피 목록·태그는 시스템 프롬프트에 없어야 한다.</b> 그 셋은 요청마다 바뀌므로
     * {@link ClaudeDocumentGenerator#buildPrompt} 가 만드는 user 메시지의 몫이다.
     *
     * <p>왜 굳이 확인하는가: 프롬프트를 한 덩어리로 다시 쓰다 보면 {@code [주제] {개념명}} 같은
     * 자리표시자를 시스템 쪽에 넣기 쉽다. 그러면 <b>치환되지 않은 문자열이 매 요청에 그대로
     * 실려</b> 모델이 주제 지시를 두 번(하나는 빈 채로) 받는다. 게다가 시스템/유저를 나눈
     * 이유인 프롬프트 캐시가 흐려진다 — 시스템 쪽이 고정이어야 앞부분이 재사용된다.
     */
    @Test
    @DisplayName("요청마다 바뀌는 값은 시스템 프롬프트에 없다 — 주제·회피 목록은 user 메시지의 몫이다")
    void systemPromptHasNoPerRequestPlaceholders() {
        assertThat(ClaudeDocumentGenerator.SYSTEM_PROMPT)
                .as("치환되지 않은 자리표시자가 그대로 실려 나가면 안 된다")
                .doesNotContain("{")
                .as("주제는 user 메시지가 넣는다")
                .doesNotContain("[주제]")
                .doesNotContain("[이미 문서가 있는 주제]");
    }
}
