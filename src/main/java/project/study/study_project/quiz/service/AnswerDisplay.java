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

    /**
     * 사용자가 고른 <b>그 보기</b>의 오답 설명 — 없으면 {@code null}(V15).
     *
     * <p><b>왜 오답노트에는 보기 넷을 다 안 주나.</b> 그 화면은 보기를 그리지 않는다.
     * "내 답 / 정답 / 해설"만 보여 주는 복습용 카드다. 거기서 학습자가 알고 싶은 것은
     * <b>내가 왜 틀렸는가</b> 하나뿐이고, 그건 내가 고른 보기의 설명이다. 넷을 다 실으면
     * 카드가 문제 풀이 화면으로 부풀고, 안 고른 보기의 설명까지 읽게 된다.
     *
     * <p>{@code null}이 되는 경우가 셋이다 — 객관식이 아닐 때, 옛 문제라 설명이 없을 때,
     * 그리고 <b>정답을 골랐는데 오답노트에 남아 있을 때</b>(과거에 틀린 뒤 다시 풀어 맞힌
     * 문제도 목록에 남는다, ADR-0002). 셋 다 화면이 그 줄을 안 그리면 되는 상태라
     * 구분해서 알릴 이유가 없다.
     */
    static String userAnswerRationaleOf(Problem problem, String rawUserAnswer) {
        if (problem.getType() != ProblemType.MULTIPLE_CHOICE || rawUserAnswer == null) {
            return null;
        }
        return problem.getChoices().stream()
                .filter(c -> String.valueOf(c.getId()).equals(rawUserAnswer.trim()))
                .findFirst()
                .map(Choice::getRationale)
                .orElse(null);
    }
}
