package project.study.study_project.llm.service;

/**
 * 분야 설정이 바뀌었다는 신호 — 파일 내보내기({@code DomainSettingExporter})를 깨운다.
 *
 * <p>구조와 판단은 {@link TopicQueueChanged}와 같다: 서비스는 "바뀌었다"만 알리고, 그 신호를
 * 누가 듣는지는 모른다. 듣는 쪽이 {@code AFTER_COMMIT}이라 아직 커밋되지 않은 상태를 파일에
 * 찍는 일도, 롤백된 변경이 파일에 남는 일도 없다.
 *
 * <p><b>왜 {@code TopicQueueChanged}를 재사용하지 않았나.</b> 이름과 뜻이 "주제 대기열이
 * 바뀌었다"로 못 박혀 있고, 그 신호를 듣는 {@code TopicQueueExporter}는 분야 설정과 전혀 다른
 * 테이블을 스냅샷 찍는다. 하나의 이벤트에 두 내보내기를 걸면, 대기열만 바뀌었을 뿐인데 분야
 * 설정 파일까지 매번 다시 훑어 "내용이 같은지" 비교하는 낭비가 생기고, 반대로 이벤트 이름만
 * 보고 "이건 대기열 얘기겠지" 착각하기도 쉽다. 신호마다 자기 이름을 갖는 편이 어느 리스너가
 * 왜 깨어났는지 코드만 보고 알 수 있다.
 *
 * <p>필드가 없는 것은 의도다. 분야 설정은 파일 하나에 전체를 통째로 내보내므로 <b>무엇이
 * 어떻게 바뀌었는지</b>가 필요 없다 — 신호가 오면 테이블 전체를 다시 읽어 새로 쓴다.
 */
public record DomainSettingChanged() {
}
