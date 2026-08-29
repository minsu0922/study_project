package project.study.study_project.quiz.dto;

import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;

import java.time.LocalDateTime;

/**
 * 문제 목록 화면의 한 줄 — 로그인 사용자 기준으로 개인화된다(docs/18, 2026-08-29).
 *
 * <h2>정답률 대신 마지막 시도일</h2>
 *
 * <p>첫 스펙에는 LeetCode처럼 정답률 열이 있었다. 그런데 이 서비스의 사용자는 사실상 한 명이라
 * 문제별 정답률은 <b>0% 아니면 100%</b>다(LeetCode의 acceptance rate는 표본이 수백만이라 성립한다).
 * "다음에 풀 문제 하나를 고른다"는 이 화면의 목적에는 <b>언제 마지막으로 건드렸나</b>가 훨씬
 * 직접적이라, 그 열을 마지막 시도일로 바꿨다.
 *
 * @param lastAttemptedAt 마지막 제출 시각. 한 번도 안 풀었으면 {@code null} —
 *                        "안 풀었다"와 "오래전에 풀었다"는 다른 말이므로 0이나 빈 문자열로
 *                        뭉개지 않는다(대시보드 정답률에서 이미 배운 구분이다)
 * @param state           풀이 상태. <b>한 번이라도 맞혔으면 계속 CORRECT</b>다(아래 설명)
 * @param reviewDue       지금 복습할 차례인지. {@code state}와 <b>따로</b> 두는 이유가 있다 —
 *                        state는 "맞힌 적 있나", reviewDue는 "지금도 아는지 확인할 때인가"라
 *                        서로 다른 질문에 답한다. 하나로 합치면 예전에 맞힌 문제가 흔들리고
 *                        있다는 사실이 목록에서 사라진다
 */
public record ProblemListItem(
        Long id,
        String title,
        Domain domain,
        String domainLabel,
        Difficulty difficulty,
        ProblemType type,
        LocalDateTime lastAttemptedAt,
        SolveState state,
        boolean reviewDue
) {

    /**
     * 풀이 상태 — 로그인 사용자 기준.
     *
     * <p><b>CORRECT의 판정은 "한 번이라도 맞혔나"다</b>(2026-08-29 사용자 결정). 대안은
     * "마지막 시도가 정답인가"였다. 후자가 "지금 이걸 아나"에 더 정확하지만, 맞혔던 문제를
     * 다시 틀리면 표시가 되돌아가 진척이 뒷걸음질친다.
     *
     * <p>전자를 택해도 "예전에 맞혔지만 지금은 흔들린다"가 목록에서 사라지지 않는 이유는
     * {@code reviewDue}가 그 자리를 따로 맡기 때문이다 — 복습 사다리가 별도 기능으로 있어서
     * 가능한 선택이다.
     */
    public enum SolveState {
        /** 제출 이력이 없다. */
        UNSOLVED,
        /** 맞힌 적이 있다(마지막 시도가 오답이어도 유지). */
        CORRECT,
        /** 제출은 했는데 아직 한 번도 못 맞혔다. */
        WRONG
    }
}
