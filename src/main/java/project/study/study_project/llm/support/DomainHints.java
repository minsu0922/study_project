package project.study.study_project.llm.support;

import project.study.study_project.global.common.DomainCode;

import java.util.HashMap;
import java.util.Map;

/**
 * 분야 경계 설명 — 모델에게 "이 분야는 어디까지인가"를 알려 주는 한 줄.
 *
 * <h2>왜 값 객체로 뺐나</h2>
 *
 * <p>같은 문자열이 문제 생성기와 문서 생성기의 {@code switch} 두 벌에 복사돼 있었다.
 * 둘이 어긋나면 <b>문서는 개발 절차를 썼는데 문제는 부하를 묻는</b> 날이 오고, 근거 문서를
 * 준 목적이 통째로 무너진다. 게다가 이 값은 곧 관리자 화면에서 고칠 값이라, "코드에 박힌
 * 기본값"과 "설정에서 온 값"을 같은 모양으로 다룰 자리가 필요했다.
 *
 * <h2>설정값이 오면 내장값은 쓰지 않는다</h2>
 *
 * <p>{@link #of}로 만든 것은 <b>그 맵이 전부</b>다. 빠진 분야를 {@link #BUILT_IN}으로 메우지
 * 않는다 — 화면에서 지운 힌트가 조용히 되살아나면 "지웠는데 왜 그대로인가"가 되고,
 * 그 순간부터 사람은 이 화면을 믿지 않는다. 내장값은 <b>행을 처음 만들 때의 초기값</b>으로만
 * 쓰인다({@code DomainSettingSyncRunner}).
 */
public final class DomainHints {

    /**
     * 2026-09-21까지 코드에 박혀 있던 값. 설정 행의 초기값으로 쓰인다.
     *
     * <p><b>문자열 원본은 {@link DefaultDomains}에 있고, 이 클래스는 읽기만 한다.</b> 의존은
     * {@code DomainHints → DefaultDomains} 한 방향뿐이어야 한다. 예전처럼 {@code DefaultDomains}가
     * 이 필드를 읽어 자기 힌트를 채우면서 이 필드도 {@code DefaultDomains}를 읽으면, 두 클래스의
     * static 필드가 서로를 부르는 순환이 된다. 자바는 이것을 컴파일 오류로 막지 않는다 — 먼저
     * 초기화되는 쪽이 상대 필드를 {@code null}로 읽어, 힌트 맵이 조용히 비거나 NPE가 난다.
     * 어느 쪽이 먼저 로드되는지는 호출 순서에 달려 있어 테스트마다 결과가 달라질 수도 있다.
     */
    public static final DomainHints BUILT_IN = of(DefaultDomains.hints());

    private final Map<DomainCode, String> hints;

    private DomainHints(Map<DomainCode, String> hints) {
        this.hints = hints;
    }

    public static DomainHints of(Map<DomainCode, String> hints) {
        // 예전엔 EnumMap이었다. 이 맵은 hintFor/rawHintFor의 조회에만 쓰이고 밖으로 순회되지
        // 않으므로(돌려주는 접근자가 없다) 순서가 드러날 자리가 없다 — HashMap이면 충분하다.
        Map<DomainCode, String> copy = new HashMap<>();
        hints.forEach((domain, hint) -> {
            if (hint != null && !hint.isBlank()) {
                copy.put(domain, hint.trim());
            }
        });
        return new DomainHints(copy);
    }

    /** 프롬프트에 그대로 이어 붙일 꼴 — 앞 공백과 괄호까지 붙여서 준다. 없으면 빈 문자열. */
    public String hintFor(DomainCode domain) {
        String raw = rawHintFor(domain);
        return raw.isEmpty() ? "" : " (" + raw + ")";
    }

    /** 괄호 없는 원문 — 화면 입력칸과 내보내기 파일이 쓴다. */
    public String rawHintFor(DomainCode domain) {
        return hints.getOrDefault(domain, "");
    }
}
