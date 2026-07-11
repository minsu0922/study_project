package project.study.study_project.review.dto;

import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.dto.QuizChoiceItem;
import project.study.study_project.review.domain.ReviewItem;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 오늘의 복습 항목 — API 스펙(docs/10 GET /api/me/reviews/today).
 *
 * <p>문제 부분은 {@code GET /api/quiz}의 풀이용 형태와 동일하다 — <b>정답·해설·보기 정답 여부는
 * 절대 포함하지 않는다</b>. 복습도 "풀어 보는" 것이므로 답을 미리 보여주면 의미가 없다.
 * 답 제출·채점은 기존 {@code POST /api/quiz/submit}을 그대로 쓴다(쓰기 경로 단일화, docs/10).
 *
 * <p>보기 항목은 퀴즈 API의 {@link QuizChoiceItem}을 재사용 — "풀이용 보기" 계약(정답 여부 미포함)이
 * 두 API에서 완전히 같아서, 별도 DTO를 만들면 같은 규칙이 두 곳에 생길 뿐이다.
 *
 * @param domainLabel  화면 표기용 한글(예 "네트워크") — docs/02의 표기 규칙
 * @param choices      객관식만 채우고 OX/단답형은 빈 배열(퀴즈 API와 동일한 모양 유지)
 * @param stage        지금 사다리 몇 번째 칸인지(0..4)
 * @param nextReviewAt 복습 예정이었던 시각 — 목록에 나온 시점엔 이미 지난 시각이다(그래서 due)
 * @param reviewCount  사다리에 오른 뒤 푼 횟수
 */
public record ReviewTodayItem(
        Long problemId,
        Domain domain,
        String domainLabel,
        Difficulty difficulty,
        ProblemType type,
        String question,
        List<QuizChoiceItem> choices,
        int stage,
        LocalDateTime nextReviewAt,
        int reviewCount
) {
    public static ReviewTodayItem from(ReviewItem r) {
        Problem p = r.getProblem();
        // 객관식일 때만 LAZY 보기 컬렉션에 접근한다(불필요한 쿼리 방지 — QuizProblemItem과 동일).
        List<QuizChoiceItem> choices = p.getType() == ProblemType.MULTIPLE_CHOICE
                ? p.getChoices().stream().map(QuizChoiceItem::from).toList()
                : List.of();
        return new ReviewTodayItem(
                p.getId(), p.getDomain(), p.getDomain().getDisplayName(),
                p.getDifficulty(), p.getType(), p.getQuestion(), choices,
                r.getStage(), r.getNextReviewAt(), r.getReviewCount());
    }
}
