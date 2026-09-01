package project.study.study_project.quiz.service;

import project.study.study_project.global.common.ProblemType;
import project.study.study_project.quiz.domain.Choice;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.support.MatchToken;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

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
 *   <li>짝짓기: {@code "왼쪽 → 오른쪽"} 쌍을 줄바꿈으로 이어서(2026-08-31)
 *   <li>순서 배열: 항목의 text를 정답 순서대로 {@code " → "}로 이어서(2026-08-31)
 * </ul>
 *
 * <p><b>새 유형 둘은 "번호를 글자로 바꾼다"는 이 클래스의 목적이 가장 잘 드러나는 자리다.</b>
 * 저장값이 {@code "12|9|11|10"}(보기 id)이나 {@code "12-a3f1…"}(id와 토큰)이라, 그대로 보여 주면
 * 학습자는 자기가 무엇을 답했는지조차 알 수 없다.
 */
final class AnswerDisplay {

    /** 순서 배열 표기의 항목 사이 기호. */
    private static final String ORDER_ARROW = " → ";

    /** 짝짓기 한 쌍 안에서 왼쪽과 오른쪽을 잇는 기호. */
    private static final String PAIR_ARROW = " → ";

    /** 짝짓기의 쌍과 쌍 사이. 화면이 여러 줄로 그릴 수 있게 줄바꿈을 쓴다. */
    private static final String PAIR_SEPARATOR = "\n";

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
            // 짝짓기의 정답은 행 자체에 있다(answer는 null, V16) — 쌍을 그대로 늘어놓으면 된다.
            // 저장된 seq 순서로 보여 준다. 학습자가 본 오른쪽 열은 섞여 있었지만, 정답을 보여 줄
            // 때까지 그 순서를 유지할 이유가 없다 — 오히려 왼쪽 순서대로 읽히는 편이 대조하기 쉽다.
            case MATCHING -> problem.getChoices().stream()
                    .filter(c -> c.getMatchText() != null)
                    .map(c -> c.getText() + PAIR_ARROW + c.getMatchText())
                    .collect(Collectors.joining(PAIR_SEPARATOR));
            case ORDERING -> orderedTextsOf(problem, seqOrderOf(problem.getAnswer()));
            case ESSAY -> null;
        };
    }

    /**
     * 사용자가 제출한 답을 표시용 문자열로.
     *
     * <p>객관식은 저장값이 choiceId(예 "2")라 그대로 보여주면 의미가 없으니 보기 text로 변환한다.
     * 순서 배열·짝짓기도 같은 이유로 글자로 되돌린다.
     *
     * <p><b>어느 단계에서든 못 알아보면 원문을 그대로 돌려준다.</b> 여기는 오답노트를 <b>읽는</b>
     * 경로다 — 옛 형식으로 저장된 답이나 손상된 값 하나 때문에 목록 전체가 500으로 죽는 것보다,
     * 그 한 줄만 날것으로 보이는 편이 낫다.
     */
    static String userAnswerOf(Problem problem, String rawUserAnswer) {
        if (rawUserAnswer == null) {
            return null;
        }
        return switch (problem.getType()) {
            case MULTIPLE_CHOICE -> problem.getChoices().stream()
                    .filter(c -> String.valueOf(c.getId()).equals(rawUserAnswer.trim()))
                    .findFirst()
                    .map(Choice::getText)
                    .orElse(rawUserAnswer);
            case ORDERING -> orderedTextsByIdOrElse(problem, rawUserAnswer);
            case MATCHING -> pairedTextsOrElse(problem, rawUserAnswer);
            case OX, SHORT_ANSWER, ESSAY -> rawUserAnswer;
        };
    }

    /* ── 순서 배열·짝짓기 표기 도우미 ─────────────────────────── */

    /** {@code "3|2|1|4"} → {@code [3, 2, 1, 4]}. 숫자가 아닌 토큰이 섞이면 빈 목록(표기를 포기한다). */
    private static List<Integer> seqOrderOf(String answer) {
        if (answer == null || answer.isBlank()) {
            return List.of();
        }
        List<Integer> order = new ArrayList<>();
        for (String token : answer.split("\\|")) { // |는 정규식 메타문자라 이스케이프
            try {
                order.add(Integer.parseInt(token.trim()));
            } catch (NumberFormatException e) {
                return List.of();
            }
        }
        return order;
    }

    /** seq 순서를 항목 글자로 — 못 찾은 seq는 건너뛴다(데이터가 어긋나도 나머지는 읽히게). */
    private static String orderedTextsOf(Problem problem, List<Integer> seqOrder) {
        return seqOrder.stream()
                .map(seq -> problem.getChoices().stream()
                        .filter(c -> c.getSeq() == seq)
                        .findFirst()
                        .map(Choice::getText)
                        .orElse(null))
                .filter(Objects::nonNull)
                .collect(Collectors.joining(ORDER_ARROW));
    }

    /**
     * 사용자가 배열한 <b>보기 id</b> 순서를 글자로.
     *
     * <p>정답 쪽({@code answer})은 seq로, 사용자 답은 id로 적힌다. 같은 순서를 두 가지로 적는
     * 셈인데, 이유가 있다 — 사용자는 화면에서 보기를 누르고 그 보기의 <b>id</b>만 알 수 있다
     * (seq는 요청마다 다시 매겨지는 화면 번호라 채점 기준이 될 수 없다,
     * {@code QuizChoiceItem.shuffledFrom}). 반대로 정답은 화면과 무관하게 <b>DB 안에서</b>
     * 고정돼야 하므로 id가 아니라 seq로 적는다(id는 문제를 다시 등록하면 바뀐다).
     */
    private static String orderedTextsByIdOrElse(Problem problem, String rawUserAnswer) {
        String joined = Arrays.stream(rawUserAnswer.split("\\|"))
                .map(token -> problem.getChoices().stream()
                        .filter(c -> String.valueOf(c.getId()).equals(token.trim()))
                        .findFirst()
                        .map(Choice::getText)
                        .orElse(null))
                .filter(Objects::nonNull)
                .collect(Collectors.joining(ORDER_ARROW));
        return joined.isEmpty() ? rawUserAnswer : joined;
    }

    /**
     * 사용자가 이은 짝({@code "12-a3f1…|9-77bc…"})을 글자로.
     *
     * <p>오른쪽은 토큰으로 오므로 이 문제의 오른쪽 항목들의 토큰을 다시 계산해 대조한다
     * ({@link MatchToken} 주석 — 토큰은 텍스트에서 계산되므로 서버가 기억할 것이 없다).
     * 틀린 짝도 그대로 보여 준다: 오답노트에서 학습자가 알고 싶은 것은 "내가 무엇과 무엇을
     * 이었는가"이지 "그중 맞은 것만"이 아니다.
     */
    private static String pairedTextsOrElse(Problem problem, String rawUserAnswer) {
        String joined = Arrays.stream(rawUserAnswer.split("\\|"))
                .map(entry -> {
                    int dash = entry.indexOf('-');
                    if (dash < 0) {
                        return null;
                    }
                    String leftId = entry.substring(0, dash).trim();
                    String token = entry.substring(dash + 1).trim();
                    String left = problem.getChoices().stream()
                            .filter(c -> String.valueOf(c.getId()).equals(leftId))
                            .findFirst()
                            .map(Choice::getText)
                            .orElse(null);
                    String right = problem.getChoices().stream()
                            .filter(c -> token.equals(MatchToken.of(problem.getId(), c.getMatchText())))
                            .findFirst()
                            .map(Choice::getMatchText)
                            .orElse(null);
                    return (left == null || right == null) ? null : left + PAIR_ARROW + right;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.joining(PAIR_SEPARATOR));
        return joined.isEmpty() ? rawUserAnswer : joined;
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
