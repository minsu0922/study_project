package project.study.study_project.llm.service;

/**
 * 주제 대기열이 바뀌었다는 신호 — 파일 내보내기({@code TopicQueueExporter})를 깨운다.
 *
 * <p>구조와 판단은 {@link ReviewCompleted}와 같다: 서비스는 "바뀌었다"만 알리고, 그 신호를
 * 누가 듣는지는 모른다. 듣는 쪽이 {@code AFTER_COMMIT}이라 아직 커밋되지 않은 상태를 파일에
 * 찍는 일도, 롤백된 변경이 파일에 남는 일도 없다.
 *
 * <p><b>왜 {@code ReviewCompleted}에 값을 하나 더 넣지 않았나.</b> 그 이벤트의 이름과 뜻은
 * "검수 한 건이 끝났다"이고, 듣는 쪽 셋이 전부 검수 결과 스냅샷을 다시 찍는다. 주제 추가는
 * 검수가 아니다 — 억지로 얹으면 {@code ReviewCompleted.Target.TOPIC_QUEUE}처럼 이름이
 * 스스로 모순되는 값이 생기고, 기존 리스너 셋이 전부 "내 것이 아닌 신호"를 걸러 내야 한다.
 *
 * <p>필드가 없는 것은 의도다. 대기열은 파일 하나에 통째로 내보내므로 <b>무엇이 어떻게
 * 바뀌었는지</b>가 필요 없다 — 신호가 오면 전부 다시 쓴다.
 */
public record TopicQueueChanged() {
}
