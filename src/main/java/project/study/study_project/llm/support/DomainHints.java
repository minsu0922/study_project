package project.study.study_project.llm.support;

import project.study.study_project.global.common.Domain;

import java.util.EnumMap;
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

    /** 2026-09-21까지 코드에 박혀 있던 값. 설정 행의 초기값으로 쓰인다. */
    public static final DomainHints BUILT_IN = of(builtInMap());

    private final Map<Domain, String> hints;

    private DomainHints(Map<Domain, String> hints) {
        this.hints = hints;
    }

    public static DomainHints of(Map<Domain, String> hints) {
        EnumMap<Domain, String> copy = new EnumMap<>(Domain.class);
        hints.forEach((domain, hint) -> {
            if (hint != null && !hint.isBlank()) {
                copy.put(domain, hint.trim());
            }
        });
        return new DomainHints(copy);
    }

    /** 프롬프트에 그대로 이어 붙일 꼴 — 앞 공백과 괄호까지 붙여서 준다. 없으면 빈 문자열. */
    public String hintFor(Domain domain) {
        String raw = rawHintFor(domain);
        return raw.isEmpty() ? "" : " (" + raw + ")";
    }

    /** 괄호 없는 원문 — 화면 입력칸과 내보내기 파일이 쓴다. */
    public String rawHintFor(Domain domain) {
        return hints.getOrDefault(domain, "");
    }

    private static Map<Domain, String> builtInMap() {
        EnumMap<Domain, String> map = new EnumMap<>(Domain.class);
        map.put(Domain.BACKEND_FRAMEWORK,
                "Spring DI/IoC·Bean 생명주기·AOP·@Transactional 전파·MVC 흐름, "
                        + "JPA 영속성 컨텍스트·지연 로딩·N+1, 커넥션 풀·서블릿 컨테이너. "
                        + "순수 JVM/GC 주제는 제외");
        map.put(Domain.LANGUAGE_RUNTIME,
                "Java 언어·JVM 내부: 메모리 구조·GC·클래스로딩·동시성. "
                        + "Spring/JPA 등 프레임워크 주제는 제외");
        map.put(Domain.SOFTWARE_ENGINEERING,
                "요구사항 분석·UML·디자인 패턴·테스트 기법·형상관리·개발방법론. "
                        + "즉 사람이 코드를 만들고 관리하는 절차. "
                        + "부하·확장·장애처럼 돌아가는 시스템을 다루는 주제는 제외");
        map.put(Domain.SYSTEM_DESIGN,
                "돌아가는 시스템의 구조: 부하 분산·캐시 계층·확장·장애 대응·데이터 흐름. "
                        + "요구사항·UML·테스트 기법 같은 개발 절차 주제는 제외");
        return map;
    }
}
