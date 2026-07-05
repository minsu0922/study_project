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
 * @param choices 객관식만 보기 목록을 채우고, OX/단답형은 빈 배열(스펙과 동일한 모양 유지 —
 *                클라이언트가 타입 분기 없이 항상 배열로 다룰 수 있게 null 대신 빈 리스트)
 */
public record QuizProblemItem(
        Long id,
        Domain domain,
        Difficulty difficulty,
        ProblemType type,
        String question,
        List<QuizChoiceItem> choices
) {
    public static QuizProblemItem from(Problem p) {
        // 객관식일 때만 LAZY 보기 컬렉션에 접근한다(불필요한 쿼리 방지).
        List<QuizChoiceItem> choices = p.getType() == ProblemType.MULTIPLE_CHOICE
                ? p.getChoices().stream().map(QuizChoiceItem::from).toList()
                : List.of();
        return new QuizProblemItem(p.getId(), p.getDomain(), p.getDifficulty(), p.getType(),
                p.getQuestion(), choices);
    }
}
