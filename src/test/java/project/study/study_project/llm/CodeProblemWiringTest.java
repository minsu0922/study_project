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
import project.study.study_project.llm.client.CodeProblem;
import project.study.study_project.llm.client.GeneratedProblemItem;
import project.study.study_project.llm.client.ProblemReview;
import project.study.study_project.llm.dto.GeneratedBatchFile;
import project.study.study_project.llm.dto.LlmDraftResponse;
import project.study.study_project.llm.service.DraftImportService;
import project.study.study_project.llm.service.LlmProblemService;
import project.study.study_project.quiz.dto.AdminProblemDetail;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 코드 문제 파일의 곁칸({@code codeParts})이 <b>초안 → 검수 응답 → 승인한 문제</b>까지 닿는지 본다.
 *
 * <p>가장 큰 위험은 코드가 엉뚱한 문제에 붙는 것이다. 코드 문제는 질문 문장이 서로 같은 일이 흔해서
 * 질문으로 짝지으면 섞인다. 그래서 일부러 같은 질문 둘로 확인한다.
 *
 * <p>MySQL이 필요하다. 클래스 {@code @Transactional}로 롤백된다.
 */
@SpringBootTest(properties = {"ratelimit.enabled=false", "llm.import.enabled=false"})
@Transactional
class CodeProblemWiringTest {

    private static final String SAME_QUESTION = "다음 코드를 실행했을 때 출력되는 것은? (코드 문제 배선 테스트)";
    private static final String CODE_A = "public class Main {\n    public static void main(String[] args) {\n        System.out.println(1);\n    }\n}";
    private static final String CODE_B = "public class Main {\n    public static void main(String[] args) {\n        System.out.println(2);\n    }\n}";

    @Autowired
    private DraftImportService draftImportService;
    @Autowired
    private LlmProblemService llmProblemService;

    @TempDir
    Path tempDir;

    private static GeneratedProblemItem item(String answer) {
        return new GeneratedProblemItem(SAME_QUESTION, "", "3행에서 그 값을 그대로 출력한다. 다른 계산은 없다.",
                List.of(new GeneratedProblemItem.GeneratedChoice(answer, true),
                        new GeneratedProblemItem.GeneratedChoice(answer + "0", false, "자릿수를 잘못 읽은 오해다"),
                        new GeneratedProblemItem.GeneratedChoice("컴파일 오류", false, "main의 모양을 잘못 본 오해다"),
                        new GeneratedProblemItem.GeneratedChoice("아무것도 출력되지 않는다", false, "println을 못 본 오해다")),
                "", "코드 문제 배선 " + answer);
    }

    private Path writeFile(List<CodeProblem.Part> parts) throws Exception {
        GeneratedBatchFile batch = new GeneratedBatchFile(
                "테스트용", "2099-01-04", "2099-01-04T00:00:00Z",
                TestDomains.LANGUAGE_RUNTIME, Difficulty.BEGINNER, ProblemType.MULTIPLE_CHOICE, "test-model",
                null, List.of(item("1"), item("2")))
                .withCode("코드 문제 배선 테스트", parts);
        Path file = tempDir.resolve("2099-01-04-code-beg.json");
        new ObjectMapper().writeValue(file.toFile(), batch);
        return file;
    }

    private List<LlmDraftResponse> myDrafts() {
        return llmProblemService.getDrafts(null, TestDomains.LANGUAGE_RUNTIME, Difficulty.BEGINNER, null,
                        PageRequest.of(0, 200)).content().stream()
                .filter(d -> SAME_QUESTION.equals(d.question()))
                .sorted(Comparator.comparing(LlmDraftResponse::id))
                .toList();
    }

    @Test
    @DisplayName("질문이 같은 두 문제에도 코드가 제 문제에 붙고, 유형과 예상 출력이 검수 응답에 실린다")
    void codeReachesItsOwnDraft() throws Exception {
        assertThat(draftImportService.importFile(writeFile(List.of(
                // 일부러 번호 역순으로 적는다. 순서가 아니라 번호로 짝지어야 한다
                new CodeProblem.Part(1, CodeProblem.Kind.CODE_OUTPUT, "java", CODE_B, "2", 0, ""),
                new CodeProblem.Part(0, CodeProblem.Kind.CODE_OUTPUT, "java", CODE_A, "1", 0, ""))))).as("두 문제 모두 저장된다").isEqualTo(2);

        List<LlmDraftResponse> drafts = myDrafts();

        assertThat(drafts).hasSize(2);
        assertThat(drafts.get(0).code()).isEqualTo(CODE_A);
        assertThat(drafts.get(0).choices().get(0).text()).isEqualTo("1");
        assertThat(drafts.get(1).code()).isEqualTo(CODE_B);
        assertThat(drafts.get(1).expectedOutput()).isEqualTo("2");
        assertThat(drafts.get(1).codeKind()).isEqualTo("CODE_OUTPUT");
        assertThat(drafts.get(1).codeKindLabel()).isEqualTo("실행 결과");
    }

    @Test
    @DisplayName("승인하면 코드가 정식 문제로 넘어간다")
    void approvalCarriesCodeToProblem() throws Exception {
        draftImportService.importFile(writeFile(List.of(
                new CodeProblem.Part(0, CodeProblem.Kind.CODE_OUTPUT, "java", CODE_A, "1", 0, ""),
                new CodeProblem.Part(1, CodeProblem.Kind.CODE_OUTPUT, "java", CODE_B, "2", 0, ""))));

        AdminProblemDetail problem = llmProblemService.approve(myDrafts().get(1).id());

        assertThat(problem.code()).isEqualTo(CODE_B);
        assertThat(problem.codeLanguage()).isEqualTo("java");
    }

    @Test
    @DisplayName("한 줄 고치기는 바꿀 줄이 강조할 줄로 들어오고, 승인하면 '보기가 코드' 표시와 함께 문제로 넘어간다")
    void focusLineAndCodeChoicesFollowTheKind() throws Exception {
        draftImportService.importFile(writeFile(List.of(
                new CodeProblem.Part(0, CodeProblem.Kind.CODE_FIX, "java", CODE_A, "2", 3, ""),
                new CodeProblem.Part(1, CodeProblem.Kind.CODE_ERROR, "java", CODE_B, "", 3, "NullPointerException"))));

        List<LlmDraftResponse> drafts = myDrafts();
        assertThat(drafts.get(0).codeFocusLine()).isEqualTo(3);
        assertThat(drafts.get(0).codeChoices()).isTrue();
        assertThat(drafts.get(1).codeFocusLine()).as("예외가 나는 줄은 정답이다").isNull();
        assertThat(drafts.get(1).codeChoices()).isFalse();

        AdminProblemDetail problem = llmProblemService.approve(drafts.get(0).id());
        assertThat(problem.codeFocusLine()).isEqualTo(3);
        assertThat(problem.codeChoices()).isTrue();
    }

    @Test
    @DisplayName("실행 대조 지적은 질문이 같아도 번호로 제 초안에 붙고, 지적 없는 초안은 '대조함, 지적 없음'이 된다")
    void factFindingsReachTheirOwnDraft() throws Exception {
        Path file = writeFile(List.of(
                new CodeProblem.Part(0, CodeProblem.Kind.CODE_OUTPUT, "java", CODE_A, "1", 0, ""),
                new CodeProblem.Part(1, CodeProblem.Kind.CODE_OUTPUT, "java", CODE_B, "2", 0, "")));
        ObjectMapper mapper = new ObjectMapper();
        GeneratedBatchFile batch = mapper.readValue(file.toFile(), GeneratedBatchFile.class);
        mapper.writeValue(file.toFile(), batch.withReviewFindings(List.of(new ProblemReview.Finding(1,
                ProblemReview.FindingType.EXPLANATION_MISMATCH, "해설이 코드에 없는 줄을 가리킵니다", "9행 — 코드는 5줄입니다"))));

        draftImportService.importFile(file);

        List<LlmDraftResponse> drafts = myDrafts();
        assertThat(drafts.get(0).aiFindings()).as("대조했고 지적 없음").isEmpty();
        assertThat(drafts.get(1).aiFindings()).singleElement().satisfies(f -> {
            assertThat(f.type()).isEqualTo("EXPLANATION_MISMATCH");
            assertThat(f.grade()).isEqualTo("DEFECT");
        });
    }

    @Test
    @DisplayName("코드가 딸리지 않은 코드 문제는 들여오지 않는다 — 코드 없이는 풀 수 없다")
    void dropsProblemWithoutCode() throws Exception {
        draftImportService.importFile(writeFile(List.of(
                new CodeProblem.Part(1, CodeProblem.Kind.CODE_OUTPUT, "java", CODE_B, "2", 0, ""))));

        assertThat(myDrafts()).singleElement().satisfies(d -> assertThat(d.code()).isEqualTo(CODE_B));
    }
}
