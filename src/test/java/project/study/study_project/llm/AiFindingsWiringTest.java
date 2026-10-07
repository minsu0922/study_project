package project.study.study_project.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.TestDomains;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.llm.client.GeneratedProblemItem;
import project.study.study_project.llm.client.ProblemReview;
import project.study.study_project.llm.dto.GeneratedBatchFile;
import project.study.study_project.llm.dto.LlmDraftResponse;
import project.study.study_project.llm.service.DraftImportService;
import project.study.study_project.llm.service.LlmProblemService;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 검수 지적이 <b>배치 파일 → 초안 테이블 → 검수 응답</b>까지 닿는지 본다(docs/22 §6 2단계).
 *
 * <p>{@code SourceQuoteWiringTest}와 같은 종류다 — 규칙이 아니라 배선을 본다. 새 열(V29)이
 * 실제 MySQL에 쓰이고 읽히는지는 가짜 저장소로는 알 수 없다.
 *
 * <p>MySQL이 필요하다. 클래스 {@code @Transactional}로 롤백된다. 흡수를 끄는 이유도
 * {@code SourceQuoteWiringTest}와 같다.
 */
@SpringBootTest(properties = {"ratelimit.enabled=false", "llm.import.enabled=false"})
@Transactional
class AiFindingsWiringTest {

    /** 이 테스트의 초안만 걸러 내는 열쇠. 검수 목록은 근거 문서로 좁힐 수 있다. */
    private static final String SLUG = "ai-findings-wiring-test";

    @Autowired
    private DraftImportService draftImportService;
    @Autowired
    private LlmProblemService llmProblemService;

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("파일의 검수 지적이 검수 응답에 한글 이름과 함께 실리고, 지적 없는 문제는 빈 목록이다")
    void findingsInFileReachReviewResponse() throws Exception {
        Path file = writeFile(List.of(
                new ProblemReview.Finding(1, ProblemReview.FindingType.CHOICE_CUE_LEAK, "보기만 봐도 고른다",
                        "4번만 길다\n나머지는 짧다"),
                // 근거 칸이 생기기 전의 지적 — 결론만 있어도 그대로 실려야 한다
                new ProblemReview.Finding(1, ProblemReview.FindingType.NO_SUPPORT, "근거 없음")));

        draftImportService.importFile(file);

        List<LlmDraftResponse> drafts = reviewList();
        assertThat(drafts).extracting(LlmDraftResponse::question).containsExactly("지적 없는 문제", "지적 있는 문제");
        assertThat(drafts.get(0).aiFindings()).isEmpty();
        assertThat(drafts.get(1).aiFindings()).containsExactly(
                new LlmDraftResponse.AiFinding("CHOICE_CUE_LEAK", "보기 단서 누설", "보기만 봐도 고른다",
                        List.of("4번만 길다", "나머지는 짧다")),
                new LlmDraftResponse.AiFinding("NO_SUPPORT", "문서에 근거 없음", "근거 없음", List.of()));
    }

    @Test
    @DisplayName("검수를 안 돌린 파일의 초안은 응답의 지적이 null이다 — 빈 목록과 달라야 한다")
    void unreviewedFileYieldsNull() throws Exception {
        draftImportService.importFile(writeFile(null));

        assertThat(reviewList()).hasSize(2)
                .allSatisfy(d -> assertThat(d.aiFindings()).isNull());
    }

    private List<LlmDraftResponse> reviewList() {
        return llmProblemService.getDrafts(null, null, null, SLUG, PageRequest.of(0, 20)).content();
    }

    private Path writeFile(List<ProblemReview.Finding> findings) throws Exception {
        GeneratedBatchFile batch = new GeneratedBatchFile(
                "테스트용", "2099-01-01", "2099-01-01T00:00:00Z",
                TestDomains.NETWORK, Difficulty.BEGINNER, ProblemType.MULTIPLE_CHOICE, "test-model",
                SLUG, List.of(item("지적 없는 문제"), item("지적 있는 문제")))
                .withReviewFindings(findings);
        Path file = tempDir.resolve("ai-findings-wiring-test.json");
        new ObjectMapper().writeValue(file.toFile(), batch);
        return file;
    }

    private GeneratedProblemItem item(String question) {
        return new GeneratedProblemItem(question, "", "해설: 왜 정답인지와 오답의 오해를 설명한다.",
                List.of(new GeneratedProblemItem.GeneratedChoice("정답 보기", true),
                        new GeneratedProblemItem.GeneratedChoice("오답 보기 1", false),
                        new GeneratedProblemItem.GeneratedChoice("오답 보기 2", false),
                        new GeneratedProblemItem.GeneratedChoice("오답 보기 3", false)));
    }
}
