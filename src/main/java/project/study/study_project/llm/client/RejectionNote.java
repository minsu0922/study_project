package project.study.study_project.llm.client;

/**
 * "이 문제가 이런 이유로 거절됐다" — 프롬프트에 되먹이는 검수 피드백 한 건(docs/14).
 *
 * <p><b>왜 사유만이 아니라 지문도 함께 넣는가.</b> "부정형 문제 금지" 같은 사유만 던지면
 * 모델은 그것이 어떤 문제를 두고 한 말인지 알 수 없다. 거절된 지문과 짝지어 주면
 * "이런 문제가 이런 이유로 탈락했다"는 구체적 사례가 되고, 추상적 규칙보다 훨씬 강한 신호가 된다
 * (난이도 규칙에 예시 문항을 넣었을 때 확인한 효과와 같은 원리 — ClaudeProblemGenerator 주석 참고).
 *
 * <p><b>왜 이 타입이 client 패키지에 있는가.</b> 세 곳이 같은 모양을 공유해야 하기 때문이다:
 * <ul>
 *   <li>{@code LlmProblemService} — DB에서 읽어 만든다(관리자 수동 생성 경로)
 *   <li>{@code DraftGeneratorCli} — 스냅샷 파일에서 읽어 만든다(GitHub Actions 경로)
 *   <li>{@code ClaudeProblemGenerator} — 프롬프트 문장으로 조립한다
 * </ul>
 * 프롬프트 조립을 <b>생성기 한 곳에서만</b> 하려면 두 경로가 같은 타입을 넘겨야 한다.
 * 미리 조립된 문자열을 넘기게 하면 서비스와 CLI가 각자 문장을 만들게 되고,
 * 언젠가 한쪽 형식만 바뀌어 어긋난다(중복 회피 목록에서 지킨 것과 같은 단일 경로 원칙).
 *
 * <p>이 record는 스냅샷 파일({@code generated/_rejection-notes.json})의 항목 형태이기도 하다 —
 * Jackson이 그대로 역직렬화하므로 파일 전용 DTO를 따로 두지 않는다.
 *
 * @param question 거절된 문제의 지문(프롬프트에 넣을 때 앞부분만 잘라 쓴다)
 * @param reason   검수자가 남긴 거절 사유
 */
public record RejectionNote(String question, String reason) {
}
