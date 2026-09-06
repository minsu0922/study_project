package project.study.study_project.quiz.dto;

/**
 * 랜딩 화면의 집계 한 줄 — "문제 296개 · 개념 문서 24편 · 12개 분야" (2026-09-06).
 *
 * <h2>왜 서버가 세나</h2>
 *
 * <p>HTML에 숫자를 글로 박으면 배치가 도는 <b>다음 날</b> 거짓말이 된다. 이 앱은 매일
 * 문제를 더하는 것이 기능이라, 정적인 숫자가 다른 서비스보다 훨씬 빨리 낡는다.
 * 그리고 이 숫자를 보는 사람은 <b>가입할지 말지 정하는 중</b>이라, 여기서 틀린 말을 하면
 * 서비스 전체가 대충 만든 것으로 읽힌다.
 *
 * <h2>왜 인증이 없나</h2>
 *
 * <p>이 숫자를 보는 사람은 아직 가입하지 않은 사람이다. 인증을 걸면 정작 봐야 할 사람이
 * 못 본다. 공개해도 새는 것이 없다 — 개별 문제의 <b>내용</b>이 아니라 개수뿐이고,
 * 문제 본문은 이미 공개 API로 읽을 수 있다("화면과 문제는 누구나, 채점만 로그인").
 *
 * @param problemCount  풀 수 있는 문제 수. {@code Problem} 테이블에는 승인된 것만 있다
 *                      (AI 초안은 {@code GeneratedProblemDraft}라는 다른 테이블)
 * @param documentCount 개념 문서 편수. 입문편·심화편이 각각 한 편으로 세어진다 —
 *                      독자에게는 읽을 거리 두 개가 맞다
 * @param domainCount   분야 수. {@code Domain} enum의 크기라 DB를 보지 않는다
 */
public record PublicStatsResponse(
        long problemCount,
        long documentCount,
        int domainCount
) {
}
