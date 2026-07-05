package project.study.study_project.quiz.service;

import project.study.study_project.global.common.ProblemType;
import project.study.study_project.quiz.domain.Choice;
import project.study.study_project.quiz.domain.Problem;

/**
 * 답을 "사람이 읽는 표기"로 바꾸는 규칙 모음 — docs/03의 correctAnswer 표기 규칙과 1:1.
 *
 * <p>왜 분리했나: 채점 응답(QuizService)과 오답노트(WrongAnswerService)가 <b>같은 표기 규칙</b>을
 * 써야 한다. 각자 구현하면 한쪽만 고쳐지는 사고(예: 단답형 대표 정답 규칙 변경)가 나기 쉬워
 * 한 곳에 모았다. 상태가 없는 순수 함수라 static 유틸로 충분하다.
 *
 * <p>표기 규칙(docs/03):
 * <ul>
 *   <li>객관식: choiceId가 아니라 <b>보기의 text</b> (사용자에게 "2번" 대신 "전송 계층"을 보여준다)
 *   <li>OX: {@code "O"} / {@code "X"} 그대로
 *   <li>단답형: 복수 정답({@code |} 구분) 중 <b>첫 토큰</b>을 대표 정답으로
 * </ul>
 */
final class AnswerDisplay {

    private AnswerDisplay() {
        // 인스턴스화 방지 — 규칙(순수 함수)만 담는 유틸리티
    }

    /** 문제의 정답을 표시용 문자열로. (ESSAY는 MVP 채점 대상이 아니므로 null) */
    static String correctAnswerOf(Problem problem) {
        return switch (problem.getType()) {
            case MULTIPLE_CHOICE -> problem.getChoices().stream()
                    .filter(Choice::isCorrect)
                    .findFirst()
                    .map(Choice::getText)
                    .orElse(null); // 시드 규칙상 정답 보기 1개가 항상 존재하나, 데이터 오류에도 조회는 계속되게
            case OX -> problem.getAnswer();
            case SHORT_ANSWER -> problem.getAnswer().split("\\|")[0].trim();
            case ESSAY -> null;
        };
    }

    /**
     * 사용자가 제출한 답을 표시용 문자열로.
     * 객관식은 저장값이 choiceId(예 "2")라 그대로 보여주면 의미가 없으니 보기 text로 변환한다.
     * 보기를 못 찾으면(이론상 없음 — 제출 시 검증됨) 원문을 그대로 반환해 조회가 죽지 않게 한다.
     */
    static String userAnswerOf(Problem problem, String rawUserAnswer) {
        if (problem.getType() != ProblemType.MULTIPLE_CHOICE) {
            return rawUserAnswer;
        }
        return problem.getChoices().stream()
                .filter(c -> String.valueOf(c.getId()).equals(rawUserAnswer.trim()))
                .findFirst()
                .map(Choice::getText)
                .orElse(rawUserAnswer);
    }
}
