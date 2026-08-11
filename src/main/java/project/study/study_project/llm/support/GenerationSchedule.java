package project.study.study_project.llm.support;

import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;

import java.time.LocalDate;
import java.util.List;

/**
 * "오늘은 어느 칸을 채울 것인가" — 날짜만으로 결정되는 순환 규칙(docs/14).
 *
 * <p><b>왜 DB를 안 보는가.</b> 이 규칙은 GitHub Actions 러너에서 돈다. 러너는 12분 뒤 사라지는
 * 임시 컴퓨터라 우리 MySQL에 접근할 수 없다(로컬 PC에 있고, 외부에 열어 줄 이유도 없다).
 * 그래서 기존 {@code LlmProblemService.pickScarcestCell}처럼 "가장 부족한 칸을 집계로 찾는"
 * 방식을 쓸 수 없다 — 대신 <b>날짜만 있으면 누구나 같은 답을 내는</b> 결정적 순환으로 바꿨다.
 *
 * <p><b>잃는 것과 얻는 것.</b> 잃는 것은 적응력이다(검수를 많이 한 칸을 우선 채우는 식의 반응).
 * 얻는 것은 두 가지다:
 * <ul>
 *   <li>DB 의존이 사라져 생성이 어디서든 돈다 — 이 설계 변경의 목적 그 자체.
 *   <li><b>"매일 같은 칸만 뽑히는" 사고가 원천 불가능</b>해진다. 집계 방식이 실제로 겪었던
 *       버그(검수를 미루면 그 칸의 정식 문제 수가 늘지 않아 계속 같은 칸이 최소로 뽑힘)는
 *       순환에서는 정의상 발생할 수 없다. 24일에 정확히 24칸을 한 번씩 돈다.
 * </ul>
 * 관리자 화면의 수동 생성은 여전히 집계 기반 자동 선택을 쓴다(DB가 옆에 있으므로) —
 * 두 경로가 다른 전략을 쓰는 것은 <b>가진 정보가 다르기 때문</b>이지 일관성 결함이 아니다.
 *
 * <p><b>왜 도메인을 빠르게 돌리는가.</b> 순번을 난이도 우선으로 돌리면(도메인 하나를 3일 연속)
 * 사흘 내리 같은 분야만 나온다. 도메인을 안쪽 축으로 두면 매일 분야가 바뀌어, 검수하는 사람이
 * 매일 다른 주제를 보게 된다 — 지루함이 검수를 미루게 만드는 가장 큰 이유라서 이렇게 정했다.
 */
public final class GenerationSchedule {

    private GenerationSchedule() {
    }

    /** 하루치 생성 대상 — 분야 × 난이도 한 칸. */
    public record Cell(Domain domain, Difficulty difficulty) {
    }

    /**
     * 날짜에 대응하는 칸을 계산한다.
     *
     * <p>계산식: 에포크일(1970-01-01부터의 일수)을 전체 칸 수로 나눈 나머지를 순번으로 삼는다.
     * 도메인은 순번을 도메인 수로 나눈 나머지(안쪽 축 = 매일 바뀜),
     * 난이도는 순번을 도메인 수로 나눈 몫(바깥 축 = 도메인을 한 바퀴 돌 때마다 한 단계).
     *
     * <p>{@link Math#floorMod}를 쓰는 이유: 자바 {@code %}는 음수에서 음수를 반환한다.
     * 1970년 이전 날짜가 들어올 일은 없지만, 인덱스 계산에서 음수가 나오면
     * {@code IndexOutOfBounds}로 배치가 통째로 죽으므로 방어해 둔다(비용 0의 안전장치).
     *
     * @param date    기준 날짜(UTC가 아니라 <b>한국 날짜</b>를 넘길 것 — 워크플로가 KST로 변환해 전달)
     * @param domains 후보 도메인. 비어 있으면 전체 도메인을 후보로 본다(설정 누락 시 기능 정지 방지)
     */
    public static Cell cellFor(LocalDate date, List<Domain> domains) {
        List<Domain> candidates = (domains == null || domains.isEmpty())
                ? List.of(Domain.values()) : domains;
        Difficulty[] difficulties = Difficulty.values();

        int totalCells = candidates.size() * difficulties.length;
        int index = (int) Math.floorMod(date.toEpochDay(), totalCells);

        Domain domain = candidates.get(index % candidates.size());
        Difficulty difficulty = difficulties[(index / candidates.size()) % difficulties.length];
        return new Cell(domain, difficulty);
    }
}
