package project.study.study_project.user.service;

/**
 * 탈퇴하는 사용자의 기록을 지우는 쪽이 구현한다. {@link AccountService}가 사용자 행을 지우기 전에
 * 같은 트랜잭션 안에서 {@code @Order} 순으로 부른다.
 *
 * <p><b>순서가 곧 제약 조건이다.</b> 오늘의 퀴즈 항목이 제출을 가리키므로(RESTRICT) 데일리가
 * 제출보다 먼저 지워져야 한다. 구현마다 {@link #ORDER_DAILY_QUIZ}처럼 아래 상수를 쓴다 —
 * 숫자를 각자 정하면 차례가 한눈에 보이지 않는다.
 *
 * <p>DB의 ON DELETE CASCADE·SET NULL이 처리하는 표(북마크·알림·추천·글·댓글)는 구현이 없다.
 */
public interface AccountDataCleaner {

    int ORDER_DAILY_QUIZ = 10;
    int ORDER_REVIEW = 20;
    int ORDER_SUBMISSION = 30;
    int ORDER_REPORT = 40;

    void deleteFor(Long userId);
}
