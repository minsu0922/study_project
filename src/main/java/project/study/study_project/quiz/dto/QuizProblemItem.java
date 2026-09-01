package project.study.study_project.quiz.dto;

import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.quiz.domain.Problem;

import java.util.List;

/**
 * 풀이용 문제 항목 — API 스펙(docs/03 GET /api/quiz).
 *
 * <p><b>정답({@code answer})·해설({@code explanation})·보기 정답 여부는 절대 포함하지 않는다.</b>
 * 이 값들은 채점 API(POST /api/quiz/submit)의 응답에서만 반환한다(스펙 명시).
 *
 * @param choices      {@code choice} 행을 쓰는 유형만 채운다({@link ProblemType#usesChoiceRows}) —
 *                     객관식은 보기, 짝짓기는 <b>왼쪽 열</b>, 순서 배열은 배열할 항목이다.
 *                     OX/단답형은 빈 배열(스펙과 동일한 모양 유지 — 클라이언트가 타입 분기 없이
 *                     항상 배열로 다룰 수 있게 null 대신 빈 리스트)
 * @param matchOptions 짝짓기의 <b>오른쪽 열</b>. 그 밖의 유형은 빈 배열(위와 같은 이유)
 */
public record QuizProblemItem(
        Long id,
        Domain domain,
        Difficulty difficulty,
        ProblemType type,
        String question,
        List<QuizChoiceItem> choices,
        List<QuizMatchOption> matchOptions
) {
    public static QuizProblemItem from(Problem p) {
        // 행을 쓰는 유형일 때만 LAZY 보기 컬렉션에 접근한다(불필요한 쿼리 방지).
        // 섞어서 내보내는 이유는 QuizChoiceItem.shuffledFrom 주석 참고 — 순서 배열도 같이
        // 섞인다. 오히려 그쪽이 더 중요하다: 저장 순서가 곧 정답 순서인 문제가 섞이지 않고
        // 나가면 "보이는 대로 두면 정답"이 된다.
        List<QuizChoiceItem> choices = p.getType().usesChoiceRows()
                ? QuizChoiceItem.shuffledFrom(p.getChoices())
                : List.of();
        List<QuizMatchOption> matchOptions = p.getType() == ProblemType.MATCHING
                ? QuizMatchOption.shuffledFrom(p.getId(), p.getChoices())
                : List.of();
        return new QuizProblemItem(p.getId(), p.getDomain(), p.getDifficulty(), p.getType(),
                p.getQuestion(), choices, matchOptions);
    }
}
