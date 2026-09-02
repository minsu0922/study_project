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

    /**
     * 아래 대부분의 테스트가 쓰는 앵커 = 에포크 = <b>앵커가 없던 시절의 위상</b>.
     *
     * <p>기존 테스트를 새 위상으로 옮겨 적지 않고 이 값을 넘기게 둔 이유: 그중 몇은 2026-08-13
     * 스케줄링 버그를 잡을 때 <b>손으로 계산한 실물 날짜표</b>다(DraftGeneratorCliTest 쪽).
     * 위상을 바꿔 다시 적으면 그 표가 증거이길 그만둔다 — 옛 규칙으로 되돌렸을 때 실패하는지를
     * 확인하며 만든 값들이다.
     */
    private static final LocalDate ANCHOR = GenerationSchedule.DEFAULT_ANCHOR;

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

        GenerationSchedule.Plan day0 = GenerationSchedule.planFor(cycleStart, DOMAINS, ANCHOR);
        GenerationSchedule.Plan day1 = GenerationSchedule.planFor(cycleStart.plusDays(1), DOMAINS, ANCHOR);
        GenerationSchedule.Plan day2 = GenerationSchedule.planFor(cycleStart.plusDays(2), DOMAINS, ANCHOR);
        GenerationSchedule.Plan day3 = GenerationSchedule.planFor(cycleStart.plusDays(3), DOMAINS, ANCHOR);

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
            assertThat(GenerationSchedule.planFor(cycleStart.plusDays(i), DOMAINS, ANCHOR).documentDate())
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
                    .planFor(cycleStart.plusDays((long) cycle * GenerationSchedule.CYCLE_DAYS), DOMAINS, ANCHOR)
                    .domain());
        }

        assertThat(visited).as("8주기(32일)면 8분야를 한 번씩 모두 돈다")
                .containsExactlyInAnyOrderElementsOf(DOMAINS);
    }

    @Test
    @DisplayName("같은 날짜는 항상 같은 계획 — 클라우드와 로컬이 같은 답을 내야 한다")
    void planIsDeterministic() {
        LocalDate date = LocalDate.of(2026, 8, 12);

        assertThat(GenerationSchedule.planFor(date, DOMAINS, ANCHOR))
                .isEqualTo(GenerationSchedule.planFor(date, DOMAINS, ANCHOR));
    }

    @Test
    @DisplayName("나흘에 문서일은 정확히 하루 — 매일 문서를 쓰면 비용이 네 배가 된다")
    void exactlyOneDocumentDayPerCycle() {
        LocalDate start = LocalDate.of(2026, 8, 12);
        long documentDays = java.util.stream.IntStream.range(0, GenerationSchedule.CYCLE_DAYS)
                .filter(i -> GenerationSchedule.planFor(start.plusDays(i), DOMAINS, ANCHOR).documentDay())
                .count();

        assertThat(documentDays).isEqualTo(1);
    }

    @Test
    @DisplayName("계획도 후보 도메인이 비면 전체로 보정한다")
    void planFallsBackToAllDomainsWhenCandidatesEmpty() {
        LocalDate date = LocalDate.of(2026, 8, 12);

        assertThat(GenerationSchedule.planFor(date, null, ANCHOR))
                .isEqualTo(GenerationSchedule.planFor(date, List.of(), ANCHOR));
    }

    /* ══ 3단계: 주기의 시작을 옮기는 앵커(2026-09-02) ═══════════ */

    /**
     * <b>앵커의 존재 이유가 이 한 줄이다.</b> 지정한 날이 문서일이 아니면 "내일부터 새 문서로
     * 시작"이라는 요구 자체가 성립하지 않는다.
     *
     * <p>여러 날짜로 도는 이유: 하나만 보면 <b>그 날이 마침 옛 위상에서도 문서일</b>이라 통과하는
     * 경우를 구분하지 못한다(4일 중 하나는 그렇다). 넷을 연달아 보면 그중 셋은 옛 위상에서
     * 문서일이 아니므로, 앵커가 정말 작동해야만 넷 다 통과한다.
     */
    @Test
    @DisplayName("앵커로 지정한 날은 언제나 그 주기의 0일차(문서일)다")
    void anchorDayIsAlwaysADocumentDay() {
        for (int i = 0; i < 4; i++) {
            LocalDate anchor = LocalDate.of(2026, 9, 3).plusDays(i);

            GenerationSchedule.Plan plan = GenerationSchedule.planFor(anchor, DOMAINS, anchor);

            assertThat(plan.documentDay()).as("앵커 %s", anchor).isTrue();
            assertThat(plan.documentDate()).isEqualTo(anchor);
        }
    }

    /**
     * 새 손잡이가 생겼다고 기존 계산이 조용히 달라지면 안 된다. 이미 만들어진 파일들이
     * 그 위상 위에 놓여 있어서, 위상이 몰래 바뀌면 어떤 파일이 어느 칸의 것인지 알 수 없게 된다.
     */
    @Test
    @DisplayName("앵커를 안 주거나 에포크를 주면 옛 계산과 완전히 같다")
    void defaultAnchorReproducesTheOldPhase() {
        for (int i = 0; i < 40; i++) {
            LocalDate date = LocalDate.of(2026, 8, 1).plusDays(i);

            GenerationSchedule.Plan explicit = GenerationSchedule.planFor(date, DOMAINS, ANCHOR);
            GenerationSchedule.Plan fromNull = GenerationSchedule.planFor(date, DOMAINS, null);

            assertThat(fromNull).as("null 앵커는 기본값과 같아야 한다").isEqualTo(explicit);
            // 옛 규칙을 여기서 직접 다시 계산해 대조한다 — 구현을 부르면 같이 틀려도 통과한다
            assertThat(explicit.documentDay())
                    .as("%s의 문서일 여부(옛 규칙: epochDay mod 4 == 0)", date)
                    .isEqualTo(date.toEpochDay() % GenerationSchedule.CYCLE_DAYS == 0);
        }
    }

    /**
     * 주기의 성질(나흘 = 문서+초·중·고, 사흘이 같은 문서를 가리킴)은 <b>앵커와 무관</b>해야 한다.
     * 앵커는 시작점을 옮길 뿐 규칙을 바꾸지 않는다 — 이게 깨지면 앵커가 규칙을 오염시킨 것이다.
     */
    @Test
    @DisplayName("어떤 앵커를 줘도 주기의 모양은 그대로다")
    void cycleShapeIsIndependentOfTheAnchor() {
        for (LocalDate anchor : List.of(
                LocalDate.of(2026, 9, 3), LocalDate.of(2026, 1, 1),
                LocalDate.of(2025, 12, 31), GenerationSchedule.DEFAULT_ANCHOR)) {

            List<Difficulty> difficulties = new java.util.ArrayList<>();
            for (int i = 0; i < GenerationSchedule.CYCLE_DAYS; i++) {
                GenerationSchedule.Plan plan = GenerationSchedule.planFor(anchor.plusDays(i), DOMAINS, anchor);
                assertThat(plan.documentDate()).as("앵커 %s의 %d일차가 가리키는 문서", anchor, i)
                        .isEqualTo(anchor);
                if (i > 0) {
                    difficulties.add(plan.difficulty());
                }
            }
            assertThat(difficulties).as("앵커 %s", anchor)
                    .containsExactly(Difficulty.BEGINNER, Difficulty.INTERMEDIATE, Difficulty.ADVANCED);
        }
    }

    /**
     * 앵커가 생기면서 <b>음수 offset이 실제로 가능해졌다</b> — 과거 날짜로 손수 실행하면 그렇게 된다.
     * {@code floorMod}가 아니라 {@code %}였다면 인덱스가 음수가 되어 배치가 통째로 죽는다.
     * 예전에는 "1970년 이전 날짜가 들어올 일은 없다"며 비용 0의 보험으로만 둔 방어였다.
     */
    @Test
    @DisplayName("앵커보다 앞선 날짜도 죽지 않는다 — 위상만 거꾸로 셀 뿐")
    void datesBeforeTheAnchorStillWork() {
        LocalDate anchor = LocalDate.of(2026, 9, 3);

        assertThat(GenerationSchedule.planFor(anchor.minusDays(1), DOMAINS, anchor).difficulty())
                .as("앵커 하루 전 = 직전 주기의 3일차 = 고급").isEqualTo(Difficulty.ADVANCED);
        assertThat(GenerationSchedule.planFor(anchor.minusDays(4), DOMAINS, anchor).documentDay())
                .as("앵커 나흘 전 = 직전 주기의 문서일").isTrue();
        assertThat(GenerationSchedule.planFor(anchor.minusDays(400), DOMAINS, anchor))
                .as("한참 전 날짜도 예외 없이 계획이 나온다").isNotNull();
    }

    /**
     * 앵커 직후 첫 주기는 후보 목록의 <b>첫 분야</b>다. 그냥 부수 효과가 아니라 알아 두어야 할
     * 성질이다 — 앵커를 옮기면 그날 이후 분야 배열이 통째로 달라지고, 그래서 옛 위상 기준으로
     * 미리 만들어 둔 문서를 가리키는 날짜가 사라진다.
     */
    @Test
    @DisplayName("앵커에서 분야 순환도 다시 시작한다 — 첫 주기는 후보 목록의 첫 분야")
    void domainRotationRestartsAtTheAnchor() {
        LocalDate anchor = LocalDate.of(2026, 9, 3);

        assertThat(GenerationSchedule.planFor(anchor, DOMAINS, anchor).domain())
                .isEqualTo(DOMAINS.get(0));
        assertThat(GenerationSchedule.planFor(anchor.plusDays(GenerationSchedule.CYCLE_DAYS), DOMAINS, anchor).domain())
                .isEqualTo(DOMAINS.get(1));
    }

    /** 주어진 날짜가 속한 주기의 0일차. 테스트가 특정 요일에만 통과하는 일을 막는다. */
    private LocalDate firstDayOfCycle(LocalDate date) {
        return date.minusDays(Math.floorMod(date.toEpochDay(), GenerationSchedule.CYCLE_DAYS));
    }
}
