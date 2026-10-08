package project.study.study_project.quiz.dto;

import java.util.List;

/**
 * 서술형의 모범 답안 — 학습자가 자기 답을 쓴 뒤에 받아 스스로 채점하는 데 쓴다.
 *
 * @param modelAnswer  모범 답안(문제의 해설 칸)
 * @param checkpoints  답에 들어가야 할 요점. 문제에 적어 두지 않았으면 빈 목록
 * @param documentSlug 근거 개념 문서. 실제로 있는 문서일 때만 채운다
 */
public record EssayModelAnswer(String modelAnswer, List<String> checkpoints, String documentSlug) {
}
