package project.study.study_project.llm.client;

import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.llm.client.ClaudeCalls.Raw;
import project.study.study_project.llm.client.ReaderFinding.Kind;
import project.study.study_project.llm.client.ReaderFinding.Op;
import project.study.study_project.llm.client.ReaderFinding.Suggestion;
import project.study.study_project.llm.client.ReaderFinding.Type;
import project.study.study_project.llm.client.ReaderFinding.Weight;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 독자 점검 호출기 — 실제 API 대신 가짜 전송기로 요청 모양과 응답 거르기를 본다. */
class ClaudeDocumentReaderCheckTest {

    private static final String BEG = "# 이름 풀이\n\n## 무엇인가\n\n**리졸버**는 대신 묻는 서버다. 리졸버는 답을 저장한다.\n";
    private static final String ADV = "# 이름 풀이\n\n## 깊이\n\nTTL이 지나면 다시 묻는다. 다시 묻는다.\n";

    @AfterEach
    void reset() {
        ClaudeCalls.resetForTest();
    }

    private static ClaudeDocumentPolisherTest.Fake install(String json) {
        ClaudeDocumentPolisherTest.Fake fake = new ClaudeDocumentPolisherTest.Fake(new Raw(json, 1000, 500, 0, false, "", false));
        ClaudeCalls.useTransport(fake, ms -> { });
        return fake;
    }

    private static String findingJson(String quote, String op, String target, String text) {
        return """
                {"kind":"BLOCK","type":"UNDEFINED_TERM","weight":"PAUSE","section":"무엇인가",
                 "quote":%s,"earlier":"","reason":"리졸버가 무엇인지 모른다",
                 "suggestion":{"op":"%s","target":%s,"text":%s,"note":"본문에서 가져왔다"}}"""
                .formatted(TextNode.valueOf(quote), op, TextNode.valueOf(target), TextNode.valueOf(text));
    }

    @Test
    @DisplayName("본문에 없는 인용을 단 지적은 버린다")
    void dropsFindingWhoseQuoteIsNotInDocument() {
        install("{\"findings\":[" + findingJson("리졸버는 대신 묻는 서버다.", "REPLACE", "리졸버는 대신 묻는 서버다.", "리졸버는 이름을 대신 묻는 서버다.")
                + "," + findingJson("본문에 없는 문장이다.", "DELETE", "본문에 없는 문장이다.", "") + "]}");

        List<ReaderFinding> got = new ClaudeDocumentReaderCheck("claude-opus-5-5")
                .check("이름 풀이", BEG, DocumentEdition.BEGINNER, List.of(), null);

        assertThat(got).extracting(ReaderFinding::quote).containsExactly("리졸버는 대신 묻는 서버다.");
        assertThat(got.get(0).suggestion().op()).isEqualTo(Op.REPLACE);
    }

    @Test
    @DisplayName("본문에 두 번 나오는 target은 어디를 고칠지 몰라 사람 확인으로 바꾼다")
    void turnsAmbiguousTargetIntoNeedHuman() {
        List<ReaderFinding> got = ClaudeDocumentReaderCheck.sanitize(List.of(new ReaderFinding(
                Kind.REPEAT, Type.REPEAT, Weight.PAUSE, "깊이", "TTL이 지나면 다시 묻는다.", "", "되풀이",
                new Suggestion(Op.DELETE, "다시 묻는다.", "", "앞과 같다"))), ADV);

        Suggestion s = got.get(0).suggestion();
        assertThat(s.op()).isEqualTo(Op.NEED_HUMAN);
        assertThat(s.target()).isEmpty();
        assertThat(s.text()).isEmpty();
        assertThat(s.note()).isEqualTo("제안한 문장을 본문에서 찾지 못했다");
    }

    @Test
    @DisplayName("심화편 요청은 입문편 전문과 코스 앞 글 이름을 싣는다")
    void advancedRequestCarriesBeginnerAndEarlierTopics() {
        var fake = install("{\"findings\":[]}");

        new ClaudeDocumentReaderCheck("claude-opus-5-5")
                .check("이름 풀이", ADV, DocumentEdition.ADVANCED, List.of("포트 번호로 통신을 프로그램에 나눠 주는 방식"), BEG);

        String user = fake.sent.get(0).messages().get(0).content().toString();
        assertThat(user).contains("코스 앞 글").contains("포트 번호로 통신을 프로그램에 나눠 주는 방식")
                .contains("입문편 전문").contains("**리졸버**는 대신 묻는 서버다.").contains("TTL이 지나면 다시 묻는다.");
    }

    @Test
    @DisplayName("입문편 요청에는 입문편 전문 칸이 없고, 앞 글이 없으면 그 줄도 없다")
    void emptyEarlierTopicsOmitsTheLine() {
        var fake = install("{\"findings\":[]}");

        new ClaudeDocumentReaderCheck("claude-opus-5-5")
                .check("이름 풀이", BEG, DocumentEdition.BEGINNER, List.of(), null);

        String user = fake.sent.get(0).messages().get(0).content().toString();
        assertThat(user).doesNotContain("코스 앞 글").doesNotContain("입문편 전문");
    }

    @Test
    @DisplayName("비용 요약에 어느 편의 독자 점검인지 남긴다")
    void ledgerLabelNamesEdition() {
        install("{\"findings\":[]}");

        new ClaudeDocumentReaderCheck("claude-opus-5-5")
                .check("이름 풀이", ADV, DocumentEdition.ADVANCED, List.of(), BEG);

        assertThat(ClaudeCalls.ledger()).singleElement()
                .satisfies(u -> assertThat(u.label()).isEqualTo("독자 점검(" + DocumentEdition.ADVANCED.getDisplayName() + ")"));
    }

    @Test
    @DisplayName("프롬프트가 시험 뒤 정한 보완 다섯 가지를 담는다(2026-11-20 DNS 시험)")
    void promptCarriesTheFiveAdditions() {
        assertThat(ClaudeDocumentReaderCheck.systemPromptFor(DocumentEdition.BEGINNER))
                .contains("중학생")
                .as("1 코스 앞 글을 읽은 독자").contains("코스 앞 글")
                .as("2 되풀이 예외").contains("면접에서 이렇게 물어본다")
                .as("3 수치 불일치").contains("수치 안 맞음")
                .as("4 고친 문장 제안").contains("NEED_HUMAN")
                .as("5 비유 무대").contains("세운 비유");
        assertThat(ClaudeDocumentReaderCheck.systemPromptFor(DocumentEdition.ADVANCED))
                .contains("입문편을 방금 다 읽었다").contains("수치 안 맞음").contains("NEED_HUMAN");
    }
}
