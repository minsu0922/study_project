package project.study.study_project.llm.support;

/**
 * "지금 쓸 분야 힌트"를 그때그때 내주는 공급자 — 생성기가 힌트의 <b>출처</b>를 모르게 한다.
 *
 * <h2>왜 값({@link DomainHints})이 아니라 공급자를 주입하나</h2>
 *
 * <p>처음에는 생성기가 {@code DomainHints} 값을 직접 받았다. 그런데 스프링 빈은 기동할 때
 * 한 번만 만들어지므로, 값을 받는 구조로는 두 길밖에 없었다 — 내장값을 박아 두거나(관리자
 * 화면에서 고친 힌트가 앱 안 생성에서 무시된다. 최종 리뷰 Important 2가 잡은 버그다),
 * 기동 시점의 DB 값을 굳혀 두거나(고친 힌트가 <b>재시작해야</b> 반영된다. Task 7이
 * 배치 후보 목록에서 일부러 없앤 바로 그 증상이다). 공급자를 받아 <b>프롬프트를 짜는 순간</b>
 * {@link #current()}를 부르면 둘 다 피한다.
 *
 * <h2>왜 {@code llm.support}에 두나</h2>
 *
 * <p>{@code DomainHints}가 이미 여기 있고, 무엇보다 <b>생성기({@code llm.client})가
 * 서비스({@code llm.service})를 직접 알지 않게</b> 하려는 것이다. 출처는 실행 환경마다 다르다 —
 * 앱은 DB({@code DomainSettingService}), 배치 CLI는 {@code _domain-settings.json} 파일,
 * 테스트와 평가 CLI는 상수({@link DomainHints#BUILT_IN})다. 생성기가 {@code DomainSettingService}
 * 타입을 직접 받으면 DB가 없는 CLI에서 생성기를 만들 방법이 사라진다.
 *
 * <p>함수형 인터페이스라 상수 쪽은 {@code () -> hints} 한 줄로 감싼다.
 */
@FunctionalInterface
public interface DomainHintsProvider {

    /**
     * 지금 이 순간의 힌트. 호출할 때마다 다시 읽어도 되는 값이어야 한다 — 생성기는 이 결과를
     * 필드에 담아 두지 않고 프롬프트를 짤 때마다 부른다.
     */
    DomainHints current();
}
