package project.study.study_project.llm.support;

import project.study.study_project.global.common.DomainCode;

import java.util.List;

/**
 * 분야 정보를 읽는 단일 창구 — "지금 어떤 분야가 있고, 이름과 힌트가 무엇인가"를 묻는 자리다.
 *
 * <h2>구현이 둘이다</h2>
 *
 * <p>앱(관리자 화면·API 서빙)은 {@code domain_setting} 테이블을 읽는 구현을 쓰고, 배치(날짜
 * 순환 생성기)는 {@code generated/_domain-settings.json} 파일을 읽는 구현을 쓴다. 두 프로세스가
 * DB 커넥션을 공유하지 않기 때문이다(docs/superpowers/specs/2026-09-22-domain-registry-design.md
 * 8절) — 배치는 GitHub Actions에서 별도로 돌고, 그 시점엔 앱 DB에 손이 안 닿는다.
 *
 * <h2>쓰는 순간에 읽어야 한다</h2>
 *
 * <p>이 인터페이스의 구현체를 생성자에서 한 번 조회해 필드에 캐시해 두면 안 된다. 관리자가
 * 화면에서 분야 이름이나 힌트를 고쳐도, 캐시해 둔 값을 쓰는 코드는 재시작 전까지 옛날 값을
 * 계속 보여준다 — "고쳤는데 왜 그대로인가"가 되는 버그다(지난 분야 설정 작업 최종 리뷰 I-2에서
 * 실제로 겪었다). 그래서 이름·힌트는 항상 호출 시점에 {@link #all()}/{@link #displayName}/
 * {@link #hints()}를 다시 불러 읽는다.
 */
public interface DomainCatalog {

    /** 정렬 순서대로 전체 분야(켜짐+꺼짐)를 준다 — 관리자 목록 화면용. */
    List<DomainEntry> all();

    /** 켜진 분야의 코드만, 정렬 순서대로 — 배치·문제 생성 후보용. */
    List<DomainCode> enabled();

    /** 화면 표기 이름. 모르는 코드면 코드 문자열 그대로(빈칸보다 낫다). */
    String displayName(DomainCode code);

    /** 분야 경계 힌트 묶음 — 프롬프트 조립에 쓴다. */
    DomainHints hints();

    /** 이 코드가 지금 등록된 분야인지. */
    boolean exists(DomainCode code);
}
