package project.study.study_project.llm.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 날짜 순환 규칙 테스트 — docs/14(칸 순환), docs/15(4일 주기).
 *
 * <p>이 테스트가 특히 중요한 이유: 순환 계산이 틀려도 <b>배치는 아무 오류 없이 잘 돈다</b>.
 * 그냥 매일 같은 칸만 채우거나 특정 칸을 영영 건너뛸 뿐이라, 몇 달 뒤 "왜 운영체제 문제만 잔뜩 있지?"
 * 하고서야 알게 된다. 조용히 틀리는 종류의 로직은 테스트로 못 박아 두는 수밖에 없다.
 *
 * <p>2단계에서 주 규칙이 {@code planFor}(4일 주기)로 바뀌었고, {@code cellFor}(24칸 순환)는
 * <b>근거 문서를 못 찾았을 때의 폴백</b>으로 남았다. 둘 다 계속 검증한다 — 폴백이야말로
 * 평소에 안 도는 경로라 조용히 썩기 쉽다.
 */
class GenerationScheduleTest {

    /** 실제 설정(application.yml의 batch-domains)과 같은 8개 — 순서까지 동일해야 의미가 있다. */
    private static final List<Domain> DOMAINS = List.of(
            Domain.NETWORK, Domain.OS, Domain.DATABASE, Domain.DS_ALGORITHM,
            Domain.SYSTEM_DESIGN, Domain.SECURITY, Domain.LANGUAGE_RUNTIME, Domain.BACKEND_FRAMEWORK);

    @Test
    @DisplayName("24일이면 8분야 × 3난이도 = 24칸을 정확히 한 번씩 모두 돈다")
    void coversEveryCellExactlyOnceIn24Days() {
        LocalDate start = LocalDate.of(2026, 8, 12);
        Set<GenerationSchedule.Cell> visited = new HashSet<>();

        for (int i = 0; i < 24; i++) {
            visited.add(GenerationSchedule.cellFor(start.plusDays(i), DOMAINS));
        }

        // 중복이 있었다면 Set 크기가 24보다 작다 = 어떤 칸은 두 번, 어떤 칸은 영영 안 나온다는 뜻
        assertThat(visited).as("24일 동안 서로 다른 24칸이 나와야 한다").hasSize(24);
    }

    @Test
    @DisplayName("25일째에는 첫날과 같은 칸으로 돌아온다 — 주기가 정확히 24일")
    void wrapsAroundAfter24Days() {
        LocalDate start = LocalDate.of(2026, 8, 12);

        assertThat(GenerationSchedule.cellFor(start.plusDays(24), DOMAINS))
                .isEqualTo(GenerationSchedule.cellFor(start, DOMAINS));
    }

    @Test
    @DisplayName("같은 날짜는 항상 같은 칸 — 클라우드와 로컬이 같은 답을 내야 하므로 결정적이어야 한다")
    void isDeterministic() {
        LocalDate date = LocalDate.of(2026, 8, 12);

        assertThat(GenerationSchedule.cellFor(date, DOMAINS))
                .isEqualTo(GenerationSchedule.cellFor(date, DOMAINS));
    }

    @Test
    @DisplayName("연속된 날은 분야가 서로 다르다 — 사흘 내리 같은 분야가 나오면 검수가 지루해진다")
    void consecutiveDaysUseDifferentDomains() {
        LocalDate start = LocalDate.of(2026, 8, 12);

        for (int i = 0; i < 30; i++) {
            Domain today = GenerationSchedule.cellFor(start.plusDays(i), DOMAINS).domain();
            Domain tomorrow = GenerationSchedule.cellFor(start.plusDays(i + 1), DOMAINS).domain();
            assertThat(today).as("%d일째와 다음 날의 분야", i).isNotEqualTo(tomorrow);
        }
    }

    @Test
    @DisplayName("후보 도메인이 비어 있으면 전체 도메인으로 보정한다 — 설정 누락이 기능 정지로 이어지지 않게")
    void fallsBackToAllDomainsWhenCandidatesEmpty() {
        LocalDate date = LocalDate.of(2026, 8, 12);

        GenerationSchedule.Cell fromEmpty = GenerationSchedule.cellFor(date, List.of());
        GenerationSchedule.Cell fromNull = GenerationSchedule.cellFor(date, null);

        assertThat(fromEmpty.domain()).isNotNull();
        assertThat(fromEmpty.difficulty()).isNotNull();
        assertThat(fromNull).as("null과 빈 목록은 같게 취급").isEqualTo(fromEmpty);
    }

    /* ══ 2단계: 4일 주기(문서 1 + 문제 3) ══════════════════════ */

    @Test
    @DisplayName("나흘이 '문서 → 초급 → 중급 → 고급' 순서로 돌고, 사흘 내내 같은 분야를 쓴다")
    void cycleRunsDocumentThenThreeDifficulties() {
        LocalDate cycleStart = firstDayOfCycle(LocalDate.of(2026, 8, 12));

        GenerationSchedule.Plan day0 = GenerationSchedule.planFor(cycleStart, DOMAINS);
        GenerationSchedule.Plan day1 = GenerationSchedule.planFor(cycleStart.plusDays(1), DOMAINS);
        GenerationSchedule.Plan day2 = GenerationSchedule.planFor(cycleStart.plusDays(2), DOMAINS);
        GenerationSchedule.Plan day3 = GenerationSchedule.planFor(cycleStart.plusDays(3), DOMAINS);

        assertThat(day0.documentDay()).as("0일차는 문서를 쓰는 날").isTrue();
        assertThat(day0.difficulty()).as("문서에는 난이도 축이 없다").isNull();

        assertThat(day1.documentDay()).isFalse();
        assertThat(day1.difficulty()).isEqualTo(Difficulty.BEGINNER);
        assertThat(day2.difficulty()).isEqualTo(Difficulty.INTERMEDIATE);
        assertThat(day3.difficulty()).isEqualTo(Difficulty.ADVANCED);

        assertThat(List.of(day1.domain(), day2.domain(), day3.domain()))
                .as("문제 사흘은 0일차 문서와 같은 분야여야 한다 — 다르면 근거 문서가 엉뚱해진다")
                .containsOnly(day0.domain());
    }

    /**
     * <b>이 테스트가 2단계 전체를 지탱한다.</b> 문제일이 가리키는 문서 날짜가 틀리면 배치는
     * 오류 없이 <b>엉뚱한 문서로 문제를 만든다</b> — SECURITY 문서를 주고 DATABASE 문제를
     * 내라고 하는 꼴이 되는데, 모델은 그래도 뭔가 그럴듯한 걸 만들어 낸다.
     */
    @Test
    @DisplayName("문제일 사흘이 모두 같은 문서 날짜(주기 0일차)를 가리킨다")
    void allProblemDaysPointToTheSameDocumentDate() {
        LocalDate cycleStart = firstDayOfCycle(LocalDate.of(2026, 8, 12));

        for (int i = 0; i < GenerationSchedule.CYCLE_DAYS; i++) {
            assertThat(GenerationSchedule.planFor(cycleStart.plusDays(i), DOMAINS).documentDate())
                    .as("%d일차가 가리키는 문서 날짜", i)
                    .isEqualTo(cycleStart);
        }
    }

    @Test
    @DisplayName("주기가 끝나면 다음 분야로 넘어간다 — 한 분야에 머무르면 나머지가 영영 안 나온다")
    void movesToNextDomainAfterEachCycle() {
        LocalDate cycleStart = firstDayOfCycle(LocalDate.of(2026, 8, 12));
        List<Domain> visited = new java.util.ArrayList<>();

        for (int cycle = 0; cycle < DOMAINS.size(); cycle++) {
            visited.add(GenerationSchedule
                    .planFor(cycleStart.plusDays((long) cycle * GenerationSchedule.CYCLE_DAYS), DOMAINS)
                    .domain());
        }

        assertThat(visited).as("8주기(32일)면 8분야를 한 번씩 모두 돈다")
                .containsExactlyInAnyOrderElementsOf(DOMAINS);
    }

    @Test
    @DisplayName("같은 날짜는 항상 같은 계획 — 클라우드와 로컬이 같은 답을 내야 한다")
    void planIsDeterministic() {
        LocalDate date = LocalDate.of(2026, 8, 12);

        assertThat(GenerationSchedule.planFor(date, DOMAINS))
                .isEqualTo(GenerationSchedule.planFor(date, DOMAINS));
    }

    @Test
    @DisplayName("나흘에 문서일은 정확히 하루 — 매일 문서를 쓰면 비용이 네 배가 된다")
    void exactlyOneDocumentDayPerCycle() {
        LocalDate start = LocalDate.of(2026, 8, 12);
        long documentDays = java.util.stream.IntStream.range(0, GenerationSchedule.CYCLE_DAYS)
                .filter(i -> GenerationSchedule.planFor(start.plusDays(i), DOMAINS).documentDay())
                .count();

        assertThat(documentDays).isEqualTo(1);
    }

    @Test
    @DisplayName("계획도 후보 도메인이 비면 전체로 보정한다")
    void planFallsBackToAllDomainsWhenCandidatesEmpty() {
        LocalDate date = LocalDate.of(2026, 8, 12);

        assertThat(GenerationSchedule.planFor(date, null))
                .isEqualTo(GenerationSchedule.planFor(date, List.of()));
    }

    /** 주어진 날짜가 속한 주기의 0일차. 테스트가 특정 요일에만 통과하는 일을 막는다. */
    private LocalDate firstDayOfCycle(LocalDate date) {
        return date.minusDays(Math.floorMod(date.toEpochDay(), GenerationSchedule.CYCLE_DAYS));
    }
}
