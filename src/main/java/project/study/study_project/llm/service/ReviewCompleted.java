package project.study.study_project.llm.service;

/**
 * 검수 한 건이 <b>커밋된 뒤</b> 발행되는 신호 — 스냅샷 내보내기를 깨우는 데 쓴다.
 *
 * <h2>왜 필요했나 — 스냅샷이 한 박자 늦었다</h2>
 *
 * <p>스냅샷 세 개는 원래 {@code ApplicationRunner}로 <b>앱이 켜질 때만</b> 만들어졌다.
 * 그런데 검수는 앱이 켜진 <b>뒤에</b> 한다. 그래서 이런 순서가 됐다:
 * <pre>
 *   앱 켬  → 스냅샷이 찍힌다(아직 옛날 상태)
 *   검수함에서 승인·거절 → DB가 바뀐다
 *   앱 끔  → 파일은 그대로. 바뀐 내용이 어디에도 안 남는다
 * </pre>
 * 다음에 앱을 한 번 더 켜야 반영됐다. 2026-08-17에 실제로 겪었다 — 파일에는 08-14가 찍혀 있고
 * DB는 08-17이었으며, 그 사이 승인한 4문항이 회피 목록에 없었다. 그대로 배치가 돌았다면
 * <b>방금 승인한 문제를 다시 만들어</b> 냈을 것이다.
 *
 * <h2>왜 이벤트인가 — 서비스가 파일을 몰라도 되게</h2>
 *
 * <p>{@code LlmProblemService.approve()}에서 내보내기를 직접 부를 수도 있었다. 그러면 검수
 * 서비스가 파일 경로·JSON 직렬화·"바뀌었는지 비교" 같은 것을 알아야 하고, 스냅샷이 하나 늘 때마다
 * 검수 코드를 고치게 된다. 이벤트로 끊어 두면 <b>검수는 "끝났다"만 알리고</b> 누가 그 신호를
 * 듣는지는 모른다.
 *
 * <h2>왜 하필 커밋 뒤인가</h2>
 *
 * <p>듣는 쪽이 {@code @TransactionalEventListener(AFTER_COMMIT)}을 쓴다. 그냥
 * {@code @EventListener}면 <b>트랜잭션이 커밋되기 전에</b> 실행되어, 스냅샷이 아직 DB에 반영되지
 * 않은 상태를 찍는다 — 고치려던 "한 박자 늦음"이 그대로 남는다. 게다가 그 뒤 트랜잭션이 롤백되면
 * <b>일어나지 않은 승인이 파일에 남는다</b>.
 *
 * <p>내보내기가 실패해도 검수는 성공으로 남는다. 커밋이 이미 끝난 뒤라 되돌릴 것이 없고,
 * 원래 원칙이 그렇다 — "이 기능이 실패해도 앱은 정상이어야 한다"(각 내보내기의 {@code run} 주석).
 *
 * @param target 무엇을 검수했는지. 문제와 문서가 서로 다른 스냅샷을 건드리므로 갈라 둔다
 */
public record ReviewCompleted(Target target) {

    public enum Target {
        /** 문제 초안 승인·거절·복구 — 지문 목록과 거절 사례가 바뀔 수 있다. */
        PROBLEM,
        /** 문서 초안 승인·거절·복구 — 제목·태그·거절된 slug가 바뀔 수 있다. */
        DOCUMENT
    }

    public static ReviewCompleted problem() {
        return new ReviewCompleted(Target.PROBLEM);
    }

    public static ReviewCompleted document() {
        return new ReviewCompleted(Target.DOCUMENT);
    }
}
