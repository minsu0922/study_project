package project.study.study_project.llm.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.global.common.Domain;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 날짜 순환 규칙 테스트 — docs/14.
 *
 * <p>이 규칙이 지켜야 할 성질은 세 가지다: <b>빠짐없이 돈다</b>(24일에 24칸),
 * <b>결정적이다</b>(같은 날짜면 언제 어디서 계산해도 같은 답 — 클라우드와 로컬이 같은 답을 내야 하므로),
 * <b>매일 분야가 바뀐다</b>(검수하는 사람이 지루하지 않게).
 *
 * <p>이 테스트가 특히 중요한 이유: 순환 계산이 틀려도 <b>배치는 아무 오류 없이 잘 돈다</b>.
 * 그냥 매일 같은 칸만 채우거나 특정 칸을 영영 건너뛸 뿐이라, 몇 달 뒤 "왜 운영체제 문제만 잔뜩 있지?"
 * 하고서야 알게 된다. 조용히 틀리는 종류의 로직은 테스트로 못 박아 두는 수밖에 없다.
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
}
