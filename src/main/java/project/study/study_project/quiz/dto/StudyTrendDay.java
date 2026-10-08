package project.study.study_project.quiz.dto;

import java.time.LocalDate;

/**
 * 학습 추이의 하루 — 그날 낸 제출 수와 그중 정답 수.
 *
 * <p>문제 수가 아니라 제출 수다. 같은 문제를 하루에 두 번 풀었으면 두 번 한 것이고,
 * 정답률은 "낸 것 가운데 맞힌 것"이어야 분자와 분모의 단위가 맞는다.
 */
public record StudyTrendDay(LocalDate date, long attempts, long correct) {
}
