package project.study.study_project.llm.client;

import com.anthropic.models.messages.MessageCreateParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.llm.client.ClaudeCalls.BatchState;
import project.study.study_project.llm.client.ClaudeCalls.Raw;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 문서 윤문 호출기 — 실제 API 대신 가짜 전송기로 요청 모양과 응답 처리를 본다. */
class ClaudeDocumentPolisherTest {

    private static final String BODY = "# 이름 풀이\n\n## 무엇인가\n\n리졸버는 대신 묻는 서버다.\n";

    @AfterEach
    void reset() {
        ClaudeCalls.resetForTest();
    }

    /** 바로 호출만 쓰는 가짜. 받은 요청을 남긴다. */
    static final class Fake implements ClaudeCalls.Transport {
        final List<MessageCreateParams> sent = new ArrayList<>();
        Raw reply;

        Fake(Raw reply) {
            this.reply = reply;
        }

        @Override
        public Raw send(MessageCreateParams params) {
            sent.add(params);
            return reply;
        }

        @Override
        public String submit(MessageCreateParams params) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BatchState poll(String batchId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Raw result(String batchId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void cancel(String batchId) {
        }
    }

    private static Raw reply(String contentMd, boolean truncated) {
        String json = "{\"contentMd\":" + com.fasterxml.jackson.databind.node.TextNode.valueOf(contentMd) + "}";
        return new Raw(json, 1000, 500, 0, false, "", truncated);
    }

    private static Fake install(Raw raw) {
        Fake fake = new Fake(raw);
        ClaudeCalls.useTransport(fake, ms -> { });
        return fake;
    }

    @Test
    @DisplayName("요청은 64,000토큰이고 사고를 켜지 않으며 윤문 프롬프트를 싣는다")
    void requestShape() {
        Fake fake = install(reply(BODY, false));

        new ClaudeDocumentPolisher("claude-opus-5-5").polish("이름 풀이", BODY, DocumentEdition.BEGINNER);

        MessageCreateParams sent = fake.sent.get(0);
        assertThat(sent.maxTokens()).isEqualTo(64_000L);
        assertThat(sent.thinking()).isEmpty();
        assertThat(sent.system().orElseThrow().toString()).contains("내용은 그대로, 문장만 고친다");
    }

    @Test
    @DisplayName("사용자 메시지에 제목과 본문 전문을 싣는다")
    void userMessageCarriesTitleAndBody() {
        Fake fake = install(reply(BODY, false));

        new ClaudeDocumentPolisher("claude-opus-5-5").polish("이름 풀이", BODY, DocumentEdition.BEGINNER);

        String user = fake.sent.get(0).messages().get(0).content().toString();
        assertThat(user).contains("이름 풀이").contains("리졸버는 대신 묻는 서버다.");
    }

    @Test
    @DisplayName("응답의 본문을 돌려준다")
    void returnsPolishedBody() {
        install(reply("# 이름 풀이\n\n고친 본문\n", false));

        assertThat(new ClaudeDocumentPolisher("claude-opus-5-5").polish("이름 풀이", BODY, DocumentEdition.BEGINNER))
                .isEqualTo("# 이름 풀이\n\n고친 본문\n");
    }

    @Test
    @DisplayName("MAX_TOKENS에서 잘린 응답은 실패로 던진다 — 뒷부분이 빠진 문서가 나가면 안 된다")
    void truncatedReplyThrows() {
        install(reply(BODY, true));

        assertThatThrownBy(() -> new ClaudeDocumentPolisher("claude-opus-5-5")
                .polish("이름 풀이", BODY, DocumentEdition.BEGINNER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("MAX_TOKENS");
    }

    @Test
    @DisplayName("빈 본문은 실패로 던진다")
    void blankReplyThrows() {
        install(reply("  ", false));

        assertThatThrownBy(() -> new ClaudeDocumentPolisher("claude-opus-5-5")
                .polish("이름 풀이", BODY, DocumentEdition.BEGINNER))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("비용 요약에 어느 편의 윤문인지 남긴다")
    void ledgerLabelNamesEdition() {
        install(reply(BODY, false));

        new ClaudeDocumentPolisher("claude-opus-5-5").polish("이름 풀이", BODY, DocumentEdition.ADVANCED);

        assertThat(ClaudeCalls.ledger()).singleElement()
                .satisfies(u -> assertThat(u.label()).isEqualTo("문서 윤문(" + DocumentEdition.ADVANCED.getDisplayName() + ")"));
    }

    @Test
    @DisplayName("윤문 프롬프트가 다섯 묶음 기준과 건드리지 않을 목록을 싣는다")
    void promptCarriesTheFiveBundles() {
        assertThat(ClaudeDocumentPolisher.SYSTEM_PROMPT)
                .contains("내용은 그대로, 문장만 고친다")
                .as("A 번역투·흐린 말").contains("\"~에 대해/~에 대한\"").contains("\"~하는 것\"").contains("\"~할 수 있다\" 남발")
                .as("전역 문체 기준에서 옮긴 세 줄(2026-11-19)")
                .contains("\"~하는 경우\", \"~로 인해\", \"~에 의해\"").contains("대명사 \"이는\"").contains("일상 외래어")
                .as("고친 문장이 \"~보자\", \"~하자\"로 끝났다(2026-11-19 HTTP 시험 윤문). 고친 문장은 평서문으로 끝낸다")
                .contains("청유형·명령형").contains("볼 곳을 평서문으로 직접 쓴다").doesNotContain("한다고 하자\"처럼")
                .as("알림말을 지워 비유가 실제 장면처럼 읽혔다(2026-11-19 TCP·HTTP 시험 윤문). 알림말은 지우지 않는다")
                .contains("비유 문장은 비유임이 드러나야 한다").doesNotContain("비유 문장을 바로 쓴다")
                .as("알림말을 문장 앞으로만 옮겨 \"~로 치면\"이 장면 앞에 붙었다(2026-11-19 쿠키 시험 윤문)")
                .contains("알림말은 문장 앞에 와도, 끝에 와도 된다").contains("원고에 이미 알림말이 있으면 바꾸지 않는다")
                .contains("\"~로 치면\"은 대상을 비유 속 자리에 짝지을 때만").contains("같은 알림말은 두 번까지")
                .contains("이미 비유를 연 문단").doesNotContain("앞쪽 알림말로 옮긴다")
                .as("되풀이를 허용한 절과 대사의 길이를 분명히 한다(2026-11-19 프롬프트 대조)")
                .contains("「핵심 요약」, 「용어 한눈에」, 「정리」, 「한 줄로 정리하면」").contains("큰따옴표 안의 대사는 끊지 않는다")
                .as("B 군더더기").contains("정리하면")
                .as("C 같은 틀 반복").contains("점을 보면 된다").contains("A가 아니라 B")
                .as("D 문장 길이").contains("한 문장은 60자까지다")
                .as("문단 쪼개기가 지나쳤다(2026-11-18 DNS 시험 윤문). 한 문장짜리 문단을 막는다")
                .contains("원고에 없던 한 문장 문단을 새로 만들지 마라")
                .as("원고의 한 문장 문단은 형식이 정한 자리일 수 있다(2026-11-19 프롬프트 대조)")
                .contains("원고에 있던 한 문장 문단은 그대로 둔다")
                .as("다리 문장이 지워지면 정의 줄이 꼬리표 목록에 붙는다")
                .contains("형식이 정한 안내 문장은 지우지 않는다")
                .as("예고 예시가 정의 목록 앞 안내 문장과 같은 꼴이라 그 문장을 지웠다(2026-11-19 멱등성 심화편)")
                .doesNotContain("용어 두 개만 먼저 정리한다").contains("용어 정의 목록 바로 앞의 안내 문장")
                .as("고치면서 말을 덧붙였다(2026-11-18 사용자 지적). 원문에 없던 말을 더하지 않는다")
                .contains("원문에 없던 말을 덧붙이지 마라")
                .as("E 줄바꿈").contains("굵은 글씨 한 줄 바로 아래에 본문을 붙여 쓰지 마라")
                .contains("한 문단에는 이야기 하나만 담는다").contains("줄표는 문장 끝에서만 쓴다")
                .as("앞뒤에 말을 붙이면 보존 대조에서 원본으로 돌아간다").contains("본문만 내놓는다");
    }
}
