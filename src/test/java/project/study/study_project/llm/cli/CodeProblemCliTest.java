package project.study.study_project.llm.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import project.study.study_project.TestDomains;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.llm.client.ClaudeCodeProblemReviewer;
import project.study.study_project.llm.client.CodeProblem;
import project.study.study_project.llm.client.CodeProblem.Choice;
import project.study.study_project.llm.client.CodeProblem.Item;
import project.study.study_project.llm.client.CodeProblem.Kind;
import project.study.study_project.llm.dto.GeneratedBatchFile;
import project.study.study_project.llm.dto.TopicQueueFile;
import project.study.study_project.llm.client.ProblemReview.FindingType;
import project.study.study_project.llm.support.CodeFactChecker;
import project.study.study_project.llm.support.CodeRunner;
import project.study.study_project.llm.support.CodeVerifier;
import project.study.study_project.llm.support.TopicQueue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 코드 문제 배치의 검증 단계 — 후보를 <b>실제로 실행해</b> 통과한 것만 파일로 남기는지 본다.
 * Claude는 부르지 않는다. 후보 파일을 손으로 만들어 넣는다.
 */
class CodeProblemCliTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 18);
    private static final String TOPIC = "==과 equals, Integer 캐시";

    @TempDir
    Path outDir;
    @TempDir
    Path workDir;

    private final CodeVerifier verifier = new CodeVerifier(new CodeRunner()::run);
    private final CodeFactChecker checker = new CodeFactChecker(new CodeRunner()::run);

    private static String program(String statement) {
        return "public class Main {\n    public static void main(String[] args) {\n        " + statement + "\n    }\n}";
    }

    private static Item output(Difficulty difficulty, String title, String statement, String claimed) {
        return new Item(Kind.CODE_OUTPUT, difficulty, title, "다음 코드를 실행했을 때 출력되는 것은?",
                program(statement), claimed, 0, "",
                List.of(new Choice(claimed, true, ""), new Choice(claimed + "!", false, "오해 하나"),
                        new Choice("컴파일 오류", false, "오해 둘"), new Choice("출력 없음", false, "오해 셋")),
                "3행이 그 값을 그대로 출력한다.");
    }

    private void writeCandidates(Item... items) throws Exception {
        Files.writeString(workDir.resolve(CodeProblemCli.CANDIDATES_FILE), CodeProblemCli.MAPPER.writeValueAsString(
                new CodeProblemCli.Candidates(DATE.toString(), TOPIC, 0, TestDomains.LANGUAGE_RUNTIME, "test-model",
                        List.of(items))));
    }

    private void writeTopics() throws Exception {
        Files.writeString(outDir.resolve(TopicQueue.CODE_FILE_NAME), CodeProblemCli.MAPPER.writeValueAsString(
                new TopicQueueFile("테스트", List.of(
                        new TopicQueueFile.Entry(11L, "LANGUAGE_RUNTIME", TOPIC, null, null, null)))));
    }

    @Test
    @DisplayName("실행해서 맞은 문제만 난이도별 파일로 남기고, 틀린 정답과 금지 소재는 버린다")
    void keepsOnlyProblemsThatRunAsClaimed() throws Exception {
        writeTopics();
        writeCandidates(
                output(Difficulty.BEGINNER, "나눗셈", "System.out.println(7 / 2);", "3"),
                output(Difficulty.INTERMEDIATE, "틀린 계산", "System.out.println(7 / 2);", "3.5"),
                output(Difficulty.ADVANCED, "환경 읽기", "System.out.println(System.getenv(\"X\"));", "null"));

        CodeProblemCli.verify(outDir, workDir, verifier, checker);

        Path saved = outDir.resolve("2026-10-18-code-beg.json");
        assertThat(saved).exists();
        assertThat(outDir.resolve("2026-10-18-code-int.json")).as("모델이 계산을 틀린 문제").doesNotExist();
        assertThat(outDir.resolve("2026-10-18-code-adv.json")).as("금지 소재").doesNotExist();

        GeneratedBatchFile file = CodeProblemCli.MAPPER.readValue(saved.toFile(), GeneratedBatchFile.class);
        assertThat(file.codeTopic()).isEqualTo(TOPIC);
        assertThat(file.difficulty()).isEqualTo(Difficulty.BEGINNER);
        assertThat(file.documentSlug()).isNull();
        assertThat(file.problems()).singleElement().satisfies(p -> assertThat(p.title()).isEqualTo("나눗셈"));
        assertThat(file.codeParts()).singleElement().satisfies(part -> {
            assertThat(part.problemIndex()).isZero();
            assertThat(part.expectedOutput()).isEqualTo("3");
            assertThat(part.code()).contains("7 / 2");
        });
        assertThat(file.reviewFindings()).as("대조했고 어긋난 곳이 없다 — null이면 '대조 안 함'으로 읽힌다").isEmpty();
        assertThat(workDir.resolve(CodeProblemCli.CANDIDATES_FILE)).as("후보는 한 번 쓰고 지운다").doesNotExist();
    }

    @Test
    @DisplayName("정답은 맞지만 해설이 없는 줄을 가리키면, 문제는 남기고 파일에 지적을 싣는다")
    void keepsProblemAndRecordsFactFinding() throws Exception {
        writeTopics();
        Item item = output(Difficulty.BEGINNER, "나눗셈", "System.out.println(7 / 2);", "3");
        writeCandidates(new Item(item.kind(), item.difficulty(), item.title(), item.question(), item.code(),
                item.expectedOutput(), 0, "", item.choices(), "9행이 그 값을 출력한다."));

        CodeProblemCli.verify(outDir, workDir, verifier, checker);

        GeneratedBatchFile file = CodeProblemCli.MAPPER.readValue(
                outDir.resolve("2026-10-18-code-beg.json").toFile(), GeneratedBatchFile.class);
        assertThat(file.problems()).hasSize(1);
        assertThat(file.reviewFindings()).singleElement().satisfies(f -> {
            assertThat(f.problemIndex()).isZero();
            assertThat(f.type()).isEqualTo(FindingType.EXPLANATION_MISMATCH);
            assertThat(f.detail()).contains("9행").contains("5줄");
        });
    }

    @Test
    @DisplayName("검증이 실행 결과를 남기면, AI 검수는 그것을 받아 지적을 제 파일·제 번호 뒤에 덧붙인다")
    void reviewAppendsFindingsToTheRightFile() throws Exception {
        writeTopics();
        Item badExplanation = output(Difficulty.INTERMEDIATE, "곱셈", "System.out.println(2 * 3);", "6");
        writeCandidates(
                output(Difficulty.BEGINNER, "나눗셈", "System.out.println(7 / 2);", "3"),
                output(Difficulty.BEGINNER, "나머지", "System.out.println(7 % 2);", "1"),
                new Item(badExplanation.kind(), badExplanation.difficulty(), badExplanation.title(), badExplanation.question(),
                        badExplanation.code(), "6", 0, "", badExplanation.choices(), "8행이 곱을 출력한다."));
        CodeProblemCli.verify(outDir, workDir, verifier, checker);

        CodeProblemCli.RunNotes runs = CodeProblemCli.MAPPER.readValue(
                workDir.resolve(CodeProblemCli.RUNS_FILE).toFile(), CodeProblemCli.RunNotes.class);
        assertThat(runs.notes()).hasSize(3);
        assertThat(runs.notes().get(1).facts()).isEqualTo("- 이 코드: 출력 「1」");

        String[] prompt = {null};
        CodeProblemCli.review(outDir, workDir, model -> new ClaudeCodeProblemReviewer(p -> {
            prompt[0] = p;
            // 요청 글의 문제 2(초급 파일의 1번)와 문제 3(중급 파일의 0번)에 답한다
            return new ClaudeCodeProblemReviewer.JudgementBatch(List.of(
                    new ClaudeCodeProblemReviewer.Judgement(2, "", List.of(), "", "주제와 상관없다", 0, "", "", "", ""),
                    new ClaudeCodeProblemReviewer.Judgement(3, "해설의 8행은 없다", List.of(), "", "", 0, "", "", "", "")));
        }));

        assertThat(prompt[0]).contains("- 이 코드: 출력 「1」");
        GeneratedBatchFile beg = CodeProblemCli.MAPPER.readValue(
                outDir.resolve("2026-10-18-code-beg.json").toFile(), GeneratedBatchFile.class);
        assertThat(beg.reviewFindings()).singleElement().satisfies(f -> {
            assertThat(f.problemIndex()).isEqualTo(1);
            assertThat(f.type()).isEqualTo(FindingType.OFF_TOPIC);
        });
        GeneratedBatchFile mid = CodeProblemCli.MAPPER.readValue(
                outDir.resolve("2026-10-18-code-int.json").toFile(), GeneratedBatchFile.class);
        assertThat(mid.reviewFindings()).as("기계 대조 지적 뒤에 AI 지적이 붙는다")
                .extracting(f -> f.problemIndex() + ":" + f.message())
                .containsExactly("0:해설이 코드에 없는 줄을 가리킵니다", "0:해설이 실제 실행과 어긋납니다");
        assertThat(workDir.resolve(CodeProblemCli.RUNS_FILE)).as("한 번 쓰고 지운다").doesNotExist();
    }

    @Test
    @DisplayName("AI 검수가 실패해도 문제 파일은 그대로 둔다")
    void reviewFailureLeavesFilesAlone() throws Exception {
        writeTopics();
        writeCandidates(output(Difficulty.BEGINNER, "나눗셈", "System.out.println(7 / 2);", "3"));
        CodeProblemCli.verify(outDir, workDir, verifier, checker);
        String before = Files.readString(outDir.resolve("2026-10-18-code-beg.json"));

        CodeProblemCli.review(outDir, workDir, model -> new ClaudeCodeProblemReviewer(p -> {
            throw new IllegalStateException("API 오류");
        }));

        assertThat(Files.readString(outDir.resolve("2026-10-18-code-beg.json"))).isEqualTo(before);
    }

    @Test
    @DisplayName("검증 단계가 남긴 실행 결과가 없으면 AI 검수는 부르지 않는다")
    void reviewSkipsWithoutRunNotes() throws Exception {
        CodeProblemCli.review(outDir, workDir, model -> {
            throw new AssertionError("검수기를 만들면 안 된다");
        });
    }

    @Test
    @DisplayName("통과한 문제가 있으면 주제에 사용 표시를 찍고, 다음 생성은 그 코드를 겹침 방지로 읽는다")
    void marksTopicUsedAndFindsPreviousCodes() throws Exception {
        writeTopics();
        writeCandidates(output(Difficulty.BEGINNER, "나눗셈", "System.out.println(7 / 2);", "3"));

        CodeProblemCli.verify(outDir, workDir, verifier, checker);

        TopicQueueFile topics = CodeProblemCli.MAPPER.readValue(
                outDir.resolve(TopicQueue.CODE_FILE_NAME).toFile(), TopicQueueFile.class);
        assertThat(topics.topics().get(0).lastUsedAt()).isEqualTo("2026-10-18");
        assertThat(topics.topics().get(0).usedCount()).isEqualTo(1);
        assertThat(CodeProblemCli.previousCodes(outDir, TOPIC)).singleElement()
                .satisfies(code -> assertThat(code).contains("7 / 2"));
        assertThat(CodeProblemCli.previousCodes(outDir, "다른 주제")).isEmpty();
        assertThat(CodeProblemCli.alreadyGenerated(outDir, DATE)).isTrue();
        assertThat(CodeProblemCli.alreadyGenerated(outDir, DATE.plusDays(7))).isFalse();
    }

    @Test
    @DisplayName("하나도 통과하지 못하면 파일도 사용 표시도 남기지 않는다 — 다음 주에 같은 주제를 다시 시도한다")
    void leavesTopicUnusedWhenNothingPasses() throws Exception {
        writeTopics();
        writeCandidates(output(Difficulty.BEGINNER, "틀린 계산", "System.out.println(7 / 2);", "3.5"));

        CodeProblemCli.verify(outDir, workDir, verifier, checker);

        assertThat(CodeProblemCli.alreadyGenerated(outDir, DATE)).isFalse();
        TopicQueueFile topics = CodeProblemCli.MAPPER.readValue(
                outDir.resolve(TopicQueue.CODE_FILE_NAME).toFile(), TopicQueueFile.class);
        assertThat(topics.topics().get(0).lastUsedAt()).isNull();
    }

    @Test
    @DisplayName("금지 소재가 든 코드는 실행 검증을 부르지도 않는다")
    void bannedCodeIsNeverRun() {
        int[] runs = {0};

        String defect = CodeProblemCli.defectOf(
                output(Difficulty.BEGINNER, "환경 읽기", "System.out.println(System.getenv(\"X\"));", "null"),
                item -> {
                    runs[0]++;
                    return null;
                });

        assertThat(defect).contains("금지 소재");
        assertThat(runs[0]).isZero();
    }

    @Test
    @DisplayName("공백이 든 주제는 환경변수로 받는다 — 인자는 공백으로 쪼개져 들어온다")
    void readsTopicFromEnvironment() {
        java.util.Map<String, String> env = java.util.Map.of("IN_TOPIC", "  ==과 equals, Integer 캐시 ");

        assertThat(CodeProblemCli.givenTopic(java.util.Map.of("topic-env", "IN_TOPIC"), env::get))
                .isEqualTo("==과 equals, Integer 캐시");
        assertThat(CodeProblemCli.givenTopic(java.util.Map.of("topic", "초기화순서"), env::get)).isEqualTo("초기화순서");
        assertThat(CodeProblemCli.givenTopic(java.util.Map.of(), env::get)).isNull();
    }

    @Test
    @DisplayName("후보가 없으면 조용히 끝난다 — 생성 단계가 건너뛴 날이다")
    void doesNothingWithoutCandidates() throws Exception {
        CodeProblemCli.verify(outDir, workDir, verifier, checker);

        assertThat(Files.list(outDir)).isEmpty();
    }
}
