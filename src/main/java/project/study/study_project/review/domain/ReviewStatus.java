package project.study.study_project.review.domain;

/**
 * 복습 항목의 저장 상태 — 문서 10-review-recommendation 기준.
 *
 * <p><b>왜 2가지뿐인가</b>: ADR-0002에 예시로 적었던 "미복습(due — 복습일이 지났는데 아직 안 품)"은
 * {@code next_review_at <= 지금}이라는 시간 비교로 <b>파생되는 값</b>이라 상태로 저장하지 않는다.
 * 저장하면 매일 자정 배치로 갱신해야 하는 상태가 생긴다 — "시간이 흐르면 저절로 바뀌는 값은
 * 저장하지 말고 조회 시점에 계산한다"는 원칙(docs/10).
 */
public enum ReviewStatus {

    /** 사다리를 오르는 중 — 복습 추천 대상. */
    LEARNING,

    /** 졸업(마지막 칸에서 정답) — 추천에서 제외. 단, 다시 틀리면 LEARNING으로 복귀한다. */
    GRADUATED
}
