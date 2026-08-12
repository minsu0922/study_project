package project.study.study_project.llm.support;

import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;

import java.time.LocalDate;
import java.util.List;

/**
 * "오늘은 무엇을 만들 것인가" — 날짜만으로 결정되는 순환 규칙(docs/14, 15).
 *
 * <p><b>왜 DB를 안 보는가.</b> 이 규칙은 GitHub Actions 러너에서 돈다. 러너는 몇 분 뒤 사라지는
 * 임시 컴퓨터라 우리 MySQL에 접근할 수 없다(로컬 PC에 있고, 외부에 열어 줄 이유도 없다).
 * 그래서 "가장 부족한 칸을 집계로 찾는" 방식을 쓸 수 없다 — 대신 <b>날짜만 있으면 누구나 같은
 * 답을 내는</b> 결정적 순환으로 바꿨다. 관리자 화면의 수동 생성은 여전히 집계 기반 자동 선택을
 * 쓴다(DB가 옆에 있으므로) — 두 경로가 다른 전략인 것은 <b>가진 정보가 다르기 때문</b>이다.
 *
 * <h2>2단계: 하루 한 칸 → 주제 단위 4일 주기</h2>
 *
 * <p>원래는 매일 (분야 × 난이도) 한 칸을 돌며 문제만 만들었다. 그런데 그렇게 나온 문제들이
 * 서로 남남이라 <b>지식이 파편으로 쌓인다</b>는 문제가 있었다(사용자 진단). 그래서 개념 문서
 * 한 편을 중심으로 나흘을 묶는다.
 *
 * <pre>
 *   0일차: 개념 문서 1편 (예: SECURITY)
 *   1일차: ↑ 그 문서를 근거로 초급 문제
 *   2일차: ↑ 같은 문서로 중급 문제
 *   3일차: ↑ 같은 문서로 고급 문제
 *   → 다음 분야로
 * </pre>
 *
 * <p><b>버린 것 — 분야의 매일 변화.</b> 이전 규칙은 분야를 안쪽 축에 두어 매일 다른 분야가
 * 나오게 했다. 검수하는 사람이 지루하지 않도록 한 선택이었는데, 이제는 <b>같은 분야가 사흘
 * 연속</b>으로 나온다. 문서 한 편으로 세 난이도를 뽑는다는 목표와 근본적으로 맞바꿀 수 없는
 * 부분이다. 대신 얻는 것이 크다 — 문제에 근거 문서가 생겨 환각이 줄고, 학습자는 막혔을 때
 * 돌아가 읽을 곳이 생긴다.
 *
 * <p><b>왜 난이도를 오름차순으로 고정하나.</b> 초급 → 중급 → 고급 순서를 날짜에 묶어 두면
 * 학습자가 사흘에 걸쳐 같은 주제를 계단식으로 밟는다. 순서를 섞으면 첫날 고급이 나와
 * 문서를 읽고도 못 푸는 일이 생긴다 — 사다리는 아래부터 놓아야 한다.
 */
public final class GenerationSchedule {

    /** 한 주기의 길이 — 문서 1일 + 문제 3일(초급·중급·고급). */
    public static final int CYCLE_DAYS = 1 + 3;

    private GenerationSchedule() {
    }

    /** 하루치 생성 대상 — 분야 × 난이도 한 칸. */
    public record Cell(Domain domain, Difficulty difficulty) {
    }

    /**
     * 하루치 생성 계획.
     *
     * @param documentDay  오늘이 문서를 쓰는 날인지. {@code true}면 {@code difficulty}는 의미가 없다
     *                     (문서는 한 편 안에 세 난이도의 재료를 모두 담는 것이 목표라 난이도 축이 없다)
     * @param domain       이번 주기의 분야 — 나흘 내내 같다
     * @param difficulty   문제일의 난이도. 문서일에는 {@code null}
     * @param documentDate 이번 주기 0일차의 날짜 = 근거 문서 파일의 날짜.
     *                     문제일에 {@code generated/documents/{documentDate}.json}을 읽으면 된다.
     *                     문서일에는 오늘 날짜 그 자체다
     */
    public record Plan(boolean documentDay, Domain domain, Difficulty difficulty, LocalDate documentDate) {
    }

    /**
     * 날짜에 대응하는 계획을 계산한다.
     *
     * <p>계산식:
     * <ul>
     *   <li>{@code dayInCycle = epochDay mod 4} — 0이면 문서일, 1·2·3이면 각각 초·중·고급 문제일
     *   <li>{@code cycleIndex = epochDay / 4} — 주기 번호. 이걸 분야 수로 나눈 나머지가 이번 주기의 분야
     *   <li>{@code documentDate = 오늘 - dayInCycle} — 이번 주기가 시작된 날
     * </ul>
     *
     * <p>{@link Math#floorMod}/{@link Math#floorDiv}를 쓰는 이유: 자바의 {@code %}와 {@code /}는
     * 음수에서 0 방향으로 자른다. 1970년 이전 날짜가 들어올 일은 없지만, 인덱스 계산에서 음수가
     * 나오면 {@code IndexOutOfBounds}로 배치가 통째로 죽는다(비용 0의 안전장치).
     *
     * @param date    기준 날짜(UTC가 아니라 <b>한국 날짜</b>를 넘길 것 — 워크플로가 KST로 변환해 전달)
     * @param domains 후보 분야. 비어 있으면 전체를 후보로 본다(설정 누락 시 기능 정지 방지)
     */
    public static Plan planFor(LocalDate date, List<Domain> domains) {
        List<Domain> candidates = candidates(domains);
        long epochDay = date.toEpochDay();

        int dayInCycle = (int) Math.floorMod(epochDay, CYCLE_DAYS);
        long cycleIndex = Math.floorDiv(epochDay, CYCLE_DAYS);

        Domain domain = candidates.get((int) Math.floorMod(cycleIndex, candidates.size()));
        LocalDate documentDate = date.minusDays(dayInCycle);

        if (dayInCycle == 0) {
            return new Plan(true, domain, null, documentDate);
        }
        // 1 → BEGINNER, 2 → INTERMEDIATE, 3 → ADVANCED.
        // enum 선언 순서에 기대는 대신 인덱스로 직접 꺼낸다 — 순서가 곧 난이도 오름차순이라는
        // 사실은 Difficulty가 이미 보증한다(초급·중급·고급 순으로 선언).
        Difficulty difficulty = Difficulty.values()[dayInCycle - 1];
        return new Plan(false, domain, difficulty, documentDate);
    }

    /**
     * 날짜에 대응하는 (분야 × 난이도) 칸 — <b>주기를 무시하는</b> 옛 규칙.
     *
     * <p>2단계에서 {@link #planFor}로 대체됐지만 지우지 않았다. 근거 문서를 못 찾았을 때의
     * <b>폴백 경로</b>가 이 규칙을 쓴다: 문서 생성이 실패했거나 검수에서 거절된 주기에는
     * 근거 없이 문제를 만들어야 하는데, 그때 "이번 주기의 난이도"를 그대로 쓰면 같은 분야만
     * 계속 나온다(주기가 통째로 헛돌아도 분야는 진행되지 않으므로). 폴백은 옛 규칙으로
     * 돌아가 분야를 매일 바꾸는 편이 낫다 — 최소한 다양성은 확보된다.
     */
    public static Cell cellFor(LocalDate date, List<Domain> domains) {
        List<Domain> candidates = candidates(domains);
        Difficulty[] difficulties = Difficulty.values();

        int totalCells = candidates.size() * difficulties.length;
        int index = (int) Math.floorMod(date.toEpochDay(), totalCells);

        Domain domain = candidates.get(index % candidates.size());
        Difficulty difficulty = difficulties[(index / candidates.size()) % difficulties.length];
        return new Cell(domain, difficulty);
    }

    private static List<Domain> candidates(List<Domain> domains) {
        return (domains == null || domains.isEmpty()) ? List.of(Domain.values()) : domains;
    }
}
