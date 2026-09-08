package project.study.study_project.review.dto;

import project.study.study_project.global.common.Domain;
import project.study.study_project.review.domain.ReviewItem;
import project.study.study_project.review.domain.ReviewStatus;

import java.time.LocalDateTime;

/**
 * 복습 현황 항목 — API 스펙(docs/10 GET /api/me/reviews). 대시보드/진척 확인용이라
 * 문제 전문(보기 등)은 싣지 않고 지문·복습 메타만 담는다(풀 문제는 /today가 담당).
 *
 * @param title 목록에 뜰 한 줄 이름(V13). <b>없을 수 있다</b> — 제목 컬럼이 생기기 전에
 *              승인된 문제는 비어 있고, 그때는 화면이 지문 앞부분으로 대신한다.
 *              2026-09-08에 더했다: 그전에는 지문만 실어서, 상황 서술로 시작하는 문제가
 *              "상품 조회 API를 로드밸런서 뒤 4대에 올려 운영한다. 한 대가…"로 잘렸다.
 *              무엇을 묻는 문제인지 알 수 없는 목록이 되는데, 그게 정확히 문제 목록 화면이
 *              제목 컬럼을 만든 이유다(docs/18).
 * @param due "지금 풀 때가 됐는지" — <b>저장된 값이 아니라 응답 조립 시 계산</b>한다.
 *            시간이 흐르면 저절로 바뀌는 값을 컬럼으로 저장하면 배치 갱신이 필요해지기
 *            때문(docs/10의 핵심 설계 포인트). 졸업한 항목은 항상 false.
 */
public record ReviewListItem(
        Long problemId,
        String title,
        String question,
        Domain domain,
        String domainLabel,
        int stage,
        ReviewStatus status,
        int reviewCount,
        LocalDateTime nextReviewAt,
        boolean due
) {
    /**
     * @param now due 판정 기준 시각 — 페이지 전체가 같은 "지금"으로 판정되도록 서비스가
     *            한 번만 구해서 넘긴다(항목마다 now()를 부르면 페이지 안에서 기준이 미세하게 갈린다)
     */
    public static ReviewListItem from(ReviewItem r, LocalDateTime now) {
        boolean due = r.getStatus() == ReviewStatus.LEARNING && !r.getNextReviewAt().isAfter(now);
        return new ReviewListItem(
                r.getProblem().getId(), r.getProblem().getTitle(), r.getProblem().getQuestion(),
                r.getProblem().getDomain(), r.getProblem().getDomain().getDisplayName(),
                r.getStage(), r.getStatus(), r.getReviewCount(), r.getNextReviewAt(), due);
    }
}
