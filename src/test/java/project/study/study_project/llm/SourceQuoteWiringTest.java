package project.study.study_project.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.llm.client.GeneratedProblemItem;
import project.study.study_project.llm.client.SourceDocument;
import project.study.study_project.llm.domain.GeneratedProblemDraft;
import project.study.study_project.llm.dto.LlmDraftResponse;
import project.study.study_project.llm.service.LlmProblemService;
import project.study.study_project.llm.support.DraftCheck;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>근거 인용 대조가 검수 화면까지 닿는지</b>를 본다 — 규칙이 아니라 배선을 보는 테스트다.
 *
 * <h2>왜 따로 필요한가</h2>
 *
 * <p>{@code SourceQuoteRule}에는 이미 아홉 갈래 단위 테스트가 있다. 그런데 그 규칙을 부르는 쪽이
 * 오랫동안 배치 CLI와 평가 CLI <b>둘뿐</b>이었다. 관리 화면에서 문서를 올려 뽑은 문제는
 * "이 문제가 정말 그 문서에서 나왔는가"를 아무도 묻지 않았고, 그 사실은 코드를 grep 하기 전에는
 * 드러나지 않았다 — 화면도 멀쩡하고 테스트도 전부 초록불이었다.
 *
 * <p>이 저장소가 같은 모양으로 두 번 데인 자리다(2026-08-13 스케줄링, 2026-09-02 앵커).
 * <b>규칙이 옳아도 배선이 없으면 없는 규칙이다.</b> 그래서 규칙의 정확도가 아니라
 * "저장 → 조회 → 응답"의 사슬이 이어져 있는지만 검사한다.
 *
 * <p>MySQL이 필요하다. 클래스 {@code @Transactional}로 롤백된다.
 *
 * <h2>{@code llm.import.enabled=false}인 이유 (2026-09-03, CI에서만 깨졌다)</h2>
 *
 * <p>{@code DraftImportRunner}는 앱이 뜰 때 {@code generated/*.json}을 초안으로 흡수한다.
 * 이미 가져온 파일은 {@code imported_draft_file}의 도장을 보고 건너뛰므로 <b>로컬에서는
 * 아무것도 안 들어온다</b> — 도장 32개가 이미 찍혀 있다. 그런데 CI는 DB가 빈 채로 시작해
 * 도장도 없으니 <b>파일 32개, 초안 115건이 전부 PENDING으로 들어온 뒤</b> 테스트가 돈다.
 *
 * <p>그 상태에서 아래 {@code messagesOf}가 첫 쪽 50건만 뒤지면 방금 만든 초안을 못 찾는다.
 * 목록이 <b>오래된 순</b>이라 방금 만든 것은 언제나 마지막 쪽에 있기 때문이다.
 * 로컬에서 통과하고 CI에서만 깨지는 실패였고, 원인은 코드가 아니라 <b>DB에 뭐가 쌓여 있느냐</b>였다.
 *
 * <p>두 겹으로 막는다. ① 흡수를 꺼서 이 테스트가 만든 것만 DB에 있게 하고,
 * ② 그래도 쪽을 넘겨 가며 찾는다({@code messagesOf}) — 로컬 개발 DB에는 이 스위치와 무관하게
 * 옛 초안이 쌓여 있어서, 흡수를 끄는 것만으로는 같은 실패가 언제든 돌아온다.
 * ①만 있으면 "지금 로컬에서는 통과한다"에 기대는 셈이다.
 *
 * <p>스위치 이름과 이유는 {@code AdminLlmBulkApproveIntegrationTest}에 이미 있었다.
 * 그 규약을 따르지 않은 것이 이번 실패의 절반이다.
 */
@SpringBootTest(properties = {"ratelimit.enabled=false", "llm.import.enabled=false"})
@Transactional
class SourceQuoteWiringTest {

    /** 초안을 찾으며 넘겨 볼 최대 쪽수 — 무한 루프 대신 분명한 실패로 끝내려는 상한. */
    private static final int MAX_PAGES = 50;

    /** 한 쪽 크기. 쿼리 수를 줄이려고 크게 잡는다(검수 화면 기본값과 맞출 이유가 없다). */
    private static final int PAGE_SIZE = 200;

    private static final String DOC_BODY = """
            ## 무엇인가

            커넥션 풀은 미리 열어 둔 연결을 빌려주는 장치다.

            ## 언제 깨지는가

            풀이 마르면 요청은 대기하다 타임아웃으로 실패한다.
            """;

    @Autowired
    private LlmProblemService llmProblemService;

    /**
     * 문서 밖에서 끌어온 인용은 <b>저장될 때</b> 걸려야 하고, 그 판정이 검수 화면이 읽는
     * 응답까지 살아 있어야 한다. 둘 중 하나만 되면 검수자는 아무것도 못 본다.
     */
    @Test
    @DisplayName("문서에 없는 인용을 달면 초안에 경고가 찍히고 검수 응답에도 실린다")
    void quoteNotFoundIsStoredAndSurfaced() {
        GeneratedProblemItem item = itemWithQuote("풀이 마르면 서버가 재시작된다");   // 문서에 없는 문장

        GeneratedProblemDraft saved = saveWithDocument(item);

        assertThat(saved.getSourceQuoteCheck())
                .as("생성 시점에 찍혀야 한다 — 검수 시점에는 인용도 문서도 없다")
                .isNotNull()
                .contains("찾지 못함");

        assertThat(messagesOf(saved))
                .as("검수 화면이 읽는 checks에 섞여 나와야 한다")
                .anyMatch(m -> m.contains("찾지 못함"));
    }

    @Test
    @DisplayName("문서에 실재하는 인용이면 경고가 없다 — 멀쩡한 문제에 헛울리지 않는다")
    void quoteFoundLeavesNoWarning() {
        GeneratedProblemItem item = itemWithQuote("커넥션 풀은 미리 열어 둔 연결을 빌려주는 장치다.");

        GeneratedProblemDraft saved = saveWithDocument(item);

        assertThat(saved.getSourceQuoteCheck()).isNull();
        assertThat(messagesOf(saved)).noneMatch(m -> m.contains("근거 인용"));
    }

    /**
     * 근거 문서 없이 만든 문제(칸 자동 선택 경로)에는 대조할 원본이 없다. 여기서 경고를 내면
     * 그 경로의 <b>모든</b> 문제에 헛울리고, 그러면 검수자가 경고 전체를 안 보게 된다.
     */
    @Test
    @DisplayName("근거 문서 없이 만든 문제는 대조하지 않는다 — 원본이 없는데 울리면 잡음이다")
    void noDocumentMeansNoQuoteCheck() {
        GeneratedProblemItem item = itemWithQuote("");   // 문서가 없으니 인용도 비어 온다

        GeneratedProblemDraft saved = llmProblemService.saveDrafts(
                Domain.BACKEND_FRAMEWORK, Difficulty.BEGINNER, ProblemType.OX,
                List.of(item), "test-model", null).get(0);

        assertThat(saved.getSourceQuoteCheck()).isNull();
    }

    /* ── 재료 ─────────────────────────────────────────────── */

    private GeneratedProblemDraft saveWithDocument(GeneratedProblemItem item) {
        SourceDocument doc = new SourceDocument(
                "connection-pool", "커넥션 풀", DOC_BODY, SourceDocument.Kind.UPLOADED);
        return llmProblemService.saveDrafts(
                Domain.BACKEND_FRAMEWORK, Difficulty.BEGINNER, ProblemType.OX,
                List.of(item), "test-model", null, doc).get(0);
    }

    /**
     * 검수 화면이 실제로 받는 경고 문장들 — 목록 API를 거쳐 꺼낸다(배선을 보는 것이 목적이다).
     *
     * <p><b>쪽을 넘겨 가며 찾는다.</b> 목록이 오래된 순({@code order by createdAt asc})이라
     * 방금 만든 초안은 언제나 <b>마지막 쪽</b>에 있다. 첫 쪽만 보면 "DB에 초안이 몇 건 쌓여
     * 있느냐"에 따라 통과했다 깨졌다 하는데, 그건 이 테스트가 재려는 것과 아무 상관이 없다.
     * 실제로 그 이유로 CI에서만 깨졌다(클래스 주석).
     */
    private List<String> messagesOf(GeneratedProblemDraft saved) {
        for (int page = 0; page < MAX_PAGES; page++) {
            var result = llmProblemService.getDrafts(null, null, null, null,
                    org.springframework.data.domain.PageRequest.of(page, PAGE_SIZE));
            Optional<LlmDraftResponse> hit = result.content().stream()
                    .filter(d -> d.id().equals(saved.getId()))
                    .findFirst();
            if (hit.isPresent()) {
                return hit.get().checks().stream().map(DraftCheck::message).toList();
            }
            if (!result.hasNext()) {
                break;
            }
        }
        throw new AssertionError("방금 저장한 초안이 검수 목록에 없다");
    }

    /** OX 한 건. 규약을 통과해야 저장되므로(defectOf) answer는 O/X여야 한다. */
    private GeneratedProblemItem itemWithQuote(String quote) {
        return new GeneratedProblemItem(
                "커넥션 풀은 미리 열어 둔 연결을 빌려주는 장치다.",   // question
                "O",                                              // answer
                "풀은 연결을 미리 열어 두고 빌려준다. ".repeat(20),   // explanation
                List.of(),                                        // choices (OX는 빈 배열)
                quote,                                            // sourceQuote
                "커넥션 풀의 역할",                                 // title
                null);                                            // questionKind
    }
}
