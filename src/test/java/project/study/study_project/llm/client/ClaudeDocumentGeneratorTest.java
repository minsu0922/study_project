package project.study.study_project.llm.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.global.common.Domain;

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
    @DisplayName("주제를 지정하면 그 주제로 쓰라는 지시가 프롬프트에 실린다")
    void includesGivenTopic() {
        String prompt = generator.buildPrompt(Domain.NETWORK, "TCP 혼잡 제어", List.of(), List.of());

        assertThat(prompt).contains("TCP 혼잡 제어");
        assertThat(prompt).as("지정 주제일 때는 '고르라'가 아니라 '이 주제로 쓰라'여야 한다")
                .contains("이 주제로 문서를 써라");
    }

    @Test
    @DisplayName("주제를 비우면 모델이 직접 고르도록 지시한다")
    void asksModelToPickWhenTopicOmitted() {
        String prompt = generator.buildPrompt(Domain.NETWORK, null, List.of(), List.of());

        assertThat(prompt).contains("주제를 하나 골라");
        assertThat(prompt).doesNotContain("이 주제로 문서를 써라");
    }

    @Test
    @DisplayName("공백만 있는 주제는 지정하지 않은 것으로 본다 — 워크플로 입력이 비면 그렇게 온다")
    void treatsBlankTopicAsUnspecified() {
        String prompt = generator.buildPrompt(Domain.NETWORK, "   ", List.of(), List.of());

        assertThat(prompt).contains("주제를 하나 골라");
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
                .contains("4,000~5,500자");
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
                .contains("서로 다른 항목이 5개 이상이어야 한다");
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
                .contains("정의가 이 문서의 첫 문장이어야 한다")
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
                .contains("- **용어(영문)** — 한 문장 정의.")
                .as("왜 하이픈이어야 하는지를 함께 적어야 모델이 규칙을 지킨다")
                .contains("줄만 바꾸면 마크다운이 한 문단으로 붙여 버린다");
    }
}
