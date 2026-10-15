package project.study.study_project.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import project.study.study_project.TestDomains;
import project.study.study_project.admin.dto.AdminCodeBatchStatus;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.llm.client.CodeProblem;
import project.study.study_project.llm.client.GeneratedProblemItem;
import project.study.study_project.llm.client.ProblemReview;
import project.study.study_project.llm.dto.GeneratedBatchFile;
import project.study.study_project.llm.repository.ImportedDraftFileRepository;
import project.study.study_project.llm.service.TopicQueueService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 주간 코드 배치 상태 — 다음 실행 시각과 결과 파일 묶기. DB·스프링 없이 본다. */
class AdminCodeBatchServiceTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("다음 실행은 일요일 02:47 — 일요일 02:47 전이면 오늘, 지나면 다음 주")
    void nextRunIsSundayMorning() {
        // 2026-10-15는 목요일
        assertThat(AdminCodeBatchService.nextRunAt(LocalDateTime.of(2026, 10, 15, 22, 0)))
                .isEqualTo(LocalDateTime.of(2026, 10, 18, 2, 47));
        assertThat(AdminCodeBatchService.nextRunAt(LocalDateTime.of(2026, 10, 18, 1, 0)))
                .isEqualTo(LocalDateTime.of(2026, 10, 18, 2, 47));
        assertThat(AdminCodeBatchService.nextRunAt(LocalDateTime.of(2026, 10, 18, 3, 0)))
                .isEqualTo(LocalDateTime.of(2026, 10, 25, 2, 47));
    }

    @Test
    @DisplayName("결과 파일을 날짜로 묶어 문제 수·지적 수(의견 제외)·들여오기 여부를 최신 순으로 낸다")
    void groupsCodeFilesByDate() throws Exception {
        write("2026-10-13-code-beg.json", 2, List.of(
                new ProblemReview.Finding(0, ProblemReview.FindingType.EXPLANATION_MISMATCH, "해설"),
                new ProblemReview.Finding(1, ProblemReview.FindingType.SOLUTION, "풀이")));
        write("2026-10-13-code-adv.json", 1, List.of());
        write("2026-10-18-code-int.json", 3, null);
        Files.writeString(dir.resolve("2026-10-14.json"), "{}");   // 일일 배치 파일은 세지 않는다

        ImportedDraftFileRepository imported = mock(ImportedDraftFileRepository.class);
        when(imported.existsById(anyString())).thenReturn(true);
        when(imported.existsById("2026-10-18-code-int.json")).thenReturn(false);
        AdminCodeBatchService service = new AdminCodeBatchService(mock(TopicQueueService.class), imported);

        List<AdminCodeBatchStatus.Run> runs = service.recentRuns(dir);

        assertThat(runs).extracting(AdminCodeBatchStatus.Run::date).containsExactly("2026-10-18", "2026-10-13");
        assertThat(runs.get(0)).satisfies(r -> {
            assertThat(r.problems()).isEqualTo(3);
            assertThat(r.findings()).as("검수를 안 돌린 파일").isNull();
            assertThat(r.imported()).isFalse();
        });
        assertThat(runs.get(1)).satisfies(r -> {
            assertThat(r.topic()).isEqualTo("주제");
            assertThat(r.problems()).isEqualTo(3);
            assertThat(r.findings()).isEqualTo(1);
            assertThat(r.imported()).isTrue();
        });
    }

    private void write(String name, int problems, List<ProblemReview.Finding> findings) throws Exception {
        List<GeneratedProblemItem> items = java.util.stream.IntStream.range(0, problems)
                .mapToObj(i -> new GeneratedProblemItem("질문 " + i, "", "해설",
                        List.of(new GeneratedProblemItem.GeneratedChoice("가", true)), "", "제목"))
                .toList();
        GeneratedBatchFile file = new GeneratedBatchFile("테스트", "2026-10-13", "2026-10-13T00:00:00Z",
                TestDomains.LANGUAGE_RUNTIME, Difficulty.BEGINNER, ProblemType.MULTIPLE_CHOICE, "m", null, items)
                .withReviewFindings(findings)
                .withCode("주제", List.<CodeProblem.Part>of());
        new ObjectMapper().writeValue(dir.resolve(name).toFile(), file);
    }
}
