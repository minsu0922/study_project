package project.study.study_project.llm.cli;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.llm.client.ClaudeProblemReviewer;
import project.study.study_project.llm.client.GeneratedDocumentItem;
import project.study.study_project.llm.client.GeneratedProblemItem;
import project.study.study_project.llm.client.GeneratedProblemItem.GeneratedChoice;
import project.study.study_project.llm.client.ProblemReview.Finding;
import project.study.study_project.llm.client.ProblemReview.FindingType;
import project.study.study_project.llm.client.SourceDocument;
import project.study.study_project.llm.dto.GeneratedBatchFile;
import project.study.study_project.llm.dto.GeneratedDocumentFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 문제 검수 적발률 측정 — docs/22 §4. 승인된 문제에 오류를 심고 {@link ClaudeProblemReviewer}로 채점한다.
 * 실제 API를 부르므로 {@code evalProblemReview} 태스크로만 돈다.
 *
 * <pre>
 *   --samples=eval-samples/problem-review.json   표본 정의
 *   --model=claude-opus-5                        검수 모델. 생략하면 생성 모델
 *   --label=before                               보고서 파일 이름 꼬리표
 *   --out=eval                                   보고서 디렉터리
 * </pre>
 */
public final class ProblemReviewEvalCli {

    private static final String DEFAULT_SAMPLES = "eval-samples/problem-review.json";
    private static final String DEFAULT_OUT_DIR = "eval";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ProblemReviewEvalCli() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = DraftGeneratorCli.parseArgs(args);
        String model = opts.containsKey("model")
                ? opts.get("model")
                : (String) DraftGeneratorCli.readGenerationConfig().getOrDefault("model", "claude-opus-5");
        SampleFile file = MAPPER.readValue(
                Path.of(opts.getOrDefault("samples", DEFAULT_SAMPLES)).toFile(), SampleFile.class);

        // 요금을 쓰기 전에 전부 준비해 본다. 문제 파일이 바뀌어 변형이 안 맞으면 여기서 멈춘다
        List<Prepared> prepared = new ArrayList<>();
        for (Group g : file.groups()) {
            prepared.add(prepare(g));
        }

        System.out.printf("문제 검수 적발률 측정: 모델 %s, 묶음 %d개%n", model, prepared.size());
        ClaudeProblemReviewer reviewer = new ClaudeProblemReviewer(model);
        List<GroupScore> scores = new ArrayList<>();
        for (Prepared p : prepared) {
            System.out.printf("  %s 검수 중...%n", p.group().id());
            List<Finding> findings = reviewer.review(p.problems(), p.group().difficulty(), p.source());
            scores.add(score(p.group(), findings));
        }

        String report = render(scores, model)
                + "\n" + ReviewEvalCli.renderCost(model, reviewer.inputTokens(), reviewer.outputTokens());
        System.out.println();
        System.out.println(report);
        Path dir = Path.of(opts.getOrDefault("out", DEFAULT_OUT_DIR));
        Files.createDirectories(dir);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String label = opts.containsKey("label") ? "-" + opts.get("label") : "";
        Path out = dir.resolve("problem-review-" + stamp + label + ".md");
        Files.writeString(out, report);
        System.out.println("보고서 저장: " + out);
    }

    /* ── 준비 ── */

    static Prepared prepare(Group g) throws Exception {
        GeneratedDocumentFile docFile = MAPPER.readValue(Path.of(g.document()).toFile(), GeneratedDocumentFile.class);
        GeneratedDocumentItem doc = "ADVANCED".equals(g.edition()) ? docFile.advancedDocument() : docFile.document();
        List<GeneratedProblemItem> problems = new ArrayList<>();
        for (ProblemRef ref : g.problems()) {
            GeneratedBatchFile batch = MAPPER.readValue(Path.of(ref.batch()).toFile(), GeneratedBatchFile.class);
            problems.add(mutate(batch.problems().get(ref.index()), ref.mutationsOrEmpty()));
        }
        return new Prepared(g, new SourceDocument(doc.slug(), doc.title(), doc.contentMd()), problems);
    }

    /** 정답 표시를 옮기거나 보기 문장을 바꿔 오류를 심는다. */
    static GeneratedProblemItem mutate(GeneratedProblemItem p, List<Mutation> mutations) {
        List<GeneratedChoice> choices = new ArrayList<>(p.choices());
        for (Mutation m : mutations) {
            if (m.moveAnswerTo() != null) {
                for (int i = 0; i < choices.size(); i++) {
                    GeneratedChoice c = choices.get(i);
                    choices.set(i, new GeneratedChoice(c.text(), i == m.moveAnswerTo(), c.rationale(), c.matchText()));
                }
            }
            if (m.choice() != null) {
                GeneratedChoice c = choices.get(m.choice());
                if (c.text().equals(m.text())) {
                    throw new IllegalStateException("바꿀 보기 문장이 원래와 같습니다: " + m.text());
                }
                choices.set(m.choice(), new GeneratedChoice(m.text(), c.correct(), c.rationale(), c.matchText()));
            }
        }
        return new GeneratedProblemItem(p.question(), p.answer(), p.explanation(), choices, p.sourceQuote(),
                p.title(), p.questionKind(), p.skipReason());
    }

    /* ── 채점 ── */

    /** 심은 문제는 기대한 종류의 지적이 하나라도 있으면 적발, 멀쩡한 문제에 달린 지적은 헛경보 후보다. */
    static GroupScore score(Group g, List<Finding> findings) {
        List<String> caught = new ArrayList<>();
        List<String> missed = new ArrayList<>();
        List<Finding> falseAlarms = new ArrayList<>();
        for (int i = 0; i < g.problems().size(); i++) {
            ProblemRef ref = g.problems().get(i);
            int index = i;
            List<Finding> mine = findings.stream().filter(f -> f.problemIndex() == index).toList();
            if (ref.expectOrEmpty().isEmpty()) {
                falseAlarms.addAll(mine);
            } else if (mine.stream().anyMatch(f -> ref.expectOrEmpty().contains(f.type()))) {
                caught.add(ref.label(i));
            } else {
                missed.add(ref.label(i));
            }
        }
        long clean = g.problems().stream().filter(r -> r.expectOrEmpty().isEmpty()).count();
        return new GroupScore(g.id(), caught, missed, falseAlarms, (int) clean, findings);
    }

    static String render(List<GroupScore> scores, String model) {
        int caught = scores.stream().mapToInt(s -> s.caught().size()).sum();
        int planted = caught + scores.stream().mapToInt(s -> s.missed().size()).sum();
        int clean = scores.stream().mapToInt(GroupScore::cleanProblems).sum();
        int alarms = scores.stream().mapToInt(s -> s.falseAlarms().size()).sum();

        Map<FindingType, Integer> byType = new LinkedHashMap<>();
        scores.forEach(s -> s.falseAlarms().forEach(f -> byType.merge(f.type(), 1, Integer::sum)));

        StringBuilder sb = new StringBuilder("# 문제 검수 적발률 — ").append(model).append("\n\n");
        sb.append("| 지표 | 값 |\n|---|---|\n");
        sb.append("| 적발 (심은 오류) | %d/%d |\n".formatted(caught, planted));
        sb.append("| 헛경보 후보 (멀쩡한 문제 %d개에 달린 지적) | %d건 |\n\n".formatted(clean, alarms));
        if (!byType.isEmpty()) {
            sb.append("| 헛경보 종류 | 건수 |\n|---|---|\n");
            byType.forEach((t, n) -> sb.append("| %s | %d |\n".formatted(t.label(), n)));
            sb.append('\n');
        }
        sb.append("헛경보 후보에는 원래 있던 결함이 섞여 있을 수 있다. 읽고 진짜면 표본의 expect에 올린다.\n");

        for (GroupScore s : scores) {
            sb.append("\n## ").append(s.groupId()).append("\n\n");
            s.caught().forEach(c -> sb.append("- 적발: ").append(c).append('\n'));
            s.missed().forEach(m -> sb.append("- **놓침**: ").append(m).append('\n'));
            s.falseAlarms().forEach(f -> sb.append("- 헛경보 후보: %d번 [%s] %s\n"
                    .formatted(f.problemIndex() + 1, f.type().label(), f.message())));
        }
        return sb.toString();
    }

    /* ── 표본 형태 ── */

    record SampleFile(String note, List<Group> groups) {
    }

    /** difficulty는 이 묶음을 "몇 급으로 냈다"고 알리는 라벨이다. 일부러 틀리게 붙여 난이도 오류를 심는다. */
    record Group(String id, String document, String edition, Difficulty difficulty, List<ProblemRef> problems) {
    }

    record ProblemRef(String batch, int index, String note, List<Mutation> mutations, List<FindingType> expect) {
        List<Mutation> mutationsOrEmpty() {
            return mutations == null ? List.of() : mutations;
        }

        List<FindingType> expectOrEmpty() {
            return expect == null ? List.of() : expect;
        }

        String label(int position) {
            return "%d번 (%s #%d)%s".formatted(position + 1, Path.of(batch).getFileName(), index,
                    note == null ? "" : " — " + note);
        }
    }

    /** moveAnswerTo: 정답 표시를 이 보기(0부터)로 옮긴다. choice·text: 그 보기 문장을 바꾼다. */
    record Mutation(Integer moveAnswerTo, Integer choice, String text) {
    }

    record Prepared(Group group, SourceDocument source, List<GeneratedProblemItem> problems) {
    }

    record GroupScore(String groupId, List<String> caught, List<String> missed, List<Finding> falseAlarms,
                      int cleanProblems, List<Finding> findings) {
    }
}
