package project.study.study_project.llm.dto;

import project.study.study_project.global.common.Domain;

/**
 * 분야와 제목 한 쌍 — 중복 회피 목록을 <b>[분야] 제목</b> 꼴로 내보내려고 만들었다(2026-09-03).
 *
 * <p><b>왜 분야가 필요해졌나.</b> 회피 목록이 제목만 있을 때 모델이 판단할 수 있는 것은
 * "이 제목과 같은가"뿐이었다. 새 user 메시지는 한 걸음 더 요구한다 —
 * <i>"같은 분야에 이미 문서가 있으면, 그 문서가 다룬 메커니즘과 겹치는 것도 고르지 않는다."</i>
 * 제목만으로는 그 판단을 할 수 없다. 「TIME_WAIT」가 네트워크 문서라는 것을 알아야
 * 네트워크 분야에서 연결 종료를 또 고르지 않는다.
 *
 * <p>정식 문서와 검수 대기 초안이 <b>같은 모양</b>으로 나와야 해서 저장소 두 곳이 이 타입을
 * 함께 쓴다. 각자 다른 타입으로 돌려주면 내보내는 쪽에서 두 갈래로 갈라 붙이게 된다.
 */
public record DomainTitle(Domain domain, String title) {

    /** 프롬프트에 실리는 형태. 분야는 한국어 표기로 — 모델에게 {@code NETWORK}보다 읽힌다. */
    public String labeled() {
        return "[%s] %s".formatted(domain.getDisplayName(), title);
    }
}
